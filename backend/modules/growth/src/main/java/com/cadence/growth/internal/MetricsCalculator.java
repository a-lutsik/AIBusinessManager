package com.cadence.growth.internal;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.VisitFact;
import com.cadence.catalog.api.ScheduleExceptionView;
import com.cadence.catalog.api.WeeklyInterval;
import com.cadence.catalog.api.WorkingHours;
import com.cadence.growth.api.MetricDefinitions;
import com.cadence.growth.api.MetricView;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The six metrics as pure functions of visit facts and the schedule (ADR 0001 §4), no I/O and no clock: the same
 * facts, schedule, zone, window and T always give the same numbers, which is what makes a snapshot replayable.
 *
 * <p>W = dates {@code ws..we} in {@code zone}; Wi = [start of ws, start of we + 1); "in W" = service start in Wi;
 * day(x) = date of x in the zone; T = {@code asOf}. With {@code specialistId} set, the facts are first narrowed to that
 * specialist and S (utilization) to that specialist if active. Minimum samples come from {@link MetricDefinitions}.
 * Completed visits of one client on one date count as one visit day ({@link #visitDays}).
 */
final class MetricsCalculator {

    /** Inputs outside the journal: specialists active at T and their weekly schedule and exceptions. */
    record Schedule(Set<UUID> activeSpecialists, Map<UUID, List<WeeklyInterval>> weekly,
                    Map<UUID, List<ScheduleExceptionView>> exceptions) {
    }

    enum ChurnLevel { HIGH, WARNING, OK }

    /** One evaluated client of CHURN_RISK (kept for signals and reactivation, not stored). */
    record ChurnAssessment(UUID clientId, Instant lastCompleted, long daysSinceLast, double typicalDays,
                           boolean typicalFromTenant, ChurnLevel level) {
    }

    /** COMPLETED visits of one service in W (LOW_VALUE_SERVICES). */
    record ServiceHours(UUID serviceId, String serviceName, int completedVisits, long valueMinor, long minutes) {
    }

    /** The six metrics plus the figures behind them that the signal rules quote. */
    record Result(List<MetricView> metrics, List<ChurnAssessment> churn, long bookedMinutes, long availableMinutes,
                  int noShows, int dueVisits, List<ServiceHours> services) {
    }

    static final String SLOT_UTILIZATION = "SLOT_UTILIZATION";
    static final String REPEAT_RATE = "REPEAT_RATE";
    static final String RETURN_INTERVAL = "RETURN_INTERVAL";
    static final String NO_SHOW_RATE = "NO_SHOW_RATE";
    static final String REVENUE_PER_HOUR = "REVENUE_PER_HOUR";
    static final String CHURN_RISK = "CHURN_RISK";
    static final Duration CHURN_LOOKBACK = Duration.ofDays(365);

    /** Completed visits of one client in journal order: service start, then id as PostgreSQL orders uuids. */
    private static final Comparator<VisitFact> BY_START = Comparator.comparing(VisitFact::serviceStart)
            .thenComparing(VisitFact::appointmentId, (a, b) -> {
                int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
                return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
            });

    private MetricsCalculator() {
    }

    static Result compute(List<VisitFact> allFacts, Schedule schedule, ZoneId zone, LocalDate ws, LocalDate we,
                          Instant asOf, UUID specialistId) {
        List<VisitFact> facts = specialistId == null
                ? allFacts
                : allFacts.stream().filter(f -> specialistId.equals(f.specialistId())).toList();
        Window w = new Window(zone, ws, we);
        List<MetricView> metrics = new ArrayList<>();

        // 1. SLOT_UTILIZATION
        Set<UUID> s = specialistId == null
                ? schedule.activeSpecialists()
                : schedule.activeSpecialists().contains(specialistId) ? Set.of(specialistId) : Set.of();
        long booked = 0;
        for (VisitFact f : facts) {
            if (f.status().occupies() && s.contains(f.specialistId())) {
                booked += w.minutesInside(f.serviceStart(), f.serviceEnd());
            }
        }
        long available = 0;
        int exceptionsApplied = 0;
        for (UUID specialist : s) {
            List<WeeklyInterval> weekly = schedule.weekly().getOrDefault(specialist, List.of());
            List<ScheduleExceptionView> exceptions = schedule.exceptions().getOrDefault(specialist, List.of());
            available += WorkingHours.availableMinutes(zone, ws, we, weekly, exceptions);
            exceptionsApplied += WorkingHours.appliedExceptionCount(ws, we, exceptions);
        }
        String exceptionsNote = " (" + exceptionsApplied + " schedule exception" + (exceptionsApplied == 1 ? "" : "s") + " applied)";
        if (available < MetricDefinitions.minSampleSize(SLOT_UTILIZATION)) {
            metrics.add(insufficient(SLOT_UTILIZATION, (int) available,
                    "No working minutes of active specialists in the window" + exceptionsNote, specialistId));
        } else {
            metrics.add(MetricView.basic(SLOT_UTILIZATION, booked / (double) available, (int) available, false,
                    "Booked " + booked + " / " + available + " available minutes" + exceptionsNote, specialistId));
        }

        // Completed visits per client up to the end of Wi (2, 3) and up to T (6), in journal order.
        Map<UUID, List<VisitFact>> completedBeforeWindowEnd = completedByClient(facts, f -> f.serviceStart().isBefore(w.end()));
        Map<UUID, List<VisitFact>> completedUpToT = completedByClient(facts, f -> !f.serviceStart().isAfter(asOf));

        // 2. REPEAT_RATE
        List<UUID> p = facts.stream()
                .filter(f -> f.status() == AppointmentStatus.COMPLETED && w.contains(f.serviceStart()))
                .map(VisitFact::clientId)
                .distinct()
                .toList();
        long r = p.stream().filter(c -> visitDays(completedBeforeWindowEnd.getOrDefault(c, List.of()), zone).size() >= 2).count();
        if (p.size() < MetricDefinitions.minSampleSize(REPEAT_RATE)) {
            metrics.add(insufficient(REPEAT_RATE, p.size(), "Need at least " + MetricDefinitions.minSampleSize(REPEAT_RATE)
                    + " clients with a completed visit in the window, have " + p.size(), specialistId));
        } else {
            metrics.add(MetricView.basic(REPEAT_RATE, r / (double) p.size(), p.size(), false,
                    r + " of " + p.size() + " clients with a completed visit in the window had completed visits on at least "
                            + "two days by its end", specialistId));
        }

        // 3. RETURN_INTERVAL
        List<Double> personal = new ArrayList<>();
        for (UUID c : p) {
            List<LocalDate> days = visitDays(completedBeforeWindowEnd.getOrDefault(c, List.of()), zone);
            if (days.size() >= 2) {
                personal.add(median(gaps(days)));
            }
        }
        if (personal.size() < MetricDefinitions.minSampleSize(RETURN_INTERVAL)) {
            metrics.add(insufficient(RETURN_INTERVAL, personal.size(), "Need at least "
                    + MetricDefinitions.minSampleSize(RETURN_INTERVAL) + " clients with completed visits on two days, have "
                    + personal.size(), specialistId));
        } else {
            double value = median(personal);
            metrics.add(MetricView.basic(RETURN_INTERVAL, value, personal.size(), false,
                    "Median of " + personal.size() + " clients' median gaps between completed visits: " + value + " days",
                    specialistId));
        }

        // 4. NO_SHOW_RATE
        List<VisitFact> due = facts.stream()
                .filter(f -> w.contains(f.serviceStart()) && f.serviceStart().isBefore(asOf) && f.status().occupies())
                .toList();
        int noShows = (int) due.stream().filter(f -> f.status() == AppointmentStatus.NO_SHOW).count();
        int unmarked = (int) due.stream()
                .filter(f -> f.status() == AppointmentStatus.PENDING || f.status() == AppointmentStatus.CONFIRMED)
                .count();
        if (due.size() < MetricDefinitions.minSampleSize(NO_SHOW_RATE)) {
            metrics.add(insufficient(NO_SHOW_RATE, due.size(), "Need at least " + MetricDefinitions.minSampleSize(NO_SHOW_RATE)
                    + " visits that reached their start, have " + due.size(), specialistId));
        } else {
            metrics.add(MetricView.basic(NO_SHOW_RATE, noShows / (double) due.size(), due.size(), false,
                    noShows + " no-shows of " + due.size() + " visits that reached their start (" + unmarked
                            + " not marked yet)", specialistId));
        }

        // 5. REVENUE_PER_HOUR
        List<VisitFact> completedInW = facts.stream()
                .filter(f -> f.status() == AppointmentStatus.COMPLETED && w.contains(f.serviceStart()))
                .toList();
        long minutes = completedInW.stream().mapToLong(VisitFact::durationMinutes).sum();
        long valueMinor = completedInW.stream().mapToLong(f -> (long) f.priceMinor() - f.discountMinor()).sum();
        if (minutes < MetricDefinitions.minSampleSize(REVENUE_PER_HOUR)) {
            metrics.add(insufficient(REVENUE_PER_HOUR, (int) minutes, "No completed service minutes in the window", specialistId));
        } else {
            double hours = minutes / 60.0;
            metrics.add(MetricView.basic(REVENUE_PER_HOUR, valueMinor / hours, (int) minutes, false,
                    "Completed value " + valueMinor + " minor units over " + hours + " worked hours ("
                            + completedInW.size() + " visits)", specialistId));
        }

        // 6. CHURN_RISK
        List<ChurnAssessment> churn = new ArrayList<>();
        Map<UUID, Double> typical = new LinkedHashMap<>();
        completedUpToT.forEach((c, history) -> {
            List<LocalDate> days = visitDays(history, zone);
            if (days.size() >= 2) {
                typical.put(c, median(gaps(days)));
            }
        });
        Double tenantTypical = typical.isEmpty() ? null : median(new ArrayList<>(typical.values()));
        Instant lookback = asOf.minus(CHURN_LOOKBACK);
        LocalDate dayT = day(asOf, zone);
        int excluded = 0;
        for (Map.Entry<UUID, List<VisitFact>> e : completedUpToT.entrySet()) {
            boolean recent = e.getValue().stream().anyMatch(f -> !f.serviceStart().isBefore(lookback));
            if (!recent) {
                continue;
            }
            Double own = typical.get(e.getKey());
            Double typicalDays = own != null ? own : tenantTypical;
            if (typicalDays == null) {
                excluded++;
                continue;
            }
            VisitFact last = e.getValue().getLast();
            long days = ChronoUnit.DAYS.between(day(last.serviceStart(), zone), dayT);
            ChurnLevel level = days >= 2 * typicalDays ? ChurnLevel.HIGH
                    : days >= 1.5 * typicalDays ? ChurnLevel.WARNING : ChurnLevel.OK;
            churn.add(new ChurnAssessment(e.getKey(), last.serviceStart(), days, typicalDays, own == null, level));
        }
        long high = churn.stream().filter(a -> a.level() == ChurnLevel.HIGH).count();
        long warning = churn.stream().filter(a -> a.level() == ChurnLevel.WARNING).count();
        String excludedNote = excluded == 0 ? "" : "; " + excluded + " without a typical interval excluded";
        if (churn.size() < MetricDefinitions.minSampleSize(CHURN_RISK)) {
            metrics.add(insufficient(CHURN_RISK, churn.size(), "Need at least " + MetricDefinitions.minSampleSize(CHURN_RISK)
                    + " clients with a completed visit in the last 365 days, have " + churn.size() + excludedNote, specialistId));
        } else {
            String example = churn.stream()
                    .filter(a -> a.level() != ChurnLevel.OK)
                    .max(Comparator.comparingDouble((ChurnAssessment a) -> a.daysSinceLast() / Math.max(a.typicalDays(), 1e-9))
                            .thenComparing(ChurnAssessment::clientId))
                    .map(a -> "; e.g. last visit " + a.daysSinceLast() + " days ago vs usual " + a.typicalDays())
                    .orElse("");
            metrics.add(MetricView.basic(CHURN_RISK, high / (double) churn.size(), churn.size(), false,
                    high + " high risk and " + warning + " warning of " + churn.size()
                            + " clients with a completed visit in the last 365 days" + excludedNote + example, specialistId));
        }

        return new Result(metrics, churn, booked, available, noShows, due.size(), serviceHours(completedInW));
    }

    private static MetricView insufficient(String key, int sample, String explanation, UUID specialistId) {
        return MetricView.basic(key, null, sample, true, explanation, specialistId);
    }

    private static Map<UUID, List<VisitFact>> completedByClient(List<VisitFact> facts, Predicate<VisitFact> filter) {
        Map<UUID, List<VisitFact>> byClient = new LinkedHashMap<>();
        facts.stream()
                .filter(f -> f.status() == AppointmentStatus.COMPLETED && filter.test(f))
                .sorted(BY_START)
                .forEach(f -> byClient.computeIfAbsent(f.clientId(), k -> new ArrayList<>()).add(f));
        return byClient;
    }

    /**
     * Review decision M1: a client's completed visits on one tenant-zone date are one visit day. Distinct visit days,
     * ascending; "two completed visits" in REPEAT_RATE, RETURN_INTERVAL and CHURN_RISK means two visit days.
     */
    static List<LocalDate> visitDays(List<VisitFact> history, ZoneId zone) {
        return history.stream().map(f -> day(f.serviceStart(), zone)).distinct().sorted().toList();
    }

    /** Days between consecutive visit days. */
    private static List<Double> gaps(List<LocalDate> days) {
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < days.size(); i++) {
            gaps.add((double) ChronoUnit.DAYS.between(days.get(i - 1), days.get(i)));
        }
        return gaps;
    }

    /** Median; for an even count the mean of the two middle values (fractional). */
    static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(mid - 1) + sorted.get(mid)) / 2.0 : sorted.get(mid);
    }

    private static LocalDate day(Instant instant, ZoneId zone) {
        return LocalDate.ofInstant(instant, zone);
    }

    private static List<ServiceHours> serviceHours(List<VisitFact> completedInW) {
        Map<UUID, List<VisitFact>> byService = new LinkedHashMap<>();
        completedInW.stream().sorted(BY_START)
                .forEach(f -> byService.computeIfAbsent(f.serviceId(), k -> new ArrayList<>()).add(f));
        List<ServiceHours> result = new ArrayList<>();
        byService.forEach((serviceId, visits) -> result.add(new ServiceHours(
                serviceId,
                visits.getLast().serviceName(),
                visits.size(),
                visits.stream().mapToLong(f -> (long) f.priceMinor() - f.discountMinor()).sum(),
                visits.stream().mapToLong(VisitFact::durationMinutes).sum()
        )));
        return result;
    }

    /** Wi of the window. */
    private record Window(Instant start, Instant end) {
        Window(ZoneId zone, LocalDate ws, LocalDate we) {
            this(ws.atStartOfDay(zone).toInstant(), we.plusDays(1).atStartOfDay(zone).toInstant());
        }

        boolean contains(Instant instant) {
            return !instant.isBefore(start) && instant.isBefore(end);
        }

        /** Whole minutes of [from, to) inside Wi (truncated). */
        long minutesInside(Instant from, Instant to) {
            Instant a = from.isAfter(start) ? from : start;
            Instant b = to.isBefore(end) ? to : end;
            return b.isAfter(a) ? Duration.between(a, b).toMinutes() : 0;
        }
    }
}

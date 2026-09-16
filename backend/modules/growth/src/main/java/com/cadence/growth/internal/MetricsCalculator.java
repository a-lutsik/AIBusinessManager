package com.cadence.growth.internal;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.catalog.api.WeeklyInterval;
import com.cadence.growth.api.MetricView;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
final class MetricsCalculator {

    private final Clock clock;

    MetricsCalculator(Clock clock) {
        this.clock = clock;
    }

    List<MetricView> compute(
            List<AppointmentView> visits,
            List<WeeklyInterval> weekly,
            ZoneId zone,
            LocalDate from,
            LocalDate to,
            UUID specialistId
    ) {
        Instant now = clock.instant();
        List<MetricView> metrics = new ArrayList<>();
        metrics.add(slotUtilization(visits, weekly, zone, from, to, specialistId));
        metrics.add(repeatRate(visits, specialistId));
        metrics.add(returnInterval(visits, specialistId));
        metrics.add(noShowRate(visits, specialistId));
        metrics.add(revenuePerHour(visits, specialistId));
        metrics.add(churnRisk(visits, specialistId, now));
        return metrics;
    }

    private MetricView slotUtilization(
            List<AppointmentView> visits,
            List<WeeklyInterval> weekly,
            ZoneId zone,
            LocalDate from,
            LocalDate to,
            UUID specialistId
    ) {
        long available = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            int weekday = d.getDayOfWeek().getValue();
            for (WeeklyInterval w : weekly) {
                if (w.weekday() == weekday) {
                    available += Math.max(0, w.endMinute() - w.startMinute());
                }
            }
        }
        long booked = visits.stream()
                .filter(v -> v.status().occupies())
                .mapToLong(v -> Duration.between(v.serviceStart(), v.serviceEnd()).toMinutes())
                .sum();
        if (available == 0) {
            return new MetricView("SLOT_UTILIZATION", null, 0, true, "No working minutes in the window", specialistId);
        }
        double value = booked / (double) available;
        return new MetricView(
                "SLOT_UTILIZATION",
                value,
                (int) available,
                false,
                "Booked " + booked + " service minutes / " + available + " available minutes",
                specialistId
        );
    }

    private MetricView repeatRate(List<AppointmentView> visits, UUID specialistId) {
        Map<UUID, Long> completed = visits.stream()
                .filter(v -> v.status() == AppointmentStatus.COMPLETED)
                .collect(Collectors.groupingBy(AppointmentView::clientId, Collectors.counting()));
        long clients = completed.size();
        if (clients < 10) {
            return new MetricView("REPEAT_RATE", null, (int) clients, true, "Need at least 10 clients with a completed visit", specialistId);
        }
        long repeats = completed.values().stream().filter(c -> c >= 2).count();
        double value = repeats / (double) clients;
        return new MetricView("REPEAT_RATE", value, (int) clients, false, repeats + " returning clients / " + clients + " clients with a completed visit", specialistId);
    }

    private MetricView returnInterval(List<AppointmentView> visits, UUID specialistId) {
        Map<UUID, List<AppointmentView>> byClient = visits.stream()
                .filter(v -> v.status() == AppointmentStatus.COMPLETED)
                .sorted(Comparator.comparing(AppointmentView::serviceStart))
                .collect(Collectors.groupingBy(AppointmentView::clientId));
        List<Long> personalMedians = new ArrayList<>();
        for (List<AppointmentView> history : byClient.values()) {
            if (history.size() < 2) {
                continue;
            }
            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < history.size(); i++) {
                gaps.add(Duration.between(history.get(i - 1).serviceStart(), history.get(i).serviceStart()).toDays());
            }
            personalMedians.add(median(gaps));
        }
        if (personalMedians.size() < 3) {
            return new MetricView("RETURN_INTERVAL", null, personalMedians.size(), true, "Need clients with at least two completed visits", specialistId);
        }
        double value = median(personalMedians);
        return new MetricView("RETURN_INTERVAL", value, personalMedians.size(), false, "Median of per-client median return intervals: " + value + " days", specialistId);
    }

    private MetricView noShowRate(List<AppointmentView> visits, UUID specialistId) {
        long arrived = visits.stream()
                .filter(v -> v.status() == AppointmentStatus.COMPLETED || v.status() == AppointmentStatus.NO_SHOW)
                .count();
        long noShows = visits.stream().filter(v -> v.status() == AppointmentStatus.NO_SHOW).count();
        if (arrived < 5) {
            return new MetricView("NO_SHOW_RATE", null, (int) arrived, true, "Not enough visits that reached start time", specialistId);
        }
        double value = noShows / (double) arrived;
        return new MetricView("NO_SHOW_RATE", value, (int) arrived, false, noShows + " no-shows / " + arrived + " visits that reached start time", specialistId);
    }

    private MetricView revenuePerHour(List<AppointmentView> visits, UUID specialistId) {
        List<AppointmentView> completed = visits.stream().filter(v -> v.status() == AppointmentStatus.COMPLETED).toList();
        long minutes = completed.stream().mapToLong(v -> v.durationSnapshot()).sum();
        if (minutes == 0) {
            return new MetricView("REVENUE_PER_HOUR", null, 0, true, "No completed service minutes", specialistId);
        }
        long valueMinor = completed.stream().mapToLong(v -> v.priceSnapshot() - v.discountAmount()).sum();
        double hours = minutes / 60.0;
        double value = valueMinor / hours;
        return new MetricView("REVENUE_PER_HOUR", value, completed.size(), false, "Completed value " + valueMinor + " minor units / " + hours + " worked hours", specialistId);
    }

    private MetricView churnRisk(List<AppointmentView> visits, UUID specialistId, Instant now) {
        Map<UUID, List<AppointmentView>> byClient = visits.stream()
                .filter(v -> v.status() == AppointmentStatus.COMPLETED)
                .sorted(Comparator.comparing(AppointmentView::serviceStart))
                .collect(Collectors.groupingBy(AppointmentView::clientId));
        long high = 0;
        long considered = 0;
        for (List<AppointmentView> history : byClient.values()) {
            if (history.isEmpty()) {
                continue;
            }
            considered++;
            AppointmentView last = history.get(history.size() - 1);
            long days = Duration.between(last.serviceStart(), now).toDays();
            long typical = 21;
            if (history.size() >= 2) {
                List<Long> gaps = new ArrayList<>();
                for (int i = 1; i < history.size(); i++) {
                    gaps.add(Duration.between(history.get(i - 1).serviceStart(), history.get(i).serviceStart()).toDays());
                }
                typical = median(gaps);
            }
            if (typical > 0 && days >= typical * 2) {
                high++;
            }
        }
        if (considered < 5) {
            return new MetricView("CHURN_RISK", null, (int) considered, true, "Not enough completed-client history", specialistId);
        }
        double value = high / (double) considered;
        return new MetricView("CHURN_RISK", value, (int) considered, false, high + " clients beyond 2× their typical return interval", specialistId);
    }

    private long median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 0) {
            return (sorted.get(mid - 1) + sorted.get(mid)) / 2;
        }
        return sorted.get(mid);
    }
}

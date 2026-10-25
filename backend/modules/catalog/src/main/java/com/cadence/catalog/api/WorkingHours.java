package com.cadence.catalog.api;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The working-day rule, pure and shared: free slots (booking-engine {@code SlotCalculator}) and the available minutes
 * of slot utilization (growth) both read a specialist's working windows from here, so the two can never disagree.
 *
 * <p>Rule for one date: an exception of kind {@code DAY_OFF} gives no window; a {@code SHIFT} with both minutes set
 * replaces the weekly windows by its own; any other exception (unknown kind, or a SHIFT without minutes) is ignored
 * and the weekly windows of that ISO weekday apply. Several exceptions on one date are resolved by a fixed rule that
 * does not depend on their order ({@link #appliedException}; {@code schedule_exception.kind} is free text and the
 * date is not unique, see tech-debt TD-31).
 *
 * <p>Minute of day: a window minute {@code m} of date {@code d} is the instant {@code start of d in the zone + m
 * minutes} on the time line. On a DST change day this is not the wall-clock time {@code m} (the day is 23 or 25 hours
 * long); the quirk is kept on purpose, in this one place, for slots and metrics alike (tech-debt).
 */
public final class WorkingHours {

    /** One working window of a date, in minutes of day ({@code endMinute} exclusive). */
    public record Window(int startMinute, int endMinute) {
    }

    /** Exception kinds the rule acts on; everything else is ignored. */
    public static final String DAY_OFF = "DAY_OFF";
    public static final String SHIFT = "SHIFT";

    private WorkingHours() {
    }

    /** Working windows of {@code date}: weekly windows of its ISO weekday unless an applicable exception overrides. */
    public static List<Window> windowsFor(LocalDate date, List<WeeklyInterval> weekly, List<ScheduleExceptionView> exceptions) {
        Optional<ScheduleExceptionView> applied = appliedException(date, exceptions);
        if (applied.isPresent()) {
            ScheduleExceptionView e = applied.get();
            return DAY_OFF.equals(e.kind()) ? List.of() : List.of(new Window(e.startMinute(), e.endMinute()));
        }
        int weekday = date.getDayOfWeek().getValue();
        return weekly.stream()
                .filter(w -> w.weekday() == weekday)
                .map(w -> new Window(w.startMinute(), w.endMinute()))
                .toList();
    }

    /**
     * The exception that changes {@code date}'s windows, if any, chosen independently of the order of
     * {@code exceptions} (review M2, mirrored in {@code metrics-replay.sql}): only a {@code DAY_OFF} or a {@code SHIFT}
     * with both minutes set applies; a DAY_OFF wins over shifts; among shifts the earliest start, then the earliest
     * end wins. Other kinds and a SHIFT without minutes never apply and never block one that does.
     */
    public static Optional<ScheduleExceptionView> appliedException(LocalDate date, List<ScheduleExceptionView> exceptions) {
        return exceptions.stream()
                .filter(e -> date.equals(e.date()) && applies(e))
                .min(Comparator.comparing((ScheduleExceptionView e) -> DAY_OFF.equals(e.kind()) ? 0 : 1)
                        .thenComparing(e -> e.startMinute() == null ? Integer.MIN_VALUE : e.startMinute())
                        .thenComparing(e -> e.endMinute() == null ? Integer.MIN_VALUE : e.endMinute()));
    }

    private static boolean applies(ScheduleExceptionView e) {
        return DAY_OFF.equals(e.kind())
                || (SHIFT.equals(e.kind()) && e.startMinute() != null && e.endMinute() != null);
    }

    /** The instant of minute {@code minuteOfDay} of {@code date} in {@code zone} (start of day + minutes, see class doc). */
    public static Instant instantAt(LocalDate date, int minuteOfDay, ZoneId zone) {
        return date.atStartOfDay(zone).plusMinutes(minuteOfDay).toInstant();
    }

    /**
     * Available minutes of one specialist over the dates {@code from}..{@code to} (both inclusive): the sum of the
     * absolute length of every working window ({@link #instantAt} end minus start, never negative).
     */
    public static long availableMinutes(
            ZoneId zone,
            LocalDate from,
            LocalDate to,
            List<WeeklyInterval> weekly,
            List<ScheduleExceptionView> exceptions
    ) {
        long minutes = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            for (Window w : windowsFor(d, weekly, exceptions)) {
                Instant start = instantAt(d, w.startMinute(), zone);
                Instant end = instantAt(d, w.endMinute(), zone);
                if (end.isAfter(start)) {
                    minutes += Duration.between(start, end).toMinutes();
                }
            }
        }
        return minutes;
    }

    /** How many dates of {@code from}..{@code to} had their windows changed by an exception ({@link #appliedException}). */
    public static int appliedExceptionCount(LocalDate from, LocalDate to, List<ScheduleExceptionView> exceptions) {
        int count = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (appliedException(d, exceptions).isPresent()) {
                count++;
            }
        }
        return count;
    }
}

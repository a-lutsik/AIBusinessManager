package com.aibusinessmanager.booking.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure slot calculation — no I/O. Shared by public booking, console, and AI tools.
 */
public final class SlotCalculator {

    public record OccupiedInterval(Instant start, Instant end) {
        public boolean overlaps(OccupiedInterval other) {
            return start.isBefore(other.end) && other.start.isBefore(end);
        }

        public boolean containedIn(OccupiedInterval window) {
            return !start.isBefore(window.start) && !end.isAfter(window.end);
        }
    }

    public record WeeklyWindow(int isoWeekday, int startMinute, int endMinute) {
    }

    public record ExceptionDay(LocalDate date, String kind, Integer startMinute, Integer endMinute) {
    }

    private SlotCalculator() {
    }

    public static List<Instant> freeServiceStarts(
            ZoneId zone,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            List<WeeklyWindow> weekly,
            List<ExceptionDay> exceptions,
            List<OccupiedInterval> occupying,
            int durationMinutes,
            int bufferBeforeMinutes,
            int bufferAfterMinutes,
            int gridStepMinutes,
            Instant now,
            int minNoticeMinutes,
            int horizonDays
    ) {
        Instant minStart = now.plus(minNoticeMinutes, ChronoUnit.MINUTES);
        Instant maxStart = now.plus(horizonDays, ChronoUnit.DAYS);
        List<Instant> result = new ArrayList<>();
        for (LocalDate date = fromInclusive; !date.isAfter(toInclusive); date = date.plusDays(1)) {
            List<WeeklyWindow> windows = windowsFor(date, weekly, exceptions);
            for (WeeklyWindow window : windows) {
                Instant windowStart = local(date, window.startMinute(), zone);
                Instant windowEnd = local(date, window.endMinute(), zone);
                OccupiedInterval work = new OccupiedInterval(windowStart, windowEnd);
                for (int minute = window.startMinute(); minute < window.endMinute(); minute += gridStepMinutes) {
                    Instant serviceStart = local(date, minute, zone);
                    Instant serviceEnd = serviceStart.plus(durationMinutes, ChronoUnit.MINUTES);
                    OccupiedInterval occupied = new OccupiedInterval(
                            serviceStart.minus(bufferBeforeMinutes, ChronoUnit.MINUTES),
                            serviceEnd.plus(bufferAfterMinutes, ChronoUnit.MINUTES)
                    );
                    if (serviceStart.isBefore(minStart) || serviceStart.isAfter(maxStart)) {
                        continue;
                    }
                    if (!occupied.containedIn(work)) {
                        continue;
                    }
                    boolean busy = occupying.stream().anyMatch(occupied::overlaps);
                    if (!busy) {
                        result.add(serviceStart);
                    }
                }
            }
        }
        return result;
    }

    public static OccupiedInterval occupiedFor(Instant serviceStart, int durationMinutes, int bufferBefore, int bufferAfter) {
        Instant serviceEnd = serviceStart.plus(durationMinutes, ChronoUnit.MINUTES);
        return new OccupiedInterval(
                serviceStart.minus(bufferBefore, ChronoUnit.MINUTES),
                serviceEnd.plus(bufferAfter, ChronoUnit.MINUTES)
        );
    }

    static List<WeeklyWindow> windowsFor(LocalDate date, List<WeeklyWindow> weekly, List<ExceptionDay> exceptions) {
        ExceptionDay match = exceptions.stream().filter(e -> e.date().equals(date)).findFirst().orElse(null);
        if (match != null && "DAY_OFF".equals(match.kind())) {
            return List.of();
        }
        if (match != null && "SHIFT".equals(match.kind()) && match.startMinute() != null && match.endMinute() != null) {
            return List.of(new WeeklyWindow(date.getDayOfWeek().getValue(), match.startMinute(), match.endMinute()));
        }
        int weekday = date.getDayOfWeek().getValue();
        return weekly.stream().filter(w -> w.isoWeekday() == weekday).toList();
    }

    static Instant local(LocalDate date, int minuteOfDay, ZoneId zone) {
        return date.atStartOfDay(zone).plusMinutes(minuteOfDay).toInstant();
    }
}

package com.cadence.catalog.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared working-day rule of slots and slot utilization (ADR 0001 §3). */
class WorkingHoursTest {

    private static final UUID MASTER = UUID.randomUUID();
    private static final ZoneId TBILISI = ZoneId.of("Asia/Tbilisi");
    private static final ZoneId PRAGUE = ZoneId.of("Europe/Prague");
    /** 2026-09-07 is a Monday. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);

    private static WeeklyInterval weekly(int weekday, int start, int end) {
        return new WeeklyInterval(UUID.randomUUID(), MASTER, weekday, start, end);
    }

    private static ScheduleExceptionView exception(LocalDate date, String kind, Integer start, Integer end) {
        return new ScheduleExceptionView(UUID.randomUUID(), MASTER, date, kind, start, end, null);
    }

    /** Mon 10:00-13:00 and 14:00-19:00 (480 min), Tue 10:00-19:00 (540 min), nothing else. */
    private static final List<WeeklyInterval> WEEK = List.of(
            weekly(1, 600, 780), weekly(1, 840, 1140), weekly(2, 600, 1140));

    @Test
    void aPlainDayHasTheWeeklyWindowsOfItsWeekday() {
        assertEquals(List.of(new WorkingHours.Window(600, 780), new WorkingHours.Window(840, 1140)),
                WorkingHours.windowsFor(MONDAY, WEEK, List.of()));
        assertEquals(List.of(), WorkingHours.windowsFor(MONDAY.plusDays(2), WEEK, List.of()), "Wednesday: no weekly window");
    }

    @Test
    void dayOffRemovesEveryWindow() {
        var off = List.of(exception(MONDAY, "DAY_OFF", null, null));
        assertEquals(List.of(), WorkingHours.windowsFor(MONDAY, WEEK, off));
        assertEquals(1, WorkingHours.appliedExceptionCount(MONDAY, MONDAY.plusDays(6), off));
    }

    @Test
    void shiftReplacesTheWeeklyWindows() {
        var shift = List.of(exception(MONDAY, "SHIFT", 720, 900));
        assertEquals(List.of(new WorkingHours.Window(720, 900)), WorkingHours.windowsFor(MONDAY, WEEK, shift));
    }

    @Test
    void anyOtherExceptionIsIgnored() {
        var notApplied = List.of(
                exception(MONDAY, "SHIFT", null, 900),
                exception(MONDAY.plusDays(1), "VACATION", null, null));
        assertEquals(WorkingHours.windowsFor(MONDAY, WEEK, List.of()), WorkingHours.windowsFor(MONDAY, WEEK, notApplied));
        assertEquals(List.of(new WorkingHours.Window(600, 1140)),
                WorkingHours.windowsFor(MONDAY.plusDays(1), WEEK, notApplied));
        assertEquals(0, WorkingHours.appliedExceptionCount(MONDAY, MONDAY.plusDays(6), notApplied));
    }

    /**
     * Several exceptions on one date (review M2): the choice does not depend on row order. A DAY_OFF wins; otherwise
     * the SHIFT with the earliest start, then the earliest end; ignored kinds and a SHIFT without minutes never block.
     */
    @Test
    void severalExceptionsOnOneDateAreResolvedTheSameWayInAnyOrder() {
        var shift = exception(MONDAY, "SHIFT", 720, 900);
        var off = exception(MONDAY, "DAY_OFF", null, null);
        var unknown = exception(MONDAY, "VACATION", null, null);
        var broken = exception(MONDAY, "SHIFT", null, 900);
        var earlyLong = exception(MONDAY, "SHIFT", 600, 1000);
        var earlyShort = exception(MONDAY, "SHIFT", 600, 700);

        assertEquals(List.of(), WorkingHours.windowsFor(MONDAY, WEEK, List.of(shift, off)));
        assertEquals(List.of(), WorkingHours.windowsFor(MONDAY, WEEK, List.of(off, shift)));
        assertEquals(List.of(new WorkingHours.Window(720, 900)), WorkingHours.windowsFor(MONDAY, WEEK, List.of(unknown, shift)));
        assertEquals(List.of(new WorkingHours.Window(720, 900)), WorkingHours.windowsFor(MONDAY, WEEK, List.of(broken, shift)));
        for (var order : List.of(List.of(shift, earlyLong, earlyShort), List.of(earlyShort, shift, earlyLong),
                List.of(earlyLong, earlyShort, shift))) {
            assertEquals(List.of(new WorkingHours.Window(600, 700)), WorkingHours.windowsFor(MONDAY, WEEK, order));
        }
        assertEquals(1, WorkingHours.appliedExceptionCount(MONDAY, MONDAY, List.of(unknown, shift, off)));
    }

    @Test
    void anExceptionOfAnotherDateDoesNotLeak() {
        var other = List.of(exception(MONDAY.plusDays(7), "DAY_OFF", null, null));
        assertEquals(2, WorkingHours.windowsFor(MONDAY, WEEK, other).size());
    }

    @Test
    void availableMinutesSumTheWindowsOfEveryDateInclusive() {
        // Mon 480 + Tue 540 + Wed..Sun 0 = 1020 for one week; two weeks = 2040.
        assertEquals(1020, WorkingHours.availableMinutes(TBILISI, MONDAY, MONDAY.plusDays(6), WEEK, List.of()));
        assertEquals(2040, WorkingHours.availableMinutes(TBILISI, MONDAY, MONDAY.plusDays(13), WEEK, List.of()));
        // Second Monday off, first Tuesday on a 12:00-15:00 shift: 480 + 180 + 0 + 540 = 1200.
        var exceptions = List.of(exception(MONDAY.plusDays(7), "DAY_OFF", null, null),
                exception(MONDAY.plusDays(1), "SHIFT", 720, 900));
        assertEquals(1200, WorkingHours.availableMinutes(TBILISI, MONDAY, MONDAY.plusDays(13), WEEK, exceptions));
        assertEquals(2, WorkingHours.appliedExceptionCount(MONDAY, MONDAY.plusDays(13), exceptions));
    }

    @Test
    void anInvertedWindowCountsAsZero() {
        assertEquals(0, WorkingHours.availableMinutes(TBILISI, MONDAY, MONDAY, List.of(weekly(1, 900, 600)), List.of()));
    }

    /**
     * DST (tech-debt): a window minute is "start of day + minutes" on the time line, not the wall clock. On
     * 2026-03-29 Prague jumps from 02:00 to 03:00, so minute 600 is 11:00 local, and the window keeps its 540
     * absolute minutes; slots and metrics share this rule.
     */
    @Test
    void dstDayKeepsTheWindowLengthAndShiftsTheWallClock() {
        LocalDate springForward = LocalDate.of(2026, 3, 29);
        assertEquals(Instant.parse("2026-03-29T09:00:00Z"), WorkingHours.instantAt(springForward, 600, PRAGUE),
                "start of day 23:00Z (UTC+1) + 600 min = 09:00Z = 11:00 local (UTC+2)");
        var sunday = List.of(weekly(7, 600, 1140));
        assertEquals(540, WorkingHours.availableMinutes(PRAGUE, springForward, springForward, sunday, List.of()));

        LocalDate fallBack = LocalDate.of(2026, 10, 25);
        assertEquals(Instant.parse("2026-10-25T08:00:00Z"), WorkingHours.instantAt(fallBack, 600, PRAGUE),
                "start of day 22:00Z (UTC+2) + 600 min = 08:00Z = 09:00 local (UTC+1)");
        assertEquals(540, WorkingHours.availableMinutes(PRAGUE, fallBack, fallBack, sunday, List.of()));
        // A whole-day window is 1440 minutes even on the 23-hour day.
        assertEquals(1440, WorkingHours.availableMinutes(PRAGUE, springForward, springForward, List.of(weekly(7, 0, 1440)), List.of()));
        assertTrue(WorkingHours.instantAt(springForward, 1440, PRAGUE)
                .isAfter(springForward.plusDays(1).atStartOfDay(PRAGUE).toInstant()), "the quirk: it ends past midnight");
    }
}

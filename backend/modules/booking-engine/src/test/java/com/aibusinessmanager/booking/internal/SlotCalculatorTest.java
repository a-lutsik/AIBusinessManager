package com.aibusinessmanager.booking.internal;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotCalculatorTest {

    @Test
    void returnsGridSlotsInsideWorkingHoursAndSkipsOccupied() {
        ZoneId zone = ZoneId.of("Asia/Tbilisi");
        LocalDate day = LocalDate.of(2026, 9, 14); // Monday
        var weekly = List.of(new SlotCalculator.WeeklyWindow(1, 10 * 60, 12 * 60));
        Instant occupiedStart = day.atTime(10, 0).atZone(zone).toInstant();
        var occupying = List.of(new SlotCalculator.OccupiedInterval(
                occupiedStart.minusSeconds(5 * 60),
                occupiedStart.plusSeconds(45 * 60).plusSeconds(10 * 60)
        ));
        Instant now = day.atTime(8, 0).atZone(zone).toInstant();
        List<Instant> slots = SlotCalculator.freeServiceStarts(
                zone, day, day, weekly, List.of(), occupying,
                45, 5, 10, 15, now, 0, 30
        );
        assertFalse(slots.contains(day.atTime(10, 0).atZone(zone).toInstant()));
        assertTrue(slots.stream().allMatch(s -> !s.isBefore(day.atTime(10, 0).atZone(zone).toInstant())));
        assertTrue(slots.size() >= 1);
    }

    @Test
    void dayOffProducesNoSlots() {
        ZoneId zone = ZoneId.of("Asia/Tbilisi");
        LocalDate day = LocalDate.of(2026, 9, 14);
        var weekly = List.of(new SlotCalculator.WeeklyWindow(1, 10 * 60, 18 * 60));
        var exceptions = List.of(new SlotCalculator.ExceptionDay(day, "DAY_OFF", null, null));
        Instant now = day.minusDays(1).atStartOfDay(zone).toInstant();
        List<Instant> slots = SlotCalculator.freeServiceStarts(
                zone, day, day, weekly, exceptions, List.of(),
                30, 0, 0, 15, now, 0, 30
        );
        assertTrue(slots.isEmpty());
    }
}

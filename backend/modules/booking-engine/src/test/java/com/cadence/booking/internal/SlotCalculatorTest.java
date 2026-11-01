package com.cadence.booking.internal;

import org.junit.jupiter.api.Test;

import java.util.stream.Stream;
import java.util.AbstractList;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    /** A range far past the horizon stops at the horizon: every day costs one read of the exceptions. */
    @Test
    void theLoopEndsAtTheHorizonHoweverFarTheRangeRuns() {
        ZoneId zone = ZoneId.of("Asia/Tbilisi");
        LocalDate today = LocalDate.of(2026, 11, 2);
        int[] days = {0};
        List<SlotCalculator.ExceptionDay> counting = new AbstractList<>() {
            @Override
            public SlotCalculator.ExceptionDay get(int index) {
                throw new IndexOutOfBoundsException();
            }

            @Override
            public int size() {
                return 0;
            }

            @Override
            public Stream<SlotCalculator.ExceptionDay> stream() {
                days[0]++;
                return Stream.empty();
            }
        };

        SlotCalculator.freeServiceStarts(zone, today, today.plusDays(100_000), List.of(), counting, List.of(),
                30, 0, 0, 15, today.atStartOfDay(zone).toInstant(), 0, 28);

        assertThat(days[0]).isLessThanOrEqualTo(30);
    }

    /** The day of the horizon's last moment still counts: its slots up to that moment stay, later ones go. */
    @Test
    void aSlotOnTheDayOfTheHorizonStays() {
        ZoneId zone = ZoneId.of("Asia/Tbilisi");
        LocalDate today = LocalDate.of(2026, 11, 2);
        Instant now = today.atTime(9, 0).atZone(zone).toInstant();

        List<Instant> slots = SlotCalculator.freeServiceStarts(zone, today.plusDays(1), today.plusDays(5),
                List.of(new SlotCalculator.WeeklyWindow(today.plusDays(1).getDayOfWeek().getValue(), 8 * 60, 10 * 60)),
                List.of(), List.of(), 30, 0, 0, 30, now, 0, 1);

        assertThat(slots).containsExactly(
                today.plusDays(1).atTime(8, 0).atZone(zone).toInstant(),
                today.plusDays(1).atTime(8, 30).atZone(zone).toInstant(),
                today.plusDays(1).atTime(9, 0).atZone(zone).toInstant());
    }
}

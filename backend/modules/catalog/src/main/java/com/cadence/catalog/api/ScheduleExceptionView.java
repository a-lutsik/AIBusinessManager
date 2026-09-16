package com.cadence.catalog.api;

import java.time.LocalDate;
import java.util.UUID;

public record ScheduleExceptionView(
        UUID id,
        UUID specialistId,
        LocalDate date,
        String kind,
        Integer startMinute,
        Integer endMinute,
        String note
) {
}

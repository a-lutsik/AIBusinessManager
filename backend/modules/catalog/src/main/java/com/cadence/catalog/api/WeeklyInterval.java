package com.cadence.catalog.api;

import java.util.UUID;

public record WeeklyInterval(
        UUID id,
        UUID specialistId,
        int weekday,
        int startMinute,
        int endMinute
) {
}

package com.aibusinessmanager.catalog.api;

import java.util.UUID;

/** Service row for the specialist matrix: defaults + optional overrides + offered flag. */
public record MatrixRowView(
        UUID matrixId,
        UUID specialistId,
        UUID serviceId,
        String serviceName,
        String color,
        boolean offered,
        int defaultDurationMinutes,
        int defaultPriceMinor,
        int defaultBufferBeforeMinutes,
        int defaultBufferAfterMinutes,
        Integer durationMinutesOverride,
        Integer priceMinorOverride,
        Integer bufferBeforeMinutesOverride,
        Integer bufferAfterMinutesOverride
) {
}

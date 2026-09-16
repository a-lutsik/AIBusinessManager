package com.cadence.catalog.api;

import java.util.UUID;

/** Effective duration/price/buffers after master-service overrides. */
public record Offering(
        UUID specialistId,
        UUID serviceId,
        String serviceName,
        boolean offered,
        int durationMinutes,
        int priceMinor,
        int bufferBeforeMinutes,
        int bufferAfterMinutes,
        String color,
        boolean serviceActive
) {
}

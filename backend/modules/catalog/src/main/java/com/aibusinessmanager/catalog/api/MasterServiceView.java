package com.aibusinessmanager.catalog.api;

import java.util.UUID;

public record MasterServiceView(
        UUID id,
        UUID specialistId,
        UUID serviceId,
        boolean offered,
        Integer durationMinutesOverride,
        Integer priceMinorOverride,
        Integer bufferBeforeMinutesOverride,
        Integer bufferAfterMinutesOverride
) {
}

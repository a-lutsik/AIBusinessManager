package com.aibusinessmanager.catalog.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ServiceView(
        UUID id,
        String name,
        String description,
        int durationMinutes,
        int priceMinor,
        int bufferBeforeMinutes,
        int bufferAfterMinutes,
        String color,
        boolean active,
        boolean publicVisible
) {
}

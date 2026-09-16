package com.cadence.growth.api;

import java.time.Instant;
import java.util.UUID;

public record GrowthSignalView(
        UUID id,
        String key,
        String title,
        String evidence,
        String suggestedAction,
        String severity,
        String actionType,
        String actionPayload,
        Instant createdAt,
        Instant dismissedAt
) {
    public GrowthSignalView withDismissed(Instant at) {
        return new GrowthSignalView(
                id, key, title, evidence, suggestedAction, severity, actionType, actionPayload, createdAt, at
        );
    }
}

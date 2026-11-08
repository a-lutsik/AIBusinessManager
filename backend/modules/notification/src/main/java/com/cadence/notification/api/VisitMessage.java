package com.cadence.notification.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A message about one visit. {@code manualFallback} false means the owner already has a task with this text for the
 * visit (the public booking's NEW_BOOKING task), so no second one is created when Telegram does not deliver.
 */
public record VisitMessage(
        UUID appointmentId,
        UUID clientId,
        MessageKind kind,
        String serviceName,
        Instant serviceStart,
        boolean manualFallback
) {
}

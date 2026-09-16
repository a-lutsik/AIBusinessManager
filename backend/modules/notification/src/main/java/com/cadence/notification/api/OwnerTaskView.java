package com.cadence.notification.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OwnerTaskView(
        UUID id,
        String kind,
        String title,
        String body,
        String copyText,
        String link,
        UUID appointmentId,
        Instant createdAt,
        Instant completedAt
) {
}

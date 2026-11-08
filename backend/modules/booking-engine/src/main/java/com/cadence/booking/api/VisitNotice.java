package com.cadence.booking.api;

import java.time.Instant;
import java.util.UUID;

/** What a client message about a visit needs: facts of the visit, no client data. */
public record VisitNotice(
        UUID id,
        UUID clientId,
        AppointmentStatus status,
        Instant serviceStart,
        String serviceName,
        String source,
        Instant createdAt
) {
}

package com.cadence.booking.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A visit as the booking journal says it was at the fold's as-of instant (ADR 0001): the only input of the growth
 * metrics besides the schedule. {@code createdAt} is the time of the visit's CREATED event.
 */
public record VisitFact(
        UUID appointmentId,
        UUID specialistId,
        UUID serviceId,
        UUID clientId,
        AppointmentStatus status,
        Instant serviceStart,
        Instant serviceEnd,
        int priceMinor,
        int discountMinor,
        int durationMinutes,
        String currencyCode,
        String serviceName,
        String source,
        Instant createdAt
) {
}

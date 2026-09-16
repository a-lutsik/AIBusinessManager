package com.cadence.booking.api;

import java.time.Instant;
import java.util.UUID;

public record CreateBookingCommand(
        UUID specialistId,
        UUID serviceId,
        Instant serviceStart,
        UUID clientId,
        String phone,
        String displayName,
        String locale,
        boolean marketingConsent,
        String consentVersion,
        String note,
        String source,
        AppointmentStatus statusOverride,
        boolean skipNoticeAndHorizon
) {
}

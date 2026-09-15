package com.aibusinessmanager.app.web.dto;

import java.time.Instant;
import java.util.UUID;

public record CreatePublicBookingRequest(
        UUID serviceId,
        UUID specialistId,
        Instant serviceStart,
        String phone,
        String name,
        String locale,
        boolean marketingConsent,
        String countryCode
) {
}

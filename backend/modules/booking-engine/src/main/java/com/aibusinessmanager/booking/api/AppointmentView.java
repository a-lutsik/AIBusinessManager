package com.aibusinessmanager.booking.api;

import java.time.Instant;
import java.util.UUID;

public record AppointmentView(
        UUID id,
        UUID specialistId,
        UUID serviceId,
        UUID clientId,
        AppointmentStatus status,
        Instant serviceStart,
        Instant serviceEnd,
        Instant occupiedStart,
        Instant occupiedEnd,
        String serviceNameSnapshot,
        int priceSnapshot,
        int durationSnapshot,
        int bufferBeforeSnapshot,
        int bufferAfterSnapshot,
        String currencyCode,
        int discountAmount,
        Integer amountReceived,
        String enteredName,
        String note,
        String source,
        String clientDisplayName,
        String clientPhone
) {
}

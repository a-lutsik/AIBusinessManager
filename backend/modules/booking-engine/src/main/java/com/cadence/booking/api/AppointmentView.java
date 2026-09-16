package com.cadence.booking.api;

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
        String clientPhone,
        String trustLevel
) {
    public AppointmentView withTrustLevel(String level) {
        return new AppointmentView(
                id, specialistId, serviceId, clientId, status,
                serviceStart, serviceEnd, occupiedStart, occupiedEnd,
                serviceNameSnapshot, priceSnapshot, durationSnapshot,
                bufferBeforeSnapshot, bufferAfterSnapshot, currencyCode,
                discountAmount, amountReceived, enteredName, note, source,
                clientDisplayName, clientPhone, level
        );
    }
}

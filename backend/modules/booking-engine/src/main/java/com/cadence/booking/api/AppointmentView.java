package com.cadence.booking.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A visit. {@code clientHiddenFromMaster} (decision A, stage 03): a MASTER booked it by phone onto a client outside
 * their scope, so until an OWNER confirms it the master sees only the name they typed on it, and it does not put the
 * client into the master's scope. Cleared by the owner's PENDING to CONFIRMED; a cancelled hidden visit stays hidden.
 */
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
        String trustLevel,
        boolean clientHiddenFromMaster
) {
    /**
     * Whether a MASTER's view of this visit carries no live client data: hidden from the master (decision A) or
     * cancelled (a cancelled visit gives no access to the client, TD-19). Such a visit shows the master only the typed
     * name, no client id, stored name, phone, trust or packages. Not a JSON property.
     */
    public boolean clientWithheldFromMaster() {
        return clientHiddenFromMaster
                || status == AppointmentStatus.CANCELLED_BY_CLIENT
                || status == AppointmentStatus.CANCELLED_BY_BUSINESS;
    }

    public AppointmentView withTrustLevel(String level) {
        return new AppointmentView(
                id, specialistId, serviceId, clientId, status,
                serviceStart, serviceEnd, occupiedStart, occupiedEnd,
                serviceNameSnapshot, priceSnapshot, durationSnapshot,
                bufferBeforeSnapshot, bufferAfterSnapshot, currencyCode,
                discountAmount, amountReceived, enteredName, note, source,
                clientDisplayName, clientPhone, level, clientHiddenFromMaster
        );
    }
}

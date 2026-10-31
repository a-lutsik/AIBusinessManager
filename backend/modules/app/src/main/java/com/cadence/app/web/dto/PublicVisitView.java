package com.cadence.app.web.dto;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;

import java.time.Instant;
import java.util.UUID;

/**
 * What a public booking link shows (finding 4, stage 03): the visit's own facts and the name the visitor typed. No
 * stored client name, phone or trust, so the link reveals nothing stored about whoever owns the phone number. It is
 * not a perfect existence oracle guard: when the tenant requires confirmation of new clients, {@code status}
 * (PENDING vs CONFIRMED, from the client's trust) still tells a returning client from a new one. That is accepted
 * (auto-confirming returning clients is a feature) and bounded by the public booking rate limits; see TD-19.
 * Built as an allow-list: a field added to {@link AppointmentView} later does not reach the public page by itself.
 * {@code specialistId} and {@code serviceId} let the page offer reschedule slots; {@code clientRescheduleAllowed} is
 * the tenant's rule, so the page does not offer slots the reschedule route would refuse; {@code timezone} is the
 * tenant's zone, in which the page shows times.
 */
public record PublicVisitView(
        UUID id,
        UUID specialistId,
        UUID serviceId,
        AppointmentStatus status,
        Instant serviceStart,
        Instant serviceEnd,
        String serviceNameSnapshot,
        int priceSnapshot,
        String currencyCode,
        String enteredName,
        boolean clientRescheduleAllowed,
        String timezone
) {
    public static PublicVisitView of(AppointmentView visit, boolean clientRescheduleAllowed, String timezone) {
        return new PublicVisitView(
                visit.id(),
                visit.specialistId(),
                visit.serviceId(),
                visit.status(),
                visit.serviceStart(),
                visit.serviceEnd(),
                visit.serviceNameSnapshot(),
                visit.priceSnapshot(),
                visit.currencyCode(),
                visit.enteredName(),
                clientRescheduleAllowed,
                timezone
        );
    }
}

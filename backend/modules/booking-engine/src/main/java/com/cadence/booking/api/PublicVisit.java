package com.cadence.booking.api;

/**
 * The visit behind a manage link with the tenant's reschedule rule, time zone and slug, all read in the transaction of the
 * token method, so a failure after a change rolls the change back instead of answering 500 for an applied operation.
 */
public record PublicVisit(
        AppointmentView visit,
        boolean clientRescheduleAllowed,
        String timezone,
        String tenantSlug
) {
}

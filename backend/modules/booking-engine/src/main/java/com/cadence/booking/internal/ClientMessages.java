package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentStatus;

/**
 * Client-facing message texts stored on owner tasks. The manage link is never part of the stored text:
 * the console substitutes {@link #MANAGE_LINK_PLACEHOLDER} with a link issued on demand (access-link endpoint),
 * so the database holds only the SHA-256 hash of booking tokens.
 */
final class ClientMessages {

    static final String MANAGE_LINK_PLACEHOLDER = "{manageLink}";

    private ClientMessages() {
    }

    /** Text for the owner to send to the client after a public booking; ends with the placeholder. */
    static String bookingConfirmation(AppointmentStatus status) {
        String lead = status == AppointmentStatus.PENDING
                ? "Your visit is waiting for confirmation. Manage it here: "
                : "Your visit is confirmed. Manage it here: ";
        return lead + MANAGE_LINK_PLACEHOLDER;
    }
}

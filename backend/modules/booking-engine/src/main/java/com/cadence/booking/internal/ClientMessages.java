package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.platform.i18n.MessageCatalog;

/**
 * Client-facing message texts stored on owner tasks, written in the client's preferred locale
 * (catalog keys {@code client.booking.*}). The manage link is never part of the stored text:
 * the console substitutes {@link #MANAGE_LINK_PLACEHOLDER} with a link issued on demand (access-link endpoint),
 * so the database holds only the SHA-256 hash of booking tokens.
 */
final class ClientMessages {

    static final String MANAGE_LINK_PLACEHOLDER = "{manageLink}";

    private final MessageCatalog catalog;

    ClientMessages(MessageCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Text for the owner to send to the client after a public booking, in {@code clientLocale} (unknown or absent:
     * English); the placeholder is passed to the template as its argument, so a translation may place it anywhere.
     */
    String bookingConfirmation(AppointmentStatus status, String clientLocale) {
        String key = status == AppointmentStatus.PENDING ? "client.booking.pending" : "client.booking.confirmed";
        return catalog.forClient(clientLocale, key, MANAGE_LINK_PLACEHOLDER);
    }
}

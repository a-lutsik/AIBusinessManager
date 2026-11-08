package com.cadence.notification.api;

import java.util.UUID;

/** Binding a client to a Telegram chat through a one-time deep link. */
public interface TelegramLinkService {

    /** Whether the Telegram channel is configured and switched on. */
    boolean enabled();

    /**
     * Mints a one-time link for the client of the current tenant scope; older unused links of the client stay valid until
     * they expire. Does not check that the client has no chat yet: callers decide that.
     *
     * @throws com.cadence.platform.error.DomainException {@code TELEGRAM_DISABLED} (409), {@code CLIENT_NOT_FOUND} (404),
     *     {@code CLIENT_ANONYMIZED} (409)
     */
    TelegramLink issue(UUID clientId);
}

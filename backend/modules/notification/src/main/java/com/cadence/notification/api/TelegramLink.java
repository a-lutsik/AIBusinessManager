package com.cadence.notification.api;

import java.time.Instant;

/** A deep link {@code https://t.me/<bot>?start=<token>} and when it stops working. The token is shown once and never stored. */
public record TelegramLink(String url, Instant expiresAt) {
}

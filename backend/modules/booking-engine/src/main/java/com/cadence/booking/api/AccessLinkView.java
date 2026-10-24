package com.cadence.booking.api;

import java.time.Instant;

/** One-time view of a freshly issued client manage link; the raw token inside {@code managePath} is never stored. */
public record AccessLinkView(String managePath, Instant expiresAt) {
}

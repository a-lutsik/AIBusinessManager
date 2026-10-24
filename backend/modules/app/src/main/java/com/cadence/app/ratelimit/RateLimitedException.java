package com.cadence.app.ratelimit;

import com.cadence.platform.error.DomainException;

/** A public-API request refused by a rate limit: 429 {@code RATE_LIMITED} with a {@code Retry-After} header. */
public class RateLimitedException extends DomainException {

    private final long retryAfterSeconds;

    public RateLimitedException(String message, long retryAfterSeconds) {
        super("RATE_LIMITED", 429, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

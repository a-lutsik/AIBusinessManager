package com.cadence.app.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Limits of the public (unauthenticated) API, {@code app.rate-limit.*}. Defaults are the numbers of the product plan
 * (section 11). State is held in memory of one application instance, see {@link SlidingWindowRateLimiter}.
 *
 * @param slotsPerMinutePerIp       slot calculation requests per client IP per minute
 * @param bookingsPerDayPerPhone    booking attempts per normalized (E.164) phone per 24 hours
 * @param bookingsPerHourPerTenant  booking attempts per tenant (booking page slug) per hour
 * @param bookingsPerHourPerIp      booking attempts per client IP per hour; the only throttle for attempts whose
 *                                  phone cannot be parsed (those are not counted per phone or per tenant)
 * @param maxTrackedKeys            upper bound of tracked counters; further new keys are refused until old ones expire
 * @param trustedProxies            CIDRs (or single addresses) of reverse proxies whose {@code X-Forwarded-For} is
 *                                  honoured; empty (default) means the header is ignored
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("5") int slotsPerMinutePerIp,
        @DefaultValue("3") int bookingsPerDayPerPhone,
        @DefaultValue("20") int bookingsPerHourPerTenant,
        @DefaultValue("20") int bookingsPerHourPerIp,
        @DefaultValue("100000") int maxTrackedKeys,
        List<String> trustedProxies
) {

    public RateLimitProperties {
        require(slotsPerMinutePerIp, "slots-per-minute-per-ip");
        require(bookingsPerDayPerPhone, "bookings-per-day-per-phone");
        require(bookingsPerHourPerTenant, "bookings-per-hour-per-tenant");
        require(bookingsPerHourPerIp, "bookings-per-hour-per-ip");
        require(maxTrackedKeys, "max-tracked-keys");
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
    }

    public static RateLimitProperties defaults() {
        return new RateLimitProperties(5, 3, 20, 20, 100_000, List.of());
    }

    private static void require(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException("app.rate-limit." + name + " must be at least 1, got " + value);
        }
    }
}

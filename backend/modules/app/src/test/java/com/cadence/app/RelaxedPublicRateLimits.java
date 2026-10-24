package com.cadence.app;

import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * For ITs that drive the public API for something other than rate limiting: every test request comes from the same
 * MockMvc peer address ({@code 127.0.0.1}) and the same demo phones, so the production limits (5 slot reads a minute
 * per IP, 3 bookings a day per phone, ...) would fail them. {@code RateLimitIT} runs with the real defaults.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@TestPropertySource(properties = {
        "app.rate-limit.slots-per-minute-per-ip=1000000",
        "app.rate-limit.bookings-per-day-per-phone=1000000",
        "app.rate-limit.bookings-per-hour-per-tenant=1000000",
        "app.rate-limit.bookings-per-hour-per-ip=1000000"
})
@interface RelaxedPublicRateLimits {
}

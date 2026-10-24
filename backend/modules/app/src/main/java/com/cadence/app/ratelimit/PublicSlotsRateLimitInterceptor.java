package com.cadence.app.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-IP limit of the public slot calculation. Registered by {@link RateLimitWebConfig} for the decoded MVC path
 * {@code /api/public/tenants/*}{@code /slots}, so it applies to exactly the requests the controller serves (not to a
 * URI that merely contains {@code /slots}) and runs before argument binding and validation.
 */
@Component
public class PublicSlotsRateLimitInterceptor implements HandlerInterceptor {

    private final PublicRateLimiter limiter;
    private final ClientIpResolver clientIps;

    public PublicSlotsRateLimitInterceptor(PublicRateLimiter limiter, ClientIpResolver clientIps) {
        this.limiter = limiter;
        this.clientIps = clientIps;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        limiter.checkSlots(clientIps.resolve(request));
        return true;
    }
}

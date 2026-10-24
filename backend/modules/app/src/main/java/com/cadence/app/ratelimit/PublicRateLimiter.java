package com.cadence.app.ratelimit;

import com.cadence.app.ratelimit.SlidingWindowRateLimiter.Decision;
import com.cadence.app.ratelimit.SlidingWindowRateLimiter.Limit;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.jooq.tables.records.TenantRecord;
import com.cadence.platform.phone.PhoneNormalizer;
import com.cadence.platform.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Rate limits of the public API (product plan, section 11):
 * <ul>
 *   <li>slot calculation: {@code slots-per-minute-per-ip} per client IP per minute (default 5);</li>
 *   <li>booking attempts: {@code bookings-per-day-per-phone} per normalized E.164 phone per 24 hours (default 3) and
 *       {@code bookings-per-hour-per-tenant} per tenant per hour (default 20);</li>
 *   <li>booking attempts per client IP per hour ({@code bookings-per-hour-per-ip}, default 20) as the throttle for
 *       attempts that carry no parsable phone: those are deliberately not counted per phone or per tenant, so without
 *       an IP limit they would be unlimited.</li>
 * </ul>
 * An attempt counts whether or not the booking later succeeds. A refused request consumes nothing.
 *
 * <p>Every rejection answers 429 {@code RATE_LIMITED} (see {@link RateLimitedException}) and, when the tenant is known,
 * is written to the audit log as {@code public.rate_limited}: at most once a minute per exceeded counter, so a flood
 * cannot flood the audit table. The record holds the limit, a masked IP (last IPv4 octet / IPv6 interface id
 * dropped) and a masked phone, never the raw values. An audit failure is logged and never changes the 429.
 */
@Service
public class PublicRateLimiter {

    public static final String AUDIT_ACTION = "public.rate_limited";

    private static final Logger log = LoggerFactory.getLogger(PublicRateLimiter.class);
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DAY = Duration.ofDays(1);

    private final RateLimitProperties props;
    private final SlidingWindowRateLimiter limiter;
    private final PhoneNormalizer phones;
    private final AuditLogger audit;

    public PublicRateLimiter(
            RateLimitProperties props,
            SlidingWindowRateLimiter limiter,
            PhoneNormalizer phones,
            AuditLogger audit
    ) {
        this.props = props;
        this.limiter = limiter;
        this.phones = phones;
        this.audit = audit;
    }

    /** Counts one slot calculation request of {@code clientIp}; throws {@link RateLimitedException} when over the limit. */
    public void checkSlots(String clientIp) {
        Limit limit = new Limit("slots_per_ip", "slots:ip:" + clientIp, props.slotsPerMinutePerIp(), MINUTE);
        Decision decision = limiter.tryAcquire(List.of(limit));
        if (!decision.allowed()) {
            reject(decision, clientIp, null,
                    "Too many availability requests from your network. Please wait a minute and try again.");
        }
    }

    /**
     * Counts one public booking attempt. An unparsable phone is not counted per phone or per tenant and is left to the
     * regular validation of the booking service.
     */
    public void checkBooking(TenantRecord tenant, String clientIp, String rawPhone) {
        String e164 = normalize(rawPhone, tenant.getCountryCode());
        List<Limit> limits = new ArrayList<>();
        limits.add(new Limit("bookings_per_ip_hour", "book:ip:" + clientIp, props.bookingsPerHourPerIp(), HOUR));
        if (e164 != null) {
            limits.add(new Limit("bookings_per_phone_day", "book:phone:" + tenant.getId() + ":" + e164,
                    props.bookingsPerDayPerPhone(), DAY));
            limits.add(new Limit("bookings_per_tenant_hour", "book:tenant:" + tenant.getSlug(),
                    props.bookingsPerHourPerTenant(), HOUR));
        }
        Decision decision = limiter.tryAcquire(limits);
        if (!decision.allowed()) {
            reject(decision, clientIp, e164, messageFor(decision));
        }
    }

    private String normalize(String rawPhone, String countryCode) {
        try {
            return phones.toE164(rawPhone, countryCode);
        } catch (DomainException ex) {
            return null;
        }
    }

    private String messageFor(Decision decision) {
        if (decision.capacityExhausted()) {
            return "The service is busy right now. Please try again in a minute.";
        }
        return switch (decision.violated().name()) {
            case "bookings_per_phone_day" -> "This phone number has reached the limit of "
                    + props.bookingsPerDayPerPhone() + " booking requests per day. Please try again tomorrow "
                    + "or contact the studio.";
            case "bookings_per_tenant_hour" ->
                    "This booking page is receiving a lot of requests right now. Please try again in a few minutes.";
            default -> "Too many booking requests from your network. Please try again later.";
        };
    }

    private void reject(Decision decision, String clientIp, String e164, String message) {
        if (decision.auditDue()) {
            recordAudit(decision.violated(), clientIp, e164);
        }
        throw new RateLimitedException(message, decision.retryAfterSeconds());
    }

    private void recordAudit(Limit limit, String clientIp, String e164) {
        UUID tenantId = TenantContext.current().orElse(null);
        if (tenantId == null) {
            log.warn("Rate limit {} exceeded by {} for an unknown tenant; not auditable (no tenant scope)",
                    limit.name(), IpAddresses.mask(clientIp));
            return;
        }
        try {
            StringBuilder details = new StringBuilder()
                    .append("{\"limit\":\"").append(limit.name())
                    .append("\",\"max\":").append(limit.max())
                    .append(",\"windowSeconds\":").append(limit.window().toSeconds())
                    .append(",\"ip\":\"").append(IpAddresses.mask(clientIp)).append('"');
            if (e164 != null) {
                details.append(",\"phone\":\"").append(maskPhone(e164)).append('"');
            }
            details.append('}');
            audit.record("public", "PUBLIC", AUDIT_ACTION, "tenant", tenantId, null, details.toString(), "public-api", null);
        } catch (RuntimeException ex) {
            log.error("Could not write the {} audit record for limit {}; the request is still answered 429",
                    AUDIT_ACTION, limit.name(), ex);
        }
    }

    /** {@code +995555123456} becomes {@code +995*******56}: the leading country part and the last two digits only. */
    static String maskPhone(String e164) {
        if (e164.length() <= 6) {
            return "***";
        }
        return e164.substring(0, 4) + "*".repeat(e164.length() - 6) + e164.substring(e164.length() - 2);
    }
}

package com.cadence.platform.tenant;

import java.time.ZoneId;
import java.util.UUID;

/**
 * Tenant facts for modules that must not see the jOOQ {@code TenantRecord} (the {@code ai} module, ADR 0002 §9).
 * {@code timezone} is an IANA zone id, {@code currencyCode} ISO 4217, {@code countryCode} ISO 3166-1 alpha-2.
 */
public record TenantInfo(
        UUID id,
        String slug,
        String displayName,
        String timezone,
        String currencyCode,
        String countryCode
) {

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }
}

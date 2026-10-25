package com.cadence.retention.api;

import java.util.List;
import java.util.UUID;

/**
 * Trust of one client. {@code reasons} are readable texts for the OWNER and may carry tenant-wide counters
 * ("2 no-shows recorded"); {@code reasonCodes} say the same without numbers (decision B, stage 03: {@code NEW_CLIENT},
 * {@code NO_SHOWS}, {@code LATE_CANCELLATIONS}, {@code REGULAR_CLIENT}, {@code RETURNING_CLIENT},
 * {@code OWNER_OVERRIDE}) and are what a MASTER gets. {@code overrideReason} is the owner's free text.
 */
public record TrustView(
        UUID clientId,
        String level,
        List<String> reasons,
        String overrideLevel,
        String overrideReason,
        List<String> reasonCodes
) {
    public boolean isNew() {
        return "NEW".equals(level);
    }

    public boolean isLow() {
        return "LOW".equals(effectiveLevel());
    }

    public String effectiveLevel() {
        return overrideLevel != null ? overrideLevel : level;
    }
}

package com.cadence.crm.api;

import java.time.Instant;
import java.util.UUID;

/** {@code normalizedPhone} is null once the client is anonymized or merged away: the stored placeholder is not a phone. */
public record ClientView(
        UUID id,
        String normalizedPhone,
        String displayName,
        String notes,
        String tags,
        String preferredLocale,
        String telegramChatId,
        boolean marketingConsent,
        Instant firstVisitAt,
        Instant lastVisitAt,
        int completedVisitCount,
        int noShowCount,
        int lateCancelCount,
        int completedValueMinor
) {
    /** Anonymized or merged away. Not a JSON property. */
    public boolean retired() {
        return normalizedPhone == null;
    }
}

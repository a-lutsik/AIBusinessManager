package com.cadence.crm.api;

import java.time.Instant;
import java.util.UUID;

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
}

package com.aibusinessmanager.retention.api;

import java.util.List;
import java.util.UUID;

public record TrustView(
        UUID clientId,
        String level,
        List<String> reasons,
        String overrideLevel,
        String overrideReason
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

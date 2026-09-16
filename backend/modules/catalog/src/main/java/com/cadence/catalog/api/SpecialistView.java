package com.cadence.catalog.api;

import java.util.UUID;

public record SpecialistView(
        UUID id,
        String displayName,
        String keycloakUserId,
        String calendarColor,
        boolean active
) {
}

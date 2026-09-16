package com.cadence.booking.api;

import java.util.Set;

public enum AppointmentStatus {
    PENDING,
    CONFIRMED,
    COMPLETED,
    NO_SHOW,
    CANCELLED_BY_CLIENT,
    CANCELLED_BY_BUSINESS;

    public static final Set<AppointmentStatus> OCCUPYING_STATUSES = Set.of(
            PENDING, CONFIRMED, COMPLETED, NO_SHOW
    );

    public boolean occupies() {
        return OCCUPYING_STATUSES.contains(this);
    }
}

package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.platform.error.DomainException;

import java.util.Set;

public final class AppointmentStatusMachine {

    private AppointmentStatusMachine() {
    }

    public static void assertTransition(AppointmentStatus from, AppointmentStatus to) {
        if (!allowed(from, to)) {
            throw DomainException.conflict("INVALID_TRANSITION", "Cannot change status from " + from + " to " + to);
        }
    }

    public static boolean allowed(AppointmentStatus from, AppointmentStatus to) {
        if (from == to) {
            return false;
        }
        return switch (from) {
            case PENDING -> Set.of(
                    AppointmentStatus.CONFIRMED,
                    AppointmentStatus.CANCELLED_BY_CLIENT,
                    AppointmentStatus.CANCELLED_BY_BUSINESS
            ).contains(to);
            case CONFIRMED -> Set.of(
                    AppointmentStatus.COMPLETED,
                    AppointmentStatus.NO_SHOW,
                    AppointmentStatus.CANCELLED_BY_CLIENT,
                    AppointmentStatus.CANCELLED_BY_BUSINESS
            ).contains(to);
            case NO_SHOW -> to == AppointmentStatus.COMPLETED;
            case COMPLETED -> to == AppointmentStatus.NO_SHOW;
            case CANCELLED_BY_CLIENT, CANCELLED_BY_BUSINESS -> false;
        };
    }
}

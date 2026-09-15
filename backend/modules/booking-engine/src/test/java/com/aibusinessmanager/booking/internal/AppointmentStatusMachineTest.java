package com.aibusinessmanager.booking.internal;

import com.aibusinessmanager.booking.api.AppointmentStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppointmentStatusMachineTest {

    @Test
    void pendingAndConfirmedTransitions() {
        assertTrue(AppointmentStatusMachine.allowed(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED));
        assertTrue(AppointmentStatusMachine.allowed(AppointmentStatus.CONFIRMED, AppointmentStatus.COMPLETED));
        assertTrue(AppointmentStatusMachine.allowed(AppointmentStatus.CONFIRMED, AppointmentStatus.NO_SHOW));
        assertTrue(AppointmentStatusMachine.allowed(AppointmentStatus.NO_SHOW, AppointmentStatus.COMPLETED));
        assertFalse(AppointmentStatusMachine.allowed(AppointmentStatus.CANCELLED_BY_CLIENT, AppointmentStatus.CONFIRMED));
        assertFalse(AppointmentStatusMachine.allowed(AppointmentStatus.PENDING, AppointmentStatus.COMPLETED));
        assertThrows(RuntimeException.class, () -> AppointmentStatusMachine.assertTransition(
                AppointmentStatus.COMPLETED, AppointmentStatus.PENDING
        ));
    }

    @Test
    void occupyingStatusesMatchConstraint() {
        assertTrue(AppointmentStatus.PENDING.occupies());
        assertTrue(AppointmentStatus.CONFIRMED.occupies());
        assertTrue(AppointmentStatus.COMPLETED.occupies());
        assertTrue(AppointmentStatus.NO_SHOW.occupies());
        assertFalse(AppointmentStatus.CANCELLED_BY_CLIENT.occupies());
        assertFalse(AppointmentStatus.CANCELLED_BY_BUSINESS.occupies());
    }
}

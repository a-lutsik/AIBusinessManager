package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentEventType;
import com.cadence.booking.api.AppointmentStatus;
import org.mockito.ArgumentMatchers;

/** Mockito matchers for {@link AppointmentRepository#record}, the single write path of visit facts. */
final class JournalRecords {

    private JournalRecords() {
    }

    static AppointmentEventType created() {
        return ArgumentMatchers.eq(AppointmentEventType.CREATED);
    }

    /** A status change that expects {@code from} as the stored status (the optimistic guard of the update). */
    static JournalPayloads.Payload statusFrom(AppointmentStatus from) {
        return ArgumentMatchers.argThat(p -> p instanceof JournalPayloads.StatusChange s && s.from() == from);
    }

    /** Any status change (CONFIRMED, COMPLETED, NO_SHOW, CANCELLED). */
    static JournalPayloads.Payload anyStatusChange() {
        return ArgumentMatchers.argThat(p -> p instanceof JournalPayloads.StatusChange);
    }
}

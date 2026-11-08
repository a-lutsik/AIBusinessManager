package com.cadence.app.outbox;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.BookingService;
import com.cadence.booking.api.VisitNotice;
import com.cadence.notification.api.MessageKind;
import com.cadence.notification.api.VisitGuard;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Re-reads the visit (under a share lock, see {@link BookingService#visitNotice}) right before a message goes out: it must
 * still start when the message says and not be in the past (a past visit gets no message and no owner task), and reminders and the confirmation need it CONFIRMED; the "waiting for confirmation"
 * message only needs it not cancelled.
 */
@Component
class BookingVisitGuard implements VisitGuard {

    private final BookingService bookings;
    private final Clock clock;

    BookingVisitGuard(BookingService bookings, Clock clock) {
        this.bookings = bookings;
        this.clock = clock;
    }

    @Override
    public boolean stillDeliverable(UUID appointmentId, MessageKind kind, Instant serviceStart) {
        return bookings.visitNotice(appointmentId)
                .filter(v -> v.serviceStart().equals(serviceStart) && serviceStart.isAfter(clock.instant()))
                .map(VisitNotice::status)
                .map(status -> kind == MessageKind.CONFIRMATION_PENDING
                        ? status == AppointmentStatus.PENDING || status == AppointmentStatus.CONFIRMED
                        : status == AppointmentStatus.CONFIRMED)
                .orElse(false);
    }
}

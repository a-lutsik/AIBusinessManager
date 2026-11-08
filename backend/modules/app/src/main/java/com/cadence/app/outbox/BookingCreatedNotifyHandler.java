package com.cadence.app.outbox;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.BookingService;
import com.cadence.booking.api.VisitNotice;
import com.cadence.notification.api.ClientMessageService;
import com.cadence.notification.api.MessageKind;
import com.cadence.notification.api.VisitMessage;
import com.cadence.platform.outbox.OutboxHandler;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.OUTBOX_EVENT;

/**
 * Confirms a new visit to the client: Telegram when linked, otherwise a manual owner task. A public booking already
 * gave the owner a NEW_BOOKING task with the same text (and the manage link), so it gets no second task here.
 */
@Component
public class BookingCreatedNotifyHandler implements OutboxHandler {

    private static final Logger log = LoggerFactory.getLogger(BookingCreatedNotifyHandler.class);
    private static final String PUBLIC_SOURCE = "PUBLIC";

    private final BookingService bookings;
    private final ClientMessageService clientMessages;

    public BookingCreatedNotifyHandler(BookingService bookings, ClientMessageService clientMessages) {
        this.bookings = bookings;
        this.clientMessages = clientMessages;
    }

    @Override
    public String type() {
        return "BOOKING_CREATED";
    }

    @Override
    public void handle(Record event) {
        UUID appointmentId = event.get(OUTBOX_EVENT.AGGREGATE_ID);
        Optional<VisitNotice> found = bookings.visitNotice(appointmentId);
        if (found.isEmpty() || found.get().status() == AppointmentStatus.CANCELLED_BY_CLIENT
                || found.get().status() == AppointmentStatus.CANCELLED_BY_BUSINESS) {
            log.info("Booking confirmation skipped, visit is gone or cancelled: visit={}", appointmentId);
            return;
        }
        VisitNotice visit = found.get();
        MessageKind kind = visit.status() == AppointmentStatus.PENDING ? MessageKind.CONFIRMATION_PENDING : MessageKind.CONFIRMATION;
        clientMessages.send(new VisitMessage(
                visit.id(), visit.clientId(), kind, visit.serviceName(), visit.serviceStart(), !PUBLIC_SOURCE.equals(visit.source())));
    }
}

package com.cadence.app.outbox;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.BookingService;
import com.cadence.booking.api.VisitNotice;
import com.cadence.notification.api.ClientMessageService;
import com.cadence.notification.api.MessageKind;
import com.cadence.notification.api.VisitMessage;
import com.cadence.platform.jooq.tables.records.OutboxEventRecord;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.OUTBOX_EVENT;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BookingCreatedNotifyHandlerTest {

    private static final UUID VISIT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final Instant START = Instant.parse("2030-01-10T10:00:00Z");

    private final BookingService bookings = mock(BookingService.class);
    private final ClientMessageService clientMessages = mock(ClientMessageService.class);
    private final BookingCreatedNotifyHandler handler = new BookingCreatedNotifyHandler(bookings, clientMessages);
    private OutboxEventRecord event;

    @BeforeEach
    void event() {
        event = DSL.using(SQLDialect.POSTGRES).newRecord(OUTBOX_EVENT);
        event.setAggregateId(VISIT);
    }

    private void visit(AppointmentStatus status, String source) {
        when(bookings.visitNotice(VISIT)).thenReturn(Optional.of(
                new VisitNotice(VISIT, CLIENT, status, START, "Haircut", source, Instant.parse("2030-01-01T00:00:00Z"))));
    }

    @Test
    void aConfirmedPublicVisitQueuesTheConfirmationWithoutAManualFallback() {
        visit(AppointmentStatus.CONFIRMED, "PUBLIC");

        handler.handle(event);

        verify(clientMessages).enqueue(new VisitMessage(VISIT, CLIENT, MessageKind.CONFIRMATION, "Haircut", START, false));
    }

    @Test
    void aPendingVisitQueuesThePendingConfirmation() {
        visit(AppointmentStatus.PENDING, "PUBLIC");

        handler.handle(event);

        verify(clientMessages).enqueue(new VisitMessage(VISIT, CLIENT, MessageKind.CONFIRMATION_PENDING, "Haircut", START, false));
    }

    @Test
    void aConsoleBookingFallsBackToAManualTask() {
        visit(AppointmentStatus.CONFIRMED, "CONSOLE");

        handler.handle(event);

        verify(clientMessages).enqueue(new VisitMessage(VISIT, CLIENT, MessageKind.CONFIRMATION, "Haircut", START, true));
    }

    @Test
    void aMissingVisitQueuesNothing() {
        when(bookings.visitNotice(VISIT)).thenReturn(Optional.empty());

        handler.handle(event);

        verifyNoInteractions(clientMessages);
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"CANCELLED_BY_CLIENT", "CANCELLED_BY_BUSINESS"})
    void aCancelledVisitQueuesNothing(AppointmentStatus cancelled) {
        visit(cancelled, "PUBLIC");

        handler.handle(event);

        verifyNoInteractions(clientMessages);
    }
}

package com.cadence.ai.internal.drafts;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.BookingService;
import com.cadence.booking.api.CreateBookingCommand;
import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.platform.ai.AiDraftStore;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.tenancy.ActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR 0002 §4.3, with a transaction manager that records commits and rollbacks in order. */
class DraftApplierTest {

    private static final Instant NOW = Instant.parse("2026-11-02T08:00:00Z");
    private static final Instant START = Instant.parse("2026-11-03T06:00:00Z");
    private static final Instant NEW_START = Instant.parse("2026-11-04T06:00:00Z");
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID APPLIED = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();

    private final BookingService booking = mock(BookingService.class);
    private final CrmService crm = mock(CrmService.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final AiDraftStore store = mock(AiDraftStore.class);
    private final List<String> events = new ArrayList<>();
    private final PlatformTransactionManager manager = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            events.add("begin");
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            events.add("commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            events.add("rollback");
        }
    };
    private final DraftApplier applier = new DraftApplier(store, booking, crm, audit, new TransactionTemplate(manager),
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void owner() {
        ActorContext.set(new ActorContext.Actor("owner-1", ActorContext.Role.OWNER, null));
        when(crm.findActiveById(any())).thenReturn(Optional.of(mock(ClientView.class)));
        when(store.markConfirmed(any(), any())).thenReturn(true);
        when(store.markFailed(any(), any(), any())).thenAnswer(i -> {
            events.add("markFailed:" + i.getArgument(1));
            return true;
        });
        when(store.markExpired(any())).thenAnswer(i -> {
            events.add("markExpired");
            return true;
        });
    }

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    private static AppointmentView visit(AppointmentStatus status, Instant start) {
        return new AppointmentView(VISIT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), status, start,
                start.plusSeconds(2700), start, start.plusSeconds(2700), "Haircut", 8000, 45, 0, 0, "GEL", 0, null,
                null, null, "console", "Maria", "+995", "HIGH", false);
    }

    private static DraftPayloads.Payload bookingPayload(UUID specialist, UUID service, UUID client) {
        return new DraftPayloads.Booking(1, DraftPayloads.BOOKING, client, specialist, service, START.toString(),
                new DraftPayloads.BookingSummary("Maria", "Nino", "Haircut", 45, 8000, "GEL", "2026-11-03T10:00"));
    }

    private static final DraftPayloads.Expected EXPECTED = new DraftPayloads.Expected(START.toString(), AppointmentStatus.CONFIRMED);

    private static DraftPayloads.Payload reschedulePayload() {
        return new DraftPayloads.Reschedule(1, DraftPayloads.RESCHEDULE, CLIENT, VISIT, NEW_START.toString(), EXPECTED,
                new DraftPayloads.RescheduleSummary("Maria", "Nino", "Haircut", "a", "b"));
    }

    private static DraftPayloads.Payload cancelPayload() {
        return new DraftPayloads.Cancel(1, DraftPayloads.CANCEL, CLIENT, VISIT, AppointmentStatus.CANCELLED_BY_BUSINESS, "closed", EXPECTED,
                new DraftPayloads.CancelSummary("Maria", "Nino", "Haircut", "a"));
    }

    private AiDraftStore.DraftRecord draft(DraftPayloads.Payload payload, AiDraftStore.Status status, Instant expiresAt) {
        return new AiDraftStore.DraftRecord(id, CONVERSATION, UUID.randomUUID(), payload.type(), DraftPayloads.write(payload),
                status, NOW.minusSeconds(60), expiresAt, null, null, null, null, null);
    }

    private void stored(DraftPayloads.Payload payload) {
        AiDraftStore.DraftRecord record = draft(payload, AiDraftStore.Status.PENDING, NOW.plus(Duration.ofHours(1)));
        when(store.requireForUpdate(id)).thenReturn(record);
        when(store.require(id)).thenReturn(draft(payload, AiDraftStore.Status.CONFIRMED, record.expiresAt()));
    }

    private void assertAuditedConfirm(AiDraftStore.DraftRecord record) {
        verify(audit).record("owner-1", "OWNER", "ai.draft.confirm", "ai_draft", id, DraftPayloads.forAudit(record.payload()),
                "{\"status\":\"CONFIRMED\",\"appliedEntityId\":\"" + APPLIED + "\"}", "ai-draft", id.toString());
    }

    // --- success, all three types -------------------------------------------------------------------------------

    @Test
    void aBookingIsCreatedThroughTheEngineUnderItsRules() {
        UUID specialist = UUID.randomUUID();
        UUID service = UUID.randomUUID();
        UUID client = UUID.randomUUID();
        DraftPayloads.Payload payload = bookingPayload(specialist, service, client);
        stored(payload);
        when(booking.create(any())).thenReturn(visitWithId());

        applier.confirm(id);

        ArgumentCaptor<CreateBookingCommand> command = ArgumentCaptor.forClass(CreateBookingCommand.class);
        verify(booking).create(command.capture());
        assertEquals(specialist, command.getValue().specialistId());
        assertEquals(service, command.getValue().serviceId());
        assertEquals(client, command.getValue().clientId());
        assertEquals(START, command.getValue().serviceStart());
        assertEquals("AI", command.getValue().source());
        assertNull(command.getValue().statusOverride());
        assertEquals(false, command.getValue().skipNoticeAndHorizon());
        verify(store).markConfirmed(id, APPLIED);
        assertAuditedConfirm(draft(payload, AiDraftStore.Status.PENDING, null));
        assertEquals(List.of("begin", "commit"), events);
    }

    @Test
    void aRescheduleGoesToTheEngineWhenTheVisitIsUnchanged() {
        DraftPayloads.Payload payload = reschedulePayload();
        stored(payload);
        when(booking.find(VISIT)).thenReturn(Optional.of(visit(AppointmentStatus.CONFIRMED, START)));
        when(booking.reschedule(VISIT, NEW_START)).thenReturn(visitWithId());

        applier.confirm(id);

        verify(store).markConfirmed(id, APPLIED);
        assertAuditedConfirm(draft(payload, AiDraftStore.Status.PENDING, null));
    }

    @Test
    void aCancelGoesToTheEngineAndItsReasonStaysOffTheVisitNote() {
        DraftPayloads.Payload payload = cancelPayload();
        stored(payload);
        when(booking.find(VISIT)).thenReturn(Optional.of(visit(AppointmentStatus.CONFIRMED, START)));
        when(booking.transition(VISIT, AppointmentStatus.CANCELLED_BY_BUSINESS, null)).thenReturn(visitWithId());

        applier.confirm(id);

        verify(store).markConfirmed(id, APPLIED);
        assertAuditedConfirm(draft(payload, AiDraftStore.Status.PENDING, null));
    }

    private static AppointmentView visitWithId() {
        return new AppointmentView(APPLIED, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), AppointmentStatus.CONFIRMED,
                START, START, START, START, "Haircut", 0, 0, 0, 0, "GEL", 0, null, null, null, "AI", "x", "y", "z", false);
    }

    // --- refusals -----------------------------------------------------------------------------------------------

    @Test
    void anotherStatusIsNotPendingAndRecordsNothing() {
        when(store.requireForUpdate(id)).thenReturn(draft(cancelPayload(), AiDraftStore.Status.CONFIRMED, NOW.plusSeconds(60)));

        DomainException e = assertThrows(DomainException.class, () -> applier.confirm(id));

        assertEquals("DRAFT_NOT_PENDING", e.code());
        assertEquals(409, e.status());
        verify(store, never()).markFailed(any(), any(), any());
        verify(booking, never()).transition(any(), any(), any());
    }

    @Test
    void aMissingDraftIs404AndRecordsNothing() {
        when(store.requireForUpdate(id)).thenThrow(DomainException.notFound("DRAFT_NOT_FOUND", "no"));

        assertEquals("DRAFT_NOT_FOUND", assertThrows(DomainException.class, () -> applier.confirm(id)).code());
        assertEquals(List.of("begin", "rollback"), events);
    }

    @Test
    void anExpiredDraftBecomesExpiredInATransactionAfterTheRollback() {
        when(store.requireForUpdate(id)).thenReturn(draft(reschedulePayload(), AiDraftStore.Status.PENDING, NOW.minusSeconds(1)));

        DomainException e = assertThrows(DomainException.class, () -> applier.confirm(id));

        assertEquals("DRAFT_EXPIRED", e.code());
        assertEquals(409, e.status());
        assertEquals(List.of("begin", "rollback", "begin", "markExpired", "commit"), events);
        verify(booking, never()).reschedule(any(), any());
        verify(store, never()).markFailed(any(), any(), any());
    }

    @Test
    void aVisitMovedSinceTheDraftIsStaleAndFailed() {
        stored(reschedulePayload());
        when(booking.find(VISIT)).thenReturn(Optional.of(visit(AppointmentStatus.CONFIRMED, START.plusSeconds(3600))));

        DomainException e = assertThrows(DomainException.class, () -> applier.confirm(id));

        assertEquals("DRAFT_STALE", e.code());
        assertEquals(List.of("begin", "rollback", "begin", "markFailed:DRAFT_STALE", "commit"), events);
        verify(booking, never()).reschedule(any(), any());
    }

    @Test
    void aVisitWhoseStatusChangedOrThatIsGoneIsStaleToo() {
        stored(cancelPayload());
        when(booking.find(VISIT)).thenReturn(Optional.of(visit(AppointmentStatus.PENDING, START)));
        assertEquals("DRAFT_STALE", assertThrows(DomainException.class, () -> applier.confirm(id)).code());

        when(booking.find(VISIT)).thenReturn(Optional.empty());
        assertEquals("DRAFT_STALE", assertThrows(DomainException.class, () -> applier.confirm(id)).code());
        verify(booking, never()).transition(any(), any(), any());
    }

    @Test
    void anEngineRefusalKeepsItsCodeAndFailsTheDraftAfterTheRollback() {
        stored(bookingPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        DomainException refusal = DomainException.conflict("SLOT_OCCUPIED", "Slot is not available");
        when(booking.create(any())).thenThrow(refusal);

        DomainException e = assertThrows(DomainException.class, () -> applier.confirm(id));

        assertEquals(refusal, e);
        assertEquals(List.of("begin", "rollback", "begin", "markFailed:SLOT_OCCUPIED", "commit"), events);
        verify(store).markFailed(id, "SLOT_OCCUPIED", "Slot is not available");
        verify(store, never()).markConfirmed(any(), any());
        verify(audit).record("owner-1", "OWNER", "ai.draft.fail", "ai_draft", id, null,
                "{\"status\":\"FAILED\",\"failureCode\":\"SLOT_OCCUPIED\"}", "ai-draft", id.toString());
    }

    @Test
    void anEngineRefusalInRescheduleAndCancelFailsTheDraftWithItsCode() {
        stored(reschedulePayload());
        when(booking.find(VISIT)).thenReturn(Optional.of(visit(AppointmentStatus.CONFIRMED, START)));
        when(booking.reschedule(VISIT, NEW_START)).thenThrow(DomainException.conflict("SLOT_OCCUPIED", "no"));
        assertEquals("SLOT_OCCUPIED", assertThrows(DomainException.class, () -> applier.confirm(id)).code());

        stored(cancelPayload());
        when(booking.transition(any(), any(), any())).thenThrow(DomainException.conflict("INVALID_TRANSITION", "no"));
        assertEquals("INVALID_TRANSITION", assertThrows(DomainException.class, () -> applier.confirm(id)).code());
        verify(store).markFailed(id, "INVALID_TRANSITION", "no");
    }

    @Test
    void anUnexpectedExceptionLeavesTheDraftPendingAndPropagates() {
        stored(bookingPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        IllegalStateException failure = new IllegalStateException("db down");
        when(booking.create(any())).thenThrow(failure);

        assertEquals(failure, assertThrows(IllegalStateException.class, () -> applier.confirm(id)));

        assertEquals(List.of("begin", "rollback"), events);
        verify(store, never()).markFailed(any(), any(), any());
    }

    @Test
    void anUnreadablePayloadFailsTheDraft() {
        when(store.requireForUpdate(id)).thenReturn(new AiDraftStore.DraftRecord(id, CONVERSATION, null, "draft_booking", "{\"v\":99}",
                AiDraftStore.Status.PENDING, NOW, NOW.plusSeconds(60), null, null, null, null, null));

        assertEquals("DRAFT_INVALID", assertThrows(DomainException.class, () -> applier.confirm(id)).code());
        assertTrue(events.contains("markFailed:DRAFT_INVALID"));
    }

    @Test
    void losingTheConditionalTransitionRollsBack() {
        stored(bookingPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        when(booking.create(any())).thenReturn(visitWithId());
        when(store.markConfirmed(any(), any())).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> applier.confirm(id));

        assertEquals(List.of("begin", "rollback"), events);
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aFailingFailureRecordDoesNotHideTheRefusal() {
        stored(bookingPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        when(booking.create(any())).thenThrow(DomainException.conflict("SLOT_OCCUPIED", "no"));
        when(store.markFailed(any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        assertEquals("SLOT_OCCUPIED", assertThrows(DomainException.class, () -> applier.confirm(id)).code());
    }

    @Test
    void aRetiredClientMakesEveryDraftStaleAndNothingIsApplied() {
        for (DraftPayloads.Payload payload : List.of(bookingPayload(UUID.randomUUID(), UUID.randomUUID(), CLIENT),
                reschedulePayload(), cancelPayload())) {
            stored(payload);
            when(crm.findActiveById(CLIENT)).thenReturn(Optional.empty());

            assertEquals("DRAFT_STALE", assertThrows(DomainException.class, () -> applier.confirm(id)).code());
        }

        verify(store, times(3)).markFailed(eq(id), eq("DRAFT_STALE"), any());
        verify(store, times(3)).replacePayload(eq(id), argThat(json -> !json.contains("Maria") && !json.contains("closed")));
        verify(booking, never()).create(any());
        verify(booking, never()).reschedule(any(), any());
        verify(booking, never()).transition(any(), any(), any());
    }

    @Test
    void aStaleVisitOfAnActiveClientKeepsTheCard() {
        stored(reschedulePayload());
        when(booking.find(VISIT)).thenReturn(Optional.empty());

        assertEquals("DRAFT_STALE", assertThrows(DomainException.class, () -> applier.confirm(id)).code());

        verify(store, never()).replacePayload(any(), any());
    }

    @Test
    void theAuditOfAConfirmHoldsNoNameOrReason() {
        stored(cancelPayload());
        when(booking.find(VISIT)).thenReturn(Optional.of(visit(AppointmentStatus.CONFIRMED, START)));
        when(booking.transition(any(), any(), any())).thenReturn(visitWithId());

        applier.confirm(id);

        verify(audit).record(any(), any(), eq("ai.draft.confirm"), any(), any(),
                argThat(before -> !before.contains("Maria") && !before.contains("closed")), any(), any(), any());
    }
}

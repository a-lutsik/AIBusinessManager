package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.catalog.api.CatalogService;
import com.cadence.crm.api.CrmService;
import com.cadence.notification.api.OwnerTaskService;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.outbox.OutboxPublisher;
import com.cadence.platform.phone.PhoneNormalizer;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.cadence.retention.api.RetentionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Owner corrections NO_SHOW to COMPLETED and COMPLETED to NO_SHOW must keep the client aggregates consistent. The
 * collaborators are mocked; the SQL behind {@code completedRangeExcluding} and the CRM revert methods is covered by
 * the integration test.
 */
class BookingStatusCorrectionTest {

    private static final UUID APPOINTMENT = UUID.randomUUID();
    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID SPECIALIST = UUID.randomUUID();
    private static final Instant VISIT_START = Instant.parse("2026-09-01T10:00:00Z");

    private CrmService crm;
    private AppointmentRepository appointments;
    private AuditLogger audit;
    private BookingServiceImpl service;

    @BeforeEach
    void setUp() {
        crm = mock(CrmService.class);
        appointments = mock(AppointmentRepository.class);
        audit = mock(AuditLogger.class);
        service = new BookingServiceImpl(
                mock(CatalogService.class),
                crm,
                mock(RetentionService.class),
                appointments,
                mock(TenantDirectory.class),
                mock(OutboxPublisher.class),
                audit,
                mock(OwnerTaskService.class),
                mock(PhoneNormalizer.class),
                Clock.systemUTC(),
                TestMessages.catalog()
        );
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private static void actAsOwner() {
        ActorContext.set(new ActorContext.Actor("owner-1", ActorContext.Role.OWNER, null));
    }

    private static void actAsMaster() {
        ActorContext.set(new ActorContext.Actor("master-1", ActorContext.Role.MASTER, SPECIALIST));
    }

    /** Price 8000, discount 500: the visit is worth 7500. */
    private void visitWithStatus(AppointmentStatus status) {
        AppointmentView view = new AppointmentView(
                APPOINTMENT, SPECIALIST, UUID.randomUUID(), CLIENT, status,
                VISIT_START, VISIT_START.plusSeconds(2700), VISIT_START, VISIT_START.plusSeconds(3300),
                "Haircut", 8000, 45, 5, 10, "GEL", 500, null, "Mariam", null, "CONSOLE", "Mariam", null, null, false
        );
        when(appointments.findForUpdate(APPOINTMENT)).thenReturn(Optional.of(view));
    }

    @Test
    void completedToNoShowRevertsTheCompletedVisitThenCountsTheNoShow() {
        actAsOwner();
        visitWithStatus(AppointmentStatus.COMPLETED);
        Instant first = Instant.parse("2026-05-01T09:00:00Z");
        Instant last = Instant.parse("2026-08-01T09:00:00Z");
        when(appointments.completedRangeExcluding(CLIENT, APPOINTMENT))
                .thenReturn(new AppointmentRepository.CompletedRange(first, last));

        service.transition(APPOINTMENT, AppointmentStatus.NO_SHOW, null);

        InOrder order = inOrder(crm);
        order.verify(crm).revertCompletedVisit(CLIENT, 7500, first, last);
        order.verify(crm).recordNoShow(CLIENT);
        verify(crm, never()).recordCompletedVisit(any(), any(), anyInt());
        verify(crm, never()).revertNoShow(any());
    }

    @Test
    void completedToNoShowPassesNullTimestampsWhenNoOtherCompletedVisitRemains() {
        actAsOwner();
        visitWithStatus(AppointmentStatus.COMPLETED);
        when(appointments.completedRangeExcluding(CLIENT, APPOINTMENT))
                .thenReturn(new AppointmentRepository.CompletedRange(null, null));

        service.transition(APPOINTMENT, AppointmentStatus.NO_SHOW, null);

        verify(crm).revertCompletedVisit(CLIENT, 7500, null, null);
        verify(crm).recordNoShow(CLIENT);
    }

    @Test
    void noShowToCompletedRevertsTheNoShowThenRecordsTheCompletedVisit() {
        actAsOwner();
        visitWithStatus(AppointmentStatus.NO_SHOW);

        service.transition(APPOINTMENT, AppointmentStatus.COMPLETED, null);

        InOrder order = inOrder(crm);
        order.verify(crm).revertNoShow(CLIENT);
        order.verify(crm).recordCompletedVisit(CLIENT, VISIT_START, 7500);
        verify(crm, never()).revertCompletedVisit(any(), anyInt(), any(), any());
        verify(crm, never()).recordNoShow(any());
        verify(appointments, never()).completedRangeExcluding(any(), any());
    }

    @Test
    void correctionsAreAuditedAsStatusCorrectionWithFromAndTo() {
        actAsOwner();
        visitWithStatus(AppointmentStatus.NO_SHOW);

        service.transition(APPOINTMENT, AppointmentStatus.COMPLETED, null);

        ArgumentCaptor<String> before = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(audit).record(
                eq("owner-1"), eq("OWNER"), eq("appointment.status_correction"), eq("appointment"), eq(APPOINTMENT),
                before.capture(), after.capture(), anyString(), any());
        assertThat(before.getValue()).contains("NO_SHOW");
        assertThat(after.getValue()).contains("COMPLETED");
        verify(audit, never()).record(any(), any(), eq("appointment.transition"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void masterCannotCorrectEitherDirection() {
        actAsMaster();
        for (AppointmentStatus from : new AppointmentStatus[]{AppointmentStatus.COMPLETED, AppointmentStatus.NO_SHOW}) {
            AppointmentStatus to = from == AppointmentStatus.COMPLETED ? AppointmentStatus.NO_SHOW : AppointmentStatus.COMPLETED;
            visitWithStatus(from);

            assertThatThrownBy(() -> service.transition(APPOINTMENT, to, null))
                    .isInstanceOfSatisfying(DomainException.class, ex -> {
                        assertThat(ex.status()).isEqualTo(403);
                        assertThat(ex.code()).isEqualTo("FORBIDDEN_RESOURCE");
                    });
        }
        verifyNoInteractions(crm, audit);
        verify(appointments, never()).record(any(), any(), JournalRecords.anyStatusChange());
    }

    @Test
    void callWithoutActorCannotCorrect() {
        visitWithStatus(AppointmentStatus.COMPLETED);

        assertThatThrownBy(() -> service.transition(APPOINTMENT, AppointmentStatus.NO_SHOW, null))
                .isInstanceOfSatisfying(DomainException.class, ex -> assertThat(ex.status()).isEqualTo(403));
        verifyNoInteractions(crm);
    }

    @Test
    void ordinaryTransitionsStayOpenToMastersAndKeepTheTransitionAudit() {
        actAsMaster();
        visitWithStatus(AppointmentStatus.CONFIRMED);

        service.transition(APPOINTMENT, AppointmentStatus.COMPLETED, null);

        verify(crm).recordCompletedVisit(CLIENT, VISIT_START, 7500);
        verify(crm, never()).revertNoShow(any());
        verify(crm, never()).revertCompletedVisit(any(), anyInt(), any(), any());
        verify(audit).record(
                eq("master-1"), eq("MASTER"), eq("appointment.transition"), eq("appointment"), eq(APPOINTMENT),
                any(), any(), anyString(), any());
    }

    @Test
    void transitionLocksTheVisitRowAndGuardsTheUpdateWithTheStatusItRead() {
        actAsOwner();
        visitWithStatus(AppointmentStatus.COMPLETED);
        when(appointments.completedRangeExcluding(CLIENT, APPOINTMENT))
                .thenReturn(new AppointmentRepository.CompletedRange(null, null));

        service.transition(APPOINTMENT, AppointmentStatus.NO_SHOW, null);

        InOrder order = inOrder(appointments);
        order.verify(appointments).findForUpdate(APPOINTMENT);
        order.verify(appointments).record(any(), any(), JournalRecords.statusFrom(AppointmentStatus.COMPLETED));
        verify(appointments, never()).find(any());
    }

    @Test
    void loserOfAConcurrentStatusChangeGets409AndNeverTouchesTheAggregates() {
        actAsOwner();
        visitWithStatus(AppointmentStatus.COMPLETED);
        doThrow(DomainException.conflict("CONCURRENT_MODIFICATION", "changed"))
                .when(appointments).record(any(), any(), JournalRecords.statusFrom(AppointmentStatus.COMPLETED));

        assertThatThrownBy(() -> service.transition(APPOINTMENT, AppointmentStatus.NO_SHOW, null))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.code()).isEqualTo("CONCURRENT_MODIFICATION");
                });
        verifyNoInteractions(crm, audit);
    }

    @Test
    void reassignClientDelegatesToTheRepository() {
        UUID to = UUID.randomUUID();
        when(appointments.reassignClient(CLIENT, to)).thenReturn(4);

        assertThat(service.reassignClient(CLIENT, to)).isEqualTo(4);
    }
}

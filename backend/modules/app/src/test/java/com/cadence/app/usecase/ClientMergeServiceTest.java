package com.cadence.app.usecase;

import com.cadence.booking.api.BookingService;
import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.notification.api.OwnerTaskService;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.retention.api.RetentionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClientMergeServiceTest {

    private static final UUID SURVIVOR = UUID.randomUUID();
    private static final UUID ABSORBED = UUID.randomUUID();
    private static final String PHONE = "+995555123456";

    private CrmService crm;
    private BookingService booking;
    private RetentionService retention;
    private OwnerTaskService ownerTasks;
    private AuditLogger audit;
    private ClientMergeService service;

    @BeforeEach
    void setUp() {
        crm = mock(CrmService.class);
        booking = mock(BookingService.class);
        retention = mock(RetentionService.class);
        ownerTasks = mock(OwnerTaskService.class);
        audit = mock(AuditLogger.class);
        service = new ClientMergeService(crm, booking, retention, ownerTasks, audit);
        ActorContext.set(new ActorContext.Actor("owner-1", ActorContext.Role.OWNER, null));
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private static ClientView view(UUID id, String name, int completed, int noShow, int lateCancel, int value) {
        return new ClientView(id, PHONE, name, null, null, "en", null, false,
                Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-08-01T10:00:00Z"),
                completed, noShow, lateCancel, value);
    }

    private ClientView stubHappyPath() {
        ClientView before = view(SURVIVOR, "Mariam Beridze", 3, 1, 0, 24000);
        ClientView after = view(SURVIVOR, "Mariam Beridze", 7, 1, 1, 54000);
        when(crm.findById(SURVIVOR)).thenReturn(Optional.of(before));
        when(crm.findById(ABSORBED)).thenReturn(Optional.of(view(ABSORBED, "Mariam K.", 4, 0, 1, 30000)));
        when(booking.reassignClient(ABSORBED, SURVIVOR)).thenReturn(5);
        when(retention.reassignClient(ABSORBED, SURVIVOR)).thenReturn(2);
        when(ownerTasks.reassignClient(ABSORBED, SURVIVOR)).thenReturn(3);
        when(crm.absorb(SURVIVOR, ABSORBED)).thenReturn(after);
        return after;
    }

    @Test
    void movesBookingThenRetentionThenTasksThenAbsorbsAndAudits() {
        ClientView after = stubHappyPath();

        ClientView result = service.merge(SURVIVOR, ABSORBED);

        assertThat(result).isSameAs(after);
        InOrder order = inOrder(booking, retention, ownerTasks, crm, audit);
        order.verify(crm).lockForMerge(SURVIVOR, ABSORBED);
        order.verify(booking).reassignClient(ABSORBED, SURVIVOR);
        order.verify(retention).reassignClient(ABSORBED, SURVIVOR);
        order.verify(ownerTasks).reassignClient(ABSORBED, SURVIVOR);
        order.verify(crm).absorb(SURVIVOR, ABSORBED);
        order.verify(audit).record(any(), any(), eq("client.merge"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void auditNamesSurvivorAsEntityAndCarriesMovedCountsButNoPhoneOrName() {
        stubHappyPath();

        service.merge(SURVIVOR, ABSORBED);

        ArgumentCaptor<String> before = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(audit).record(
                eq("owner-1"), eq("OWNER"), eq("client.merge"), eq("client"), eq(SURVIVOR),
                before.capture(), after.capture(), anyString(), any());
        assertThat(before.getValue()).contains(ABSORBED.toString()).contains("\"completedVisitCount\":3");
        assertThat(after.getValue()).contains(ABSORBED.toString())
                .contains("\"completedVisitCount\":7")
                .contains("\"completedValueMinor\":54000")
                .contains("\"appointments\":5")
                .contains("\"packages\":2")
                .contains("\"tasks\":3");
        for (String json : new String[]{before.getValue(), after.getValue()}) {
            assertThat(json).doesNotContain(PHONE).doesNotContain("995555").doesNotContain("Mariam");
        }
    }

    @Test
    void masterCannotMerge() {
        ActorContext.set(new ActorContext.Actor("master-1", ActorContext.Role.MASTER, UUID.randomUUID()));

        assertThatThrownBy(() -> service.merge(SURVIVOR, ABSORBED))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(403);
                    assertThat(ex.code()).isEqualTo("FORBIDDEN_RESOURCE");
                });
        verifyNoInteractions(crm, booking, retention, ownerTasks, audit);
    }

    @Test
    void callWithoutActorCannotMerge() {
        ActorContext.clear();

        assertThatThrownBy(() -> service.merge(SURVIVOR, ABSORBED))
                .isInstanceOfSatisfying(DomainException.class, ex -> assertThat(ex.status()).isEqualTo(403));
        verifyNoInteractions(crm, booking, retention, ownerTasks, audit);
    }

    @Test
    void selfMergeIsRejectedBeforeAnythingMoves() {
        assertThatThrownBy(() -> service.merge(SURVIVOR, SURVIVOR))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(400);
                    assertThat(ex.code()).isEqualTo("INVALID_MERGE");
                });
        verifyNoInteractions(crm, booking, retention, ownerTasks, audit);
    }

    @Test
    void unknownClientIs404AndNothingMoves() {
        when(crm.findById(SURVIVOR)).thenReturn(Optional.of(view(SURVIVOR, "A", 0, 0, 0, 0)));
        when(crm.findById(ABSORBED)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.merge(SURVIVOR, ABSORBED))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(404);
                    assertThat(ex.code()).isEqualTo("CLIENT_NOT_FOUND");
                });
        assertThatThrownBy(() -> service.merge(ABSORBED, SURVIVOR))
                .isInstanceOfSatisfying(DomainException.class, ex -> assertThat(ex.code()).isEqualTo("CLIENT_NOT_FOUND"));
        verifyNoInteractions(booking, retention, ownerTasks, audit);
        verify(crm, never()).absorb(any(), any());
    }

    @Test
    void alreadyMergedPairIsRejectedByTheLockStepBeforeAnythingMoves() {
        org.mockito.Mockito.doThrow(DomainException.conflict("CLIENT_ALREADY_MERGED", "already merged"))
                .when(crm).lockForMerge(SURVIVOR, ABSORBED);

        assertThatThrownBy(() -> service.merge(SURVIVOR, ABSORBED))
                .isInstanceOfSatisfying(DomainException.class, ex -> assertThat(ex.status()).isEqualTo(409));
        verifyNoInteractions(booking, retention, ownerTasks, audit);
        verify(crm, never()).absorb(any(), any());
    }

    @Test
    void crmRejectionPropagatesWithoutAuditSoTheTransactionRollsBack() {
        when(crm.findById(SURVIVOR)).thenReturn(Optional.of(view(SURVIVOR, "A", 0, 0, 0, 0)));
        when(crm.findById(ABSORBED)).thenReturn(Optional.of(view(ABSORBED, "B", 0, 0, 0, 0)));
        when(crm.absorb(SURVIVOR, ABSORBED))
                .thenThrow(DomainException.conflict("CLIENT_ALREADY_MERGED", "Client is already anonymized or merged"));

        assertThatThrownBy(() -> service.merge(SURVIVOR, ABSORBED))
                .isInstanceOfSatisfying(DomainException.class, ex -> assertThat(ex.status()).isEqualTo(409));
        verifyNoInteractions(audit);
    }
}

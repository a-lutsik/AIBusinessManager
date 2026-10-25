package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.CreateBookingCommand;
import com.cadence.catalog.api.BookingRulesView;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.Offering;
import com.cadence.crm.api.CrmService;
import com.cadence.notification.api.OwnerTaskService;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.jooq.tables.records.TenantRecord;
import com.cadence.platform.outbox.OutboxPublisher;
import com.cadence.platform.phone.PhoneNormalizer;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.cadence.retention.api.RetentionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TD-17: no role settles a visit (COMPLETED or NO_SHOW) before its {@code serviceStart}, judged by the injected
 * clock; the refusal is 409 {@code VISIT_NOT_STARTED} and touches neither the visit nor the client aggregates. Visits
 * that already started settle as before, and the owner's NO_SHOW/COMPLETED corrections on them stay open.
 */
class VisitOutcomeTimingTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SPECIALIST = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID CLIENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");

    private CrmService crm;
    private AppointmentRepository appointments;
    private AuditLogger audit;
    private BookingServiceImpl service;
    private TenantContext.Scope tenantScope;

    @BeforeEach
    void setUp() {
        CatalogService catalog = mock(CatalogService.class);
        crm = mock(CrmService.class);
        appointments = mock(AppointmentRepository.class);
        audit = mock(AuditLogger.class);
        TenantDirectory tenants = mock(TenantDirectory.class);
        service = new BookingServiceImpl(
                catalog,
                crm,
                mock(RetentionService.class),
                appointments,
                tenants,
                mock(OutboxPublisher.class),
                audit,
                mock(OwnerTaskService.class),
                mock(PhoneNormalizer.class),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        TenantRecord tenant = new TenantRecord();
        tenant.setId(TENANT);
        tenant.setTimezone("Asia/Tbilisi");
        tenant.setCurrencyCode("GEL");
        tenant.setCountryCode("GE");
        when(tenants.requireById(TENANT)).thenReturn(tenant);
        when(catalog.offering(SPECIALIST, SERVICE)).thenReturn(Optional.of(
                new Offering(SPECIALIST, SERVICE, "Haircut", true, 60, 8000, 0, 0, "#000000", true)));
        when(catalog.rules()).thenReturn(new BookingRulesView(15, 60, 28, true, 24, true, false, 1, true));
        when(appointments.occupying(eq(SPECIALIST), any(), any(), any())).thenReturn(List.of());
        when(appointments.completedRangeExcluding(any(), any()))
                .thenReturn(new AppointmentRepository.CompletedRange(null, null));
        tenantScope = TenantContext.open(TENANT);
    }

    @AfterEach
    void tearDown() {
        tenantScope.close();
        ActorContext.clear();
    }

    private static void actAs(ActorContext.Role role) {
        ActorContext.set(new ActorContext.Actor(role.name().toLowerCase(), role,
                role == ActorContext.Role.MASTER ? SPECIALIST : null));
    }

    private AppointmentView stored(AppointmentStatus status, Instant start) {
        AppointmentView view = new AppointmentView(UUID.randomUUID(), SPECIALIST, SERVICE, CLIENT, status, start,
                start.plusSeconds(3600), start, start.plusSeconds(3600), "Haircut", 8000, 60, 0, 0, "GEL", 0, null,
                "Mariam", null, "CONSOLE", "Mariam", null, null, false);
        when(appointments.findForUpdate(view.id())).thenReturn(Optional.of(view));
        return view;
    }

    private void assertNotStarted(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.code()).isEqualTo("VISIT_NOT_STARTED");
                });
        verifyNoInteractions(crm, audit);
        verify(appointments, never()).updateStatusAndTimes(any(), any());
        verify(appointments, never()).insertEvent(any(), any(), any());
        verify(appointments, never()).insert(any());
    }

    @ParameterizedTest
    @EnumSource(value = ActorContext.Role.class, names = {"OWNER", "MASTER", "SYSTEM"})
    void noRoleCompletesAFutureVisit(ActorContext.Role role) {
        actAs(role);
        AppointmentView future = stored(AppointmentStatus.CONFIRMED, NOW.plusSeconds(60));

        assertNotStarted(() -> service.transition(future.id(), AppointmentStatus.COMPLETED, null));
    }

    @ParameterizedTest
    @EnumSource(value = ActorContext.Role.class, names = {"OWNER", "MASTER", "SYSTEM"})
    void noRoleMarksAFutureVisitAsNoShow(ActorContext.Role role) {
        actAs(role);
        AppointmentView future = stored(AppointmentStatus.CONFIRMED, NOW.plusSeconds(60));

        assertNotStarted(() -> service.transition(future.id(), AppointmentStatus.NO_SHOW, null));
    }

    @Test
    void callWithoutActorCannotSettleAFutureVisit() {
        AppointmentView future = stored(AppointmentStatus.CONFIRMED, NOW.plusSeconds(60));

        assertNotStarted(() -> service.transition(future.id(), AppointmentStatus.COMPLETED, null));
    }

    @ParameterizedTest
    @EnumSource(value = ActorContext.Role.class, names = {"OWNER", "MASTER"})
    void aVisitThatStartsNowOrStartedEarlierSettles(ActorContext.Role role) {
        actAs(role);
        AppointmentView startsNow = stored(AppointmentStatus.CONFIRMED, NOW);
        AppointmentView started = stored(AppointmentStatus.CONFIRMED, NOW.minusSeconds(1800));

        assertThat(service.transition(startsNow.id(), AppointmentStatus.COMPLETED, null).status())
                .isEqualTo(AppointmentStatus.COMPLETED);
        assertThat(service.transition(started.id(), AppointmentStatus.NO_SHOW, null).status())
                .isEqualTo(AppointmentStatus.NO_SHOW);
        verify(crm).recordCompletedVisit(CLIENT, NOW, 8000);
        verify(crm).recordNoShow(CLIENT);
    }

    @Test
    void ownerCorrectionsOnAStartedVisitStayOpen() {
        actAs(ActorContext.Role.OWNER);
        AppointmentView noShow = stored(AppointmentStatus.NO_SHOW, NOW.minusSeconds(3600));
        AppointmentView completed = stored(AppointmentStatus.COMPLETED, NOW.minusSeconds(7200));

        assertThat(service.transition(noShow.id(), AppointmentStatus.COMPLETED, null).status())
                .isEqualTo(AppointmentStatus.COMPLETED);
        assertThat(service.transition(completed.id(), AppointmentStatus.NO_SHOW, null).status())
                .isEqualTo(AppointmentStatus.NO_SHOW);
        verify(crm).revertNoShow(CLIENT);
        verify(crm).revertCompletedVisit(CLIENT, 8000, null, null);
    }

    @Test
    void aLegacyVisitSettledBeforeItStartedGetsAnExplicitRefusalForTheOwnersCorrection() {
        // Rows settled before TD-17 can be COMPLETED or NO_SHOW with a future start. The rule stays (nobody settles
        // before the start, a correction included), but the owner learns why the correction is refused.
        actAs(ActorContext.Role.OWNER);
        AppointmentView legacyCompleted = stored(AppointmentStatus.COMPLETED, NOW.plusSeconds(3600));
        AppointmentView legacyNoShow = stored(AppointmentStatus.NO_SHOW, NOW.plusSeconds(3600));

        for (Runnable call : List.<Runnable>of(
                () -> service.transition(legacyCompleted.id(), AppointmentStatus.NO_SHOW, null),
                () -> service.transition(legacyNoShow.id(), AppointmentStatus.COMPLETED, null))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class, ex -> {
                assertThat(ex.status()).isEqualTo(409);
                assertThat(ex.code()).isEqualTo("VISIT_NOT_STARTED");
                assertThat(ex.getMessage()).contains("settled before it started").contains("after it starts");
            });
        }
        verifyNoInteractions(crm, audit);
        verify(appointments, never()).updateStatusAndTimes(any(), any());
    }

    @Test
    void cancellingAFutureVisitIsUnaffected() {
        actAs(ActorContext.Role.OWNER);
        AppointmentView future = stored(AppointmentStatus.CONFIRMED, NOW.plusSeconds(3600 * 24 * 7));

        assertThat(service.transition(future.id(), AppointmentStatus.CANCELLED_BY_BUSINESS, null).status())
                .isEqualTo(AppointmentStatus.CANCELLED_BY_BUSINESS);
    }

    private static CreateBookingCommand settledCreate(AppointmentStatus status, Instant start) {
        return new CreateBookingCommand(SPECIALIST, SERVICE, start, CLIENT, null, "Mariam", "en", false, null,
                null, "CONSOLE", status, true);
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"COMPLETED", "NO_SHOW"})
    void ownerAndSystemCannotCreateAFutureVisitAlreadySettled(AppointmentStatus settled) {
        for (ActorContext.Role role : new ActorContext.Role[]{ActorContext.Role.OWNER, ActorContext.Role.SYSTEM}) {
            actAs(role);
            assertNotStarted(() -> service.create(settledCreate(settled, NOW.plusSeconds(3600))));
        }
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"COMPLETED", "NO_SHOW"})
    void ownerAndSystemStillRecordPastVisitsAsSettled(AppointmentStatus settled) {
        // The demo seeder and imports create historical visits directly in their final status.
        actAs(ActorContext.Role.SYSTEM);

        assertThat(service.create(settledCreate(settled, NOW.minusSeconds(3600 * 24))).status()).isEqualTo(settled);
    }
}

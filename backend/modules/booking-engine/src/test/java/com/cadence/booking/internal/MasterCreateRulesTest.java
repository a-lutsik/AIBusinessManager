package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.CreateBookingCommand;
import com.cadence.catalog.api.BookingRulesView;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.Offering;
import com.cadence.catalog.api.WeeklyInterval;
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
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * A MASTER creates visits only under the tenant's booking rules and the status machine: no notice/horizon/schedule
 * bypass, no visit born in a terminal status, no CONFIRMED where the client still needs confirmation. OWNER and SYSTEM
 * (seeder) keep both switches. The confirmation rule also holds for the follow-up PENDING to CONFIRMED transition.
 */
class MasterCreateRulesTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SPECIALIST = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID CLIENT = UUID.randomUUID();
    /** 10:00 in Tbilisi on Thursday 2026-10-01; the visit is the next day at 12:00 local, inside the schedule. */
    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");
    private static final Instant START = Instant.parse("2026-10-02T08:00:00Z");

    private CatalogService catalog;
    private CrmService crm;
    private RetentionService retention;
    private AppointmentRepository appointments;
    private BookingServiceImpl service;
    private TenantContext.Scope tenantScope;

    @BeforeEach
    void setUp() {
        catalog = mock(CatalogService.class);
        crm = mock(CrmService.class);
        retention = mock(RetentionService.class);
        appointments = mock(AppointmentRepository.class);
        TenantDirectory tenants = mock(TenantDirectory.class);
        service = new BookingServiceImpl(
                catalog,
                crm,
                retention,
                appointments,
                tenants,
                mock(OutboxPublisher.class),
                mock(AuditLogger.class),
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
        List<WeeklyInterval> week = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            week.add(new WeeklyInterval(UUID.randomUUID(), SPECIALIST, day, 10 * 60, 19 * 60));
        }
        when(catalog.weeklySchedule(SPECIALIST)).thenReturn(week);
        when(catalog.exceptions(eq(SPECIALIST), any(), any())).thenReturn(List.of());
        when(appointments.occupying(eq(SPECIALIST), any(), any(), any())).thenReturn(List.of());
        // The client already has a live visit with this master, so booking them by id stays in the master's scope.
        when(appointments.hasActiveVisit(CLIENT, SPECIALIST)).thenReturn(true);
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

    private static CreateBookingCommand command(AppointmentStatus statusOverride, boolean skipRules) {
        return command(statusOverride, skipRules, START);
    }

    private static CreateBookingCommand command(AppointmentStatus statusOverride, boolean skipRules, Instant start) {
        return new CreateBookingCommand(SPECIALIST, SERVICE, start, CLIENT, null, "Mariam", "en", false, null,
                null, "CONSOLE", statusOverride, skipRules);
    }

    private AppointmentStatus insertedStatus() {
        ArgumentCaptor<AppointmentView> row = ArgumentCaptor.forClass(AppointmentView.class);
        verify(appointments).insert(row.capture());
        return row.getValue().status();
    }

    private static void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(403);
                    assertThat(ex.code()).isEqualTo("FORBIDDEN_RESOURCE");
                });
    }

    @Test
    void masterCannotSkipNoticeHorizonAndSchedule() {
        actAs(ActorContext.Role.MASTER);

        assertForbidden(() -> service.create(command(null, true)));

        verifyNoInteractions(appointments, crm, retention);
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"COMPLETED", "NO_SHOW", "CANCELLED_BY_CLIENT", "CANCELLED_BY_BUSINESS"})
    void masterCannotCreateAVisitInATerminalStatus(AppointmentStatus terminal) {
        actAs(ActorContext.Role.MASTER);

        assertForbidden(() -> service.create(command(terminal, false)));

        verifyNoInteractions(appointments, crm, retention);
    }

    @Test
    void masterCannotConfirmAVisitTheClientMustStillConfirm() {
        actAs(ActorContext.Role.MASTER);
        when(retention.requiresConfirmation(CLIENT)).thenReturn(true);

        assertForbidden(() -> service.create(command(AppointmentStatus.CONFIRMED, false)));

        verify(appointments, never()).insert(any());
    }

    @Test
    void masterMayStateConfirmedWhenTheRulesGiveConfirmedAnyway() {
        actAs(ActorContext.Role.MASTER);
        when(retention.requiresConfirmation(CLIENT)).thenReturn(false);

        service.create(command(AppointmentStatus.CONFIRMED, false));

        assertThat(insertedStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
    }

    @Test
    void masterMayAskForPendingAndOtherwiseGetsTheComputedStatus() {
        actAs(ActorContext.Role.MASTER);
        when(retention.requiresConfirmation(CLIENT)).thenReturn(true);

        service.create(command(null, false));

        assertThat(insertedStatus()).isEqualTo(AppointmentStatus.PENDING);
    }

    @Test
    void masterExplicitPendingIsKept() {
        actAs(ActorContext.Role.MASTER);
        when(retention.requiresConfirmation(CLIENT)).thenReturn(false);

        service.create(command(AppointmentStatus.PENDING, false));

        assertThat(insertedStatus()).isEqualTo(AppointmentStatus.PENDING);
    }

    @ParameterizedTest
    @EnumSource(value = ActorContext.Role.class, names = {"OWNER", "SYSTEM"})
    void ownerAndSystemKeepBothSwitches(ActorContext.Role role) {
        actAs(role);

        // A settled visit is recorded only for a start that has passed (TD-17); a past start needs the rule bypass.
        service.create(command(AppointmentStatus.COMPLETED, true, NOW.minusSeconds(3600 * 24)));

        assertThat(insertedStatus()).isEqualTo(AppointmentStatus.COMPLETED);
    }

    private static AppointmentView visit(AppointmentStatus status, UUID specialistId) {
        return new AppointmentView(UUID.randomUUID(), specialistId, SERVICE, CLIENT, status, START,
                START.plusSeconds(3600), START, START.plusSeconds(3600), "Haircut", 8000, 60, 0, 0, "GEL", 0, null,
                "Mariam", null, "CONSOLE", "Mariam", null, null, false);
    }

    private AppointmentView stored(AppointmentStatus status, UUID specialistId) {
        AppointmentView view = visit(status, specialistId);
        when(appointments.findForUpdate(view.id())).thenReturn(Optional.of(view));
        return view;
    }

    @Test
    void masterCannotConfirmAPendingVisitOfAClientWhoMustBeConfirmedByTheOwner() {
        actAs(ActorContext.Role.MASTER);
        when(retention.requiresConfirmation(CLIENT)).thenReturn(true);
        AppointmentView pending = stored(AppointmentStatus.PENDING, SPECIALIST);

        assertForbidden(() -> service.transition(pending.id(), AppointmentStatus.CONFIRMED, null));

        verify(appointments, never()).updateStatusAndTimes(any(), any());
        verify(appointments, never()).insertEvent(any(), any(), any());
    }

    @Test
    void masterMayConfirmAPendingVisitWhenTheClientNeedsNoConfirmation() {
        actAs(ActorContext.Role.MASTER);
        when(retention.requiresConfirmation(CLIENT)).thenReturn(false);
        AppointmentView pending = stored(AppointmentStatus.PENDING, SPECIALIST);

        AppointmentView result = service.transition(pending.id(), AppointmentStatus.CONFIRMED, null);

        assertThat(result.status()).isEqualTo(AppointmentStatus.CONFIRMED);
        verify(appointments).updateStatusAndTimes(any(), eq(AppointmentStatus.PENDING));
    }

    @Test
    void masterMayStillCancelAPendingVisitOfAClientWhoMustBeConfirmed() {
        actAs(ActorContext.Role.MASTER);
        when(retention.requiresConfirmation(CLIENT)).thenReturn(true);
        AppointmentView pending = stored(AppointmentStatus.PENDING, SPECIALIST);

        AppointmentView result = service.transition(pending.id(), AppointmentStatus.CANCELLED_BY_BUSINESS, null);

        assertThat(result.status()).isEqualTo(AppointmentStatus.CANCELLED_BY_BUSINESS);
    }

    @ParameterizedTest
    @EnumSource(value = ActorContext.Role.class, names = {"OWNER", "SYSTEM"})
    void ownerAndSystemConfirmWithoutTheClientCheck(ActorContext.Role role) {
        actAs(role);
        when(retention.requiresConfirmation(CLIENT)).thenReturn(true);
        AppointmentView pending = stored(AppointmentStatus.PENDING, SPECIALIST);

        AppointmentView result = service.transition(pending.id(), AppointmentStatus.CONFIRMED, null);

        assertThat(result.status()).isEqualTo(AppointmentStatus.CONFIRMED);
    }
}

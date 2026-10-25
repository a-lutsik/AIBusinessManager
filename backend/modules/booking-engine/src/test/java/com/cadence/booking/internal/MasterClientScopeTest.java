package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.CreateBookingCommand;
import com.cadence.catalog.api.BookingRulesView;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.Offering;
import com.cadence.catalog.api.WeeklyInterval;
import com.cadence.crm.api.ClientView;
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
import com.cadence.retention.api.TrustView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TD-19: a MASTER's client scope is made of their non-cancelled visits only, and booking cannot widen it unseen. A
 * {@code clientId} the master has no live visit with is refused before any side effect; a booking whose phone matches
 * an existing client answers with what the master typed, never with that client's stored name, phone or trust. The
 * owner's view of the same operations is unchanged.
 */
class MasterClientScopeTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SPECIALIST = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID EXISTING = UUID.randomUUID();
    private static final String EXISTING_PHONE = "+995555000777";
    private static final String EXISTING_NAME = "Stored Name Of Existing Client";
    /** 10:00 in Tbilisi on Thursday 2026-10-01; the visit is the next day at 12:00 local, inside the schedule. */
    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");
    private static final Instant START = Instant.parse("2026-10-02T08:00:00Z");

    private CrmService crm;
    private RetentionService retention;
    private AppointmentRepository appointments;
    private BookingServiceImpl service;
    private TenantContext.Scope tenantScope;

    @BeforeEach
    void setUp() {
        CatalogService catalog = mock(CatalogService.class);
        crm = mock(CrmService.class);
        retention = mock(RetentionService.class);
        appointments = mock(AppointmentRepository.class);
        TenantDirectory tenants = mock(TenantDirectory.class);
        PhoneNormalizer phones = mock(PhoneNormalizer.class);
        service = new BookingServiceImpl(catalog, crm, retention, appointments, tenants,
                mock(OutboxPublisher.class), mock(AuditLogger.class), mock(OwnerTaskService.class), phones,
                Clock.fixed(NOW, ZoneOffset.UTC), TestMessages.catalog());
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
        when(phones.toE164(EXISTING_PHONE, "GE")).thenReturn(EXISTING_PHONE);
        ClientView existing = new ClientView(EXISTING, EXISTING_PHONE, EXISTING_NAME, "owner note", "vip", "en",
                "tg-1", true, null, null, 4, 1, 0, 32000);
        when(crm.findOrCreate(eq(EXISTING_PHONE), anyString(), any(), anyBoolean(), any())).thenReturn(existing);
        when(crm.findById(EXISTING)).thenReturn(Optional.of(existing));
        when(retention.compute(EXISTING)).thenReturn(new TrustView(EXISTING, "LOW", List.of("1 no-shows recorded"), null, null, List.of("NO_SHOWS")));
        tenantScope = TenantContext.open(TENANT);
    }

    @AfterEach
    void tearDown() {
        tenantScope.close();
        ActorContext.clear();
    }

    private static void actAsMaster() {
        ActorContext.set(new ActorContext.Actor("master", ActorContext.Role.MASTER, SPECIALIST));
    }

    private static void actAsOwner() {
        ActorContext.set(new ActorContext.Actor("owner", ActorContext.Role.OWNER, null));
    }

    private static CreateBookingCommand byClientId(UUID clientId) {
        return new CreateBookingCommand(SPECIALIST, SERVICE, START, clientId, null, null, "en", false, null,
                null, "CONSOLE", null, false);
    }

    private static CreateBookingCommand byPhone(String typedName) {
        return new CreateBookingCommand(SPECIALIST, SERVICE, START, null, EXISTING_PHONE, typedName, "en", false, null,
                null, "CONSOLE", null, false);
    }

    @Test
    void masterCannotBookAClientIdWithoutALiveVisitWithThem() {
        actAsMaster();
        when(appointments.hasActiveVisit(EXISTING, SPECIALIST)).thenReturn(false);

        assertThatThrownBy(() -> service.create(byClientId(EXISTING)))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(403);
                    assertThat(ex.code()).isEqualTo("FORBIDDEN_RESOURCE");
                });
        verify(appointments, never()).record(any(), JournalRecords.created(), any());
        verifyNoInteractions(crm, retention);
    }

    @Test
    void masterBooksAClientIdInTheirLiveScope() {
        actAsMaster();
        when(appointments.hasActiveVisit(EXISTING, SPECIALIST)).thenReturn(true);

        AppointmentView created = service.create(byClientId(EXISTING));

        assertThat(created.clientId()).isEqualTo(EXISTING);
        verify(appointments).record(any(), JournalRecords.created(), any());
    }

    @Test
    void ownerBooksAnyClientIdWithoutTheScopeCheck() {
        actAsOwner();

        service.create(byClientId(EXISTING));

        verify(appointments, never()).hasActiveVisit(any(), any());
        verify(appointments).record(any(), JournalRecords.created(), any());
    }

    @Test
    void masterPhoneMatchAnswersWithWhatTheMasterTypedOnly() {
        actAsMaster();

        AppointmentView created = service.create(byPhone("Typed By Master"));

        // Outside the master's scope the visit is hidden (decision A): not even the client id is shown.
        assertThat(created.clientId()).isNull();
        assertThat(created.clientDisplayName()).isEqualTo("Typed By Master");
        assertThat(created.enteredName()).isEqualTo("Typed By Master");
        assertThat(created.clientPhone()).isNull();
        assertThat(created.trustLevel()).isNull();
        assertThat(created.toString()).doesNotContain(EXISTING_NAME).doesNotContain(EXISTING_PHONE);
    }

    @Test
    void masterPhoneMatchWithoutATypedNameAnswersWithNulls() {
        actAsMaster();

        AppointmentView created = service.create(byPhone(null));

        assertThat(created.clientDisplayName()).isNull();
        assertThat(created.enteredName()).isNull();
        assertThat(created.clientPhone()).isNull();
        assertThat(created.toString()).doesNotContain(EXISTING_NAME).doesNotContain(EXISTING_PHONE);
    }

    @Test
    void ownerPhoneMatchStillShowsTheExistingClientAndTheTypedNameMismatch() {
        actAsOwner();

        AppointmentView created = service.create(byPhone("Typed By Owner"));

        assertThat(created.clientDisplayName()).isEqualTo(EXISTING_NAME);
        assertThat(created.clientPhone()).isEqualTo(EXISTING_PHONE);
        assertThat(created.trustLevel()).isEqualTo("LOW");
        assertThat(created.enteredName()).isEqualTo("Typed By Owner / " + EXISTING_NAME);
    }

    @Test
    void masterClientListAndHistoryLeaveCancelledVisitsOut() {
        actAsMaster();
        UUID foreign = UUID.randomUUID();
        when(appointments.clientIdsOf(SPECIALIST)).thenReturn(List.of(EXISTING));

        assertThat(service.clientIdsOf(foreign)).containsExactly(EXISTING);
        service.clientHistory(EXISTING);

        verify(appointments).clientIdsOf(SPECIALIST);
        verify(appointments).byClient(EXISTING, SPECIALIST, true);
    }

    @Test
    void ownerHistoryKeepsEveryVisitIncludingCancelled() {
        actAsOwner();

        service.clientHistory(EXISTING);

        verify(appointments).byClient(EXISTING, null, false);
    }

    @Test
    void masterBookingStoresTheTypedNameWithoutTheExistingOne() {
        actAsMaster();

        service.create(byPhone("Typed By Master"));

        ArgumentCaptor<AppointmentView> row = ArgumentCaptor.forClass(AppointmentView.class);
        verify(appointments).record(row.capture(), JournalRecords.created(), any());
        assertThat(row.getValue().enteredName()).isEqualTo("Typed By Master");
    }
}

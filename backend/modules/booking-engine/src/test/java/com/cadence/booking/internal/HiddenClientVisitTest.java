package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.CreateBookingCommand;
import com.cadence.catalog.api.BookingRulesView;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.Offering;
import com.cadence.catalog.api.SpecialistView;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Decision A (stage 03), "hide until confirmed": a MASTER's booking by phone whose client is not already in the
 * master's scope (an existing client matched by the phone, or a client created by it) is always PENDING, flagged
 * {@code clientHiddenFromMaster}, and tasks the owner to confirm; status and answer are the same in both cases, so
 * the booking is no existence oracle. Until an OWNER confirms it, every master read shows only the typed name. Also:
 * public token routes answer with what the visitor typed only (finding 4), and a visit stores the typed name alone,
 * the owner's "typed / stored" hint being built when the owner reads it.
 */
class HiddenClientVisitTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SPECIALIST = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID EXISTING = UUID.randomUUID();
    private static final UUID CREATED = UUID.randomUUID();
    private static final String EXISTING_PHONE = "+995555000777";
    private static final String NEW_PHONE = "+995555000888";
    private static final String EXISTING_NAME = "Stored Name Of Existing Client";
    private static final String TYPED = "Typed By Nino";
    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");
    private static final Instant START = Instant.parse("2026-10-02T08:00:00Z");

    private CrmService crm;
    private RetentionService retention;
    private AppointmentRepository appointments;
    private OwnerTaskService ownerTasks;
    private BookingServiceImpl service;
    private TenantContext.Scope tenantScope;

    @BeforeEach
    void setUp() {
        CatalogService catalog = mock(CatalogService.class);
        crm = mock(CrmService.class);
        retention = mock(RetentionService.class);
        appointments = mock(AppointmentRepository.class);
        ownerTasks = mock(OwnerTaskService.class);
        TenantDirectory tenants = mock(TenantDirectory.class);
        PhoneNormalizer phones = mock(PhoneNormalizer.class);
        service = new BookingServiceImpl(catalog, crm, retention, appointments, tenants,
                mock(OutboxPublisher.class), mock(AuditLogger.class), ownerTasks, phones,
                Clock.fixed(NOW, ZoneOffset.UTC), TestMessages.catalog());
        TenantRecord tenant = new TenantRecord();
        tenant.setId(TENANT);
        tenant.setTimezone("Asia/Tbilisi");
        tenant.setCurrencyCode("GEL");
        tenant.setCountryCode("GE");
        tenant.setDisplayName("Lumen");
        when(tenants.requireById(TENANT)).thenReturn(tenant);
        when(catalog.offering(SPECIALIST, SERVICE)).thenReturn(Optional.of(
                new Offering(SPECIALIST, SERVICE, "Haircut", true, 60, 8000, 0, 0, "#000000", true)));
        // New clients need no confirmation, so without decision A the rules would confirm these visits right away.
        when(catalog.rules()).thenReturn(new BookingRulesView(15, 60, 28, true, 24, false, false, 1, true));
        when(catalog.findSpecialist(SPECIALIST)).thenReturn(Optional.of(
                new SpecialistView(SPECIALIST, "Nino", "kc-nino", "#111111", true)));
        List<WeeklyInterval> week = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            week.add(new WeeklyInterval(UUID.randomUUID(), SPECIALIST, day, 10 * 60, 19 * 60));
        }
        when(catalog.weeklySchedule(SPECIALIST)).thenReturn(week);
        when(catalog.exceptions(eq(SPECIALIST), any(), any())).thenReturn(List.of());
        when(appointments.occupying(eq(SPECIALIST), any(), any(), any())).thenReturn(List.of());
        when(phones.toE164(EXISTING_PHONE, "GE")).thenReturn(EXISTING_PHONE);
        when(phones.toE164(NEW_PHONE, "GE")).thenReturn(NEW_PHONE);
        ClientView existing = new ClientView(EXISTING, EXISTING_PHONE, EXISTING_NAME, "owner note", "vip", "en",
                "tg-1", true, null, null, 4, 1, 0, 32000);
        ClientView created = new ClientView(CREATED, NEW_PHONE, TYPED, null, null, "en", null, false, null, null,
                0, 0, 0, 0);
        when(crm.findOrCreate(eq(EXISTING_PHONE), anyString(), any(), anyBoolean(), any())).thenReturn(existing);
        when(crm.findOrCreate(eq(NEW_PHONE), anyString(), any(), anyBoolean(), any())).thenReturn(created);
        when(crm.findById(EXISTING)).thenReturn(Optional.of(existing));
        when(crm.findById(CREATED)).thenReturn(Optional.of(created));
        when(retention.compute(EXISTING)).thenReturn(
                new TrustView(EXISTING, "LOW", List.of("1 no-shows recorded"), null, null, List.of("NO_SHOWS")));
        when(retention.compute(CREATED)).thenReturn(
                new TrustView(CREATED, "NEW", List.of("No completed visits yet"), null, null, List.of("NEW_CLIENT")));
        when(appointments.completedRangeExcluding(any(), any())).thenReturn(new AppointmentRepository.CompletedRange(null, null));
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

    private static CreateBookingCommand byPhone(String phone, String typedName, AppointmentStatus statusOverride) {
        return new CreateBookingCommand(SPECIALIST, SERVICE, START, null, phone, typedName, "en", false, null,
                null, "CONSOLE", statusOverride, false);
    }

    private AppointmentView inserted() {
        ArgumentCaptor<AppointmentView> row = ArgumentCaptor.forClass(AppointmentView.class);
        verify(appointments).record(row.capture(), JournalRecords.created(), any());
        return row.getValue();
    }

    /** A stored visit as {@code AppointmentRepository.toView} returns it: client fields from the typed name only. */
    private AppointmentView stored(UUID clientId, AppointmentStatus status, String enteredName, boolean hidden) {
        AppointmentView view = new AppointmentView(UUID.randomUUID(), SPECIALIST, SERVICE, clientId, status, START,
                START.plusSeconds(3600), START, START.plusSeconds(3600), "Haircut", 8000, 60, 0, 0, "GEL", 0, null,
                enteredName, null, "CONSOLE", enteredName, null, null, hidden);
        when(appointments.find(view.id())).thenReturn(Optional.of(view));
        when(appointments.findForUpdate(view.id())).thenReturn(Optional.of(view));
        when(appointments.calendar(any(), any(), eq(SPECIALIST))).thenReturn(List.of(view));
        return view;
    }

    /** A master's view of a visit that withholds its client: also no client id (review minor 1). */
    private static void assertMasterSeesTypedOnly(AppointmentView view, String typed) {
        assertShowsTypedOnly(view, typed);
        assertThat(view.clientId()).isNull();
    }

    private static void assertShowsTypedOnly(AppointmentView view, String typed) {
        assertThat(view.clientDisplayName()).isEqualTo(typed);
        assertThat(view.clientPhone()).isNull();
        assertThat(view.trustLevel()).isNull();
        assertThat(view.toString()).doesNotContain(EXISTING_NAME).doesNotContain(EXISTING_PHONE);
    }

    // ---- creation: same answer whether the phone was known or not ----

    @Test
    void masterPhoneBookingOfAnExistingClientOutsideTheScopeIsPendingHiddenAndTasksTheOwner() {
        actAsMaster();
        // The client needs confirmation: before decision A a requested CONFIRMED was refused only for such a client,
        // which told the master the phone was known.
        when(retention.requiresConfirmation(EXISTING)).thenReturn(true);

        AppointmentView created = service.create(byPhone(EXISTING_PHONE, TYPED, AppointmentStatus.CONFIRMED));

        assertThat(created.status()).isEqualTo(AppointmentStatus.PENDING);
        assertThat(created.clientHiddenFromMaster()).isTrue();
        assertMasterSeesTypedOnly(created, TYPED);
        AppointmentView row = inserted();
        assertThat(row.clientId()).as("stored on the real client").isEqualTo(EXISTING);
        assertThat(row.clientHiddenFromMaster()).isTrue();
        assertThat(row.status()).isEqualTo(AppointmentStatus.PENDING);
        assertThat(row.enteredName()).isEqualTo(TYPED);
        verify(ownerTasks).create(eq("MASTER_PHONE_BOOKING"), contains("Nino"), any(), any(), any(), eq(row.id()), eq(EXISTING));
    }

    @Test
    void aClientCreatedByTheMastersPhoneBookingGetsExactlyTheSameTreatment() {
        actAsMaster();

        AppointmentView created = service.create(byPhone(NEW_PHONE, TYPED, AppointmentStatus.CONFIRMED));

        assertThat(created.status()).isEqualTo(AppointmentStatus.PENDING);
        assertThat(created.clientHiddenFromMaster()).isTrue();
        assertMasterSeesTypedOnly(created, TYPED);
        AppointmentView row = inserted();
        assertThat(row.clientHiddenFromMaster()).isTrue();
        verify(ownerTasks).create(eq("MASTER_PHONE_BOOKING"), contains("Nino"), any(), any(), any(), eq(row.id()), eq(CREATED));
    }

    @Test
    void bothPhoneCasesAnswerIdenticallyApartFromIds() {
        actAsMaster();
        AppointmentView known = service.create(byPhone(EXISTING_PHONE, TYPED, null));
        AppointmentView unknown = service.create(byPhone(NEW_PHONE, TYPED, null));

        for (AppointmentView view : List.of(known, unknown)) {
            assertThat(List.of(view.status(), view.clientHiddenFromMaster(), String.valueOf(view.clientId()),
                    String.valueOf(view.clientDisplayName()), String.valueOf(view.enteredName()),
                    String.valueOf(view.clientPhone()), String.valueOf(view.trustLevel())))
                    .isEqualTo(List.of(AppointmentStatus.PENDING, true, "null", TYPED, TYPED, "null", "null"));
        }
    }

    @Test
    void aPhoneOfAClientAlreadyInTheMastersScopeBooksAsUsual() {
        actAsMaster();
        when(appointments.hasActiveVisit(EXISTING, SPECIALIST)).thenReturn(true);

        AppointmentView created = service.create(byPhone(EXISTING_PHONE, TYPED, null));

        assertThat(created.status()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(created.clientHiddenFromMaster()).isFalse();
        assertThat(inserted().clientHiddenFromMaster()).isFalse();
        verify(ownerTasks, never()).create(eq("MASTER_PHONE_BOOKING"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void ownerAndPublicPhoneBookingsAreNeverHidden() {
        actAsOwner();
        service.create(byPhone(EXISTING_PHONE, "Typed By Owner", null));
        assertThat(inserted().clientHiddenFromMaster()).isFalse();
        verify(appointments, never()).hasActiveVisit(any(), any());
        verify(ownerTasks, never()).create(eq("MASTER_PHONE_BOOKING"), any(), any(), any(), any(), any(), any());
    }

    // ---- master reads of a hidden visit ----

    @Test
    void everyMasterReadOfAHiddenVisitShowsOnlyTheTypedName() {
        actAsMaster();
        AppointmentView hidden = stored(EXISTING, AppointmentStatus.PENDING, TYPED, true);

        assertMasterSeesTypedOnly(service.find(hidden.id()).orElseThrow(), TYPED);
        assertMasterSeesTypedOnly(service.calendar(NOW, NOW.plusSeconds(86_400 * 3), null).getFirst(), TYPED);
        assertMasterSeesTypedOnly(service.reschedule(hidden.id(), START), TYPED);
        verify(retention, never()).compute(EXISTING);
    }

    @Test
    void aHiddenVisitWithoutATypedNameShowsNoName() {
        actAsMaster();
        AppointmentView hidden = stored(EXISTING, AppointmentStatus.PENDING, null, true);

        assertMasterSeesTypedOnly(service.find(hidden.id()).orElseThrow(), null);
    }

    @Test
    void theOwnerSeesTheStoredClientOfAHiddenVisit() {
        actAsOwner();
        AppointmentView hidden = stored(EXISTING, AppointmentStatus.PENDING, TYPED, true);

        AppointmentView view = service.find(hidden.id()).orElseThrow();

        assertThat(view.clientDisplayName()).isEqualTo(EXISTING_NAME);
        assertThat(view.clientPhone()).isEqualTo(EXISTING_PHONE);
        assertThat(view.trustLevel()).isEqualTo("LOW");
        assertThat(view.enteredName()).isEqualTo(TYPED + " / " + EXISTING_NAME);
    }

    // ---- confirmation and cancellation ----

    @Test
    void theMasterCannotConfirmAHiddenVisit() {
        actAsMaster();
        AppointmentView hidden = stored(EXISTING, AppointmentStatus.PENDING, TYPED, true);

        assertThatThrownBy(() -> service.transition(hidden.id(), AppointmentStatus.CONFIRMED, null))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(403);
                    assertThat(ex.code()).isEqualTo("FORBIDDEN_RESOURCE");
                });
        verify(appointments, never()).record(any(), any(), JournalRecords.anyStatusChange());
    }

    @Test
    void theOwnersConfirmationClearsTheFlagAndTheMasterThenSeesTheStoredClient() {
        actAsOwner();
        AppointmentView hidden = stored(EXISTING, AppointmentStatus.PENDING, TYPED, true);

        AppointmentView confirmed = service.transition(hidden.id(), AppointmentStatus.CONFIRMED, null);

        ArgumentCaptor<AppointmentView> written = ArgumentCaptor.forClass(AppointmentView.class);
        verify(appointments).record(written.capture(), any(), JournalRecords.statusFrom(AppointmentStatus.PENDING));
        assertThat(written.getValue().clientHiddenFromMaster()).isFalse();
        assertThat(confirmed.clientHiddenFromMaster()).isFalse();

        actAsMaster();
        AppointmentView revealed = stored(EXISTING, AppointmentStatus.CONFIRMED, TYPED, false);
        AppointmentView view = service.find(revealed.id()).orElseThrow();
        assertThat(view.clientDisplayName()).isEqualTo(EXISTING_NAME);
        assertThat(view.trustLevel()).isEqualTo("LOW");
    }

    @Test
    void cancellingAHiddenVisitNeverRevealsTheClient() {
        actAsMaster();
        AppointmentView byMaster = stored(EXISTING, AppointmentStatus.PENDING, TYPED, true);
        AppointmentView cancelled = service.transition(byMaster.id(), AppointmentStatus.CANCELLED_BY_BUSINESS, null);
        assertMasterSeesTypedOnly(cancelled, TYPED);

        actAsOwner();
        AppointmentView byOwner = stored(EXISTING, AppointmentStatus.PENDING, TYPED, true);
        service.transition(byOwner.id(), AppointmentStatus.CANCELLED_BY_CLIENT, null);

        ArgumentCaptor<AppointmentView> written = ArgumentCaptor.forClass(AppointmentView.class);
        verify(appointments, org.mockito.Mockito.times(2)).record(written.capture(), any(), JournalRecords.statusFrom(AppointmentStatus.PENDING));
        assertThat(written.getAllValues()).allSatisfy(row -> assertThat(row.clientHiddenFromMaster()).isTrue());
        // Review minor 7: the owner's task about the phone booking closes on any cancellation; nothing is revealed.
        verify(ownerTasks).completeForAppointment(byMaster.id(), "MASTER_PHONE_BOOKING");
        verify(ownerTasks).completeForAppointment(byOwner.id(), "MASTER_PHONE_BOOKING");
        verify(appointments, never()).revealToMaster(any(), any());
    }

    @Test
    void theOwnersConfirmationRevealsTheMastersOtherHiddenVisitsWithTheClientAndClosesTheTask() {
        // Review minor 7, decided "reveal": once confirmed the client is in the master's scope (list, card), so the
        // master's other hidden visits with them would only show an inconsistent typed name.
        actAsOwner();
        AppointmentView hidden = stored(EXISTING, AppointmentStatus.PENDING, TYPED, true);

        service.transition(hidden.id(), AppointmentStatus.CONFIRMED, null);

        verify(appointments).revealToMaster(EXISTING, SPECIALIST);
        verify(ownerTasks).completeForAppointment(hidden.id(), "MASTER_PHONE_BOOKING");
    }

    // ---- cancelled visits in a master's view (review minor 2) ----

    @Test
    void aCancelledVisitShowsTheMasterNoLiveClientData() {
        actAsMaster();
        AppointmentView cancelled = stored(EXISTING, AppointmentStatus.CANCELLED_BY_CLIENT, "Typed Earlier", false);

        assertMasterSeesTypedOnly(service.find(cancelled.id()).orElseThrow(), "Typed Earlier");
        assertMasterSeesTypedOnly(service.calendar(NOW, NOW.plusSeconds(86_400 * 3), null).getFirst(), "Typed Earlier");
        verify(crm, never()).findById(EXISTING);
        verify(retention, never()).compute(EXISTING);
    }

    @Test
    void aCancelledVisitStillShowsTheOwnerTheStoredClient() {
        actAsOwner();
        AppointmentView cancelled = stored(EXISTING, AppointmentStatus.CANCELLED_BY_BUSINESS, "Typed Earlier", false);

        AppointmentView view = service.find(cancelled.id()).orElseThrow();

        assertThat(view.clientId()).isEqualTo(EXISTING);
        assertThat(view.clientDisplayName()).isEqualTo(EXISTING_NAME);
        assertThat(view.trustLevel()).isEqualTo("LOW");
    }

    // ---- public token routes (finding 4) ----

    private void tokenFor(AppointmentView view) {
        when(appointments.resolveToken(any())).thenReturn(Optional.of(new AppointmentRepository.TokenTarget(TENANT, view.id())));
    }

    @Test
    void thePublicVisitPageShowsWhatTheVisitorTypedAndNoStoredClient() {
        AppointmentView booked = stored(EXISTING, AppointmentStatus.CONFIRMED, "Typed Visitor", false);
        tokenFor(booked);

        AppointmentView view = service.findByAccessToken("raw").orElseThrow();

        assertThat(view.enteredName()).isEqualTo("Typed Visitor");
        assertShowsTypedOnly(view, "Typed Visitor");
    }

    @Test
    void aLegacyTypedSlashStoredNameLosesTheStoredPartOnThePublicPage() {
        AppointmentView legacy = stored(EXISTING, AppointmentStatus.CONFIRMED, "Typed Visitor / " + EXISTING_NAME, false);
        tokenFor(legacy);

        AppointmentView view = service.findByAccessToken("raw").orElseThrow();

        assertThat(view.enteredName()).isEqualTo("Typed Visitor");
        assertShowsTypedOnly(view, "Typed Visitor");
    }

    @Test
    void aLegacyRowKeepsNoStoredPartEvenAfterTheClientWasRenamedOrMerged() {
        // Review minor 5: the stored name at booking time is no longer the client's name; it must not show either,
        // nor a stored name that itself contains the separator.
        AppointmentView renamed = stored(EXISTING, AppointmentStatus.CONFIRMED, "Typed Visitor / Old Name / Part", false);
        tokenFor(renamed);

        AppointmentView view = service.findByAccessToken("raw").orElseThrow();

        assertThat(view.enteredName()).isEqualTo("Typed Visitor");
        assertThat(view.toString()).doesNotContain("Old Name");
    }

    @Test
    void consentIsWithdrawnThroughTheManageLink() {
        AppointmentView booked = stored(EXISTING, AppointmentStatus.CONFIRMED, "Typed Visitor", false);
        tokenFor(booked);

        AppointmentView view = service.withdrawConsentByAccessToken(booked.id(), "raw");

        verify(crm).withdrawMarketingConsent(EXISTING, "public-link");
        assertShowsTypedOnly(view, "Typed Visitor");
    }

    @Test
    void aTokenOfAnotherVisitWithdrawsNothing() {
        AppointmentView booked = stored(EXISTING, AppointmentStatus.CONFIRMED, "Typed Visitor", false);
        tokenFor(booked);

        assertThatThrownBy(() -> service.withdrawConsentByAccessToken(UUID.randomUUID(), "raw"))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(403);
                    assertThat(ex.code()).isEqualTo("TOKEN_INVALID");
                });
        verify(crm, never()).withdrawMarketingConsent(any(), any());
    }

    @Test
    void publicCancelAndRescheduleAnswerWithoutTheStoredClient() {
        AppointmentView booked = stored(EXISTING, AppointmentStatus.CONFIRMED, "Typed Visitor", false);
        tokenFor(booked);

        assertShowsTypedOnly(service.rescheduleByAccessToken("raw", START), "Typed Visitor");
        assertShowsTypedOnly(service.cancelByAccessToken("raw", true), "Typed Visitor");
    }

    @Test
    void thePublicCreateAnswerCarriesNoStoredClient() {
        AppointmentView created = service.createPublic(byPhone(EXISTING_PHONE, "Typed Visitor", null)).appointment();

        assertShowsTypedOnly(created, "Typed Visitor");
        assertThat(created.enteredName()).isEqualTo("Typed Visitor");
    }

    // ---- the stored typed name ----

    @Test
    void aVisitStoresOnlyTheTypedNameAndTheOwnerGetsTheHintWhenReading() {
        actAsOwner();

        AppointmentView created = service.create(byPhone(EXISTING_PHONE, "Typed By Owner", null));

        assertThat(inserted().enteredName()).isEqualTo("Typed By Owner");
        assertThat(created.enteredName()).isEqualTo("Typed By Owner / " + EXISTING_NAME);
    }

    @Test
    void aPublicBookingStoresOnlyWhatTheVisitorTyped() {
        service.createPublic(byPhone(EXISTING_PHONE, "Typed Visitor", null));

        assertThat(inserted().enteredName()).isEqualTo("Typed Visitor");
    }

    @Test
    void theOwnerHintIsNotDoubledOnALegacyRow() {
        actAsOwner();
        AppointmentView legacy = stored(EXISTING, AppointmentStatus.CONFIRMED, "Typed / " + EXISTING_NAME, false);

        assertThat(service.find(legacy.id()).orElseThrow().enteredName()).isEqualTo("Typed / " + EXISTING_NAME);
    }
}

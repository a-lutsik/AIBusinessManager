package com.cadence.app.web;

import com.cadence.ai.api.AiAssistantService;
import com.cadence.app.ratelimit.ClientIpResolver;
import com.cadence.app.ratelimit.PublicRateLimiter;
import com.cadence.app.usecase.ClientMergeService;
import com.cadence.app.web.dto.AppointmentDetailView;
import com.cadence.app.web.dto.MasterTodayView;
import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.BookingService;
import com.cadence.catalog.api.CatalogService;
import com.cadence.crm.api.CrmService;
import com.cadence.growth.api.GrowthService;
import com.cadence.notification.api.OwnerTaskService;
import com.cadence.platform.jooq.tables.records.TenantRecord;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.cadence.retention.api.RetentionService;
import com.cadence.retention.api.TrustView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The handlers that add trust or packages to a visit (decision A): for a visit hidden from the master they add
 * neither, so the trust and packages of the client behind the phone stay with the owner. And the public visit routes
 * (finding 4) answer with visit facts and the typed name only.
 */
class HiddenClientResponsesTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID NINO = UUID.randomUUID();
    private static final UUID HIDDEN_CLIENT = UUID.randomUUID();
    private static final UUID SHARED_CLIENT = UUID.randomUUID();

    private final BookingService booking = mock(BookingService.class);
    private final RetentionService retention = mock(RetentionService.class);
    private final TenantDirectory tenants = mock(TenantDirectory.class);
    private final CrmService crm = mock(CrmService.class);
    private final AppApiController app = new AppApiController(mock(CatalogService.class), booking, crm,
            retention, mock(GrowthService.class), mock(AiAssistantService.class), mock(OwnerTaskService.class), tenants,
            mock(ClientMergeService.class));
    private final PublicApiController publicApi = new PublicApiController(mock(CatalogService.class), booking, tenants,
            mock(PublicRateLimiter.class), mock(ClientIpResolver.class));
    private final JsonMapper mapper = JsonMapper.builder().build();
    private TenantContext.Scope scope;

    @BeforeEach
    void setUp() {
        TenantRecord tenant = new TenantRecord();
        tenant.setId(TENANT);
        tenant.setTimezone("Asia/Tbilisi");
        when(tenants.requireById(TENANT)).thenReturn(tenant);
        when(retention.assess(any())).thenAnswer(inv -> new TrustView(inv.getArgument(0), "LOW",
                List.of("2 no-shows recorded"), null, null, List.of("NO_SHOWS")));
        when(retention.packagesForClient(any())).thenAnswer(inv -> List.of(
                new RetentionService.PackageView(UUID.randomUUID(), inv.getArgument(0), "5 haircuts", 4, 5, 35000)));
        scope = TenantContext.open(TENANT);
    }

    @AfterEach
    void tearDown() {
        scope.close();
        ActorContext.clear();
    }

    private static AppointmentView visit(UUID clientId, boolean hidden) {
        Instant start = Instant.parse("2026-10-24T10:00:00Z");
        return new AppointmentView(UUID.randomUUID(), NINO, UUID.randomUUID(), clientId,
                hidden ? AppointmentStatus.PENDING : AppointmentStatus.CONFIRMED, start, start.plusSeconds(3600), start,
                start.plusSeconds(3600), "Haircut", 8000, 60, 0, 0, "GEL", 0, null, "Typed", null, "CONSOLE",
                hidden ? "Typed" : "Stored Name", hidden ? null : "+995555000001", hidden ? null : "LOW", hidden);
    }

    private static AppointmentView cancelled(UUID clientId) {
        AppointmentView v = visit(clientId, false);
        return new AppointmentView(v.id(), v.specialistId(), v.serviceId(), null, AppointmentStatus.CANCELLED_BY_CLIENT,
                v.serviceStart(), v.serviceEnd(), v.occupiedStart(), v.occupiedEnd(), v.serviceNameSnapshot(),
                v.priceSnapshot(), v.durationSnapshot(), v.bufferBeforeSnapshot(), v.bufferAfterSnapshot(),
                v.currencyCode(), v.discountAmount(), v.amountReceived(), "Typed", null, "CONSOLE", "Typed", null, null,
                false);
    }

    private static void actAsMaster() {
        ActorContext.set(new ActorContext.Actor("m", ActorContext.Role.MASTER, NINO));
    }

    @Test
    void visitDetailOfAHiddenVisitCarriesNoTrustForTheMaster() {
        actAsMaster();
        AppointmentView hidden = visit(HIDDEN_CLIENT, true);
        when(booking.find(hidden.id())).thenReturn(Optional.of(hidden));

        AppointmentDetailView detail = app.appointment(hidden.id());

        assertThat(detail.trust()).isNull();
        verify(retention, never()).assess(HIDDEN_CLIENT);
    }

    @Test
    void visitDetailOfAVisibleVisitKeepsTheTrust() {
        actAsMaster();
        AppointmentView shared = visit(SHARED_CLIENT, false);
        when(booking.find(shared.id())).thenReturn(Optional.of(shared));

        assertThat(app.appointment(shared.id()).trust()).isNotNull();
    }

    @Test
    void theOwnerGetsTheTrustOfAHiddenVisit() {
        ActorContext.set(new ActorContext.Actor("o", ActorContext.Role.OWNER, null));
        AppointmentView hidden = visit(HIDDEN_CLIENT, true);
        when(booking.find(hidden.id())).thenReturn(Optional.of(hidden));

        assertThat(app.appointment(hidden.id()).trust()).isNotNull();
    }

    @Test
    void masterTodayAddsNeitherTrustNorPackagesToAHiddenVisit() {
        actAsMaster();
        AppointmentView hidden = visit(HIDDEN_CLIENT, true);
        AppointmentView shared = visit(SHARED_CLIENT, false);
        when(booking.calendar(any(), any(), eq(NINO))).thenReturn(List.of(hidden, shared));

        MasterTodayView today = app.masterToday();

        MasterTodayView.Item hiddenItem = today.appointments().get(0);
        assertThat(hiddenItem.trust()).isNull();
        assertThat(hiddenItem.packages()).isEmpty();
        MasterTodayView.Item sharedItem = today.appointments().get(1);
        assertThat(sharedItem.trust()).isNotNull();
        // Decision C: packages of a visible client stay in the master's today view (name and sessions; money is redacted).
        assertThat(sharedItem.packages()).hasSize(1);
        verify(retention, never()).assess(HIDDEN_CLIENT);
        verify(retention, never()).packagesForClient(HIDDEN_CLIENT);
    }

    @Test
    void cancelledVisitsCarryNoTrustOrPackagesForTheMaster() {
        // Review minor 2: a cancelled visit gives no access to its client, so no live trust or packages either.
        actAsMaster();
        AppointmentView gone = cancelled(SHARED_CLIENT);
        when(booking.find(gone.id())).thenReturn(Optional.of(gone));
        when(booking.calendar(any(), any(), eq(NINO))).thenReturn(List.of(gone));

        assertThat(app.appointment(gone.id()).trust()).isNull();
        MasterTodayView.Item item = app.masterToday().appointments().getFirst();
        assertThat(item.trust()).isNull();
        assertThat(item.packages()).isEmpty();
        verify(retention, never()).assess(any());
        verify(retention, never()).packagesForClient(any());
    }

    @Test
    void theWithheldMarkerIsNotAJsonProperty() {
        assertThat(keys(mapper.valueToTree(visit(SHARED_CLIENT, true)))).doesNotContain("clientWithheldFromMaster");
    }

    @Test
    void onlyTheOwnerWithdrawsAClientsConsentInTheConsole() {
        actAsMaster();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> app.withdrawConsent(SHARED_CLIENT))
                .isInstanceOfSatisfying(com.cadence.platform.error.DomainException.class,
                        ex -> assertThat(ex.status()).isEqualTo(403));
        verify(crm, never()).withdrawMarketingConsent(any(), any());

        ActorContext.set(new ActorContext.Actor("o", ActorContext.Role.OWNER, null));
        app.withdrawConsent(SHARED_CLIENT);
        verify(crm).withdrawMarketingConsent(SHARED_CLIENT, "owner");
    }

    @Test
    void theManageLinkWithdrawsConsentForItsOwnVisit() {
        AppointmentView view = visit(SHARED_CLIENT, false);
        when(booking.withdrawConsentByAccessToken(view.id(), "t")).thenReturn(view);

        JsonNode json = mapper.valueToTree(publicApi.withdrawConsent(view.id(), "t"));

        assertThat(keys(json)).containsExactlyInAnyOrder("id", "marketingConsent");
        assertThat(json.get("marketingConsent").asBoolean()).isFalse();
        verify(booking).withdrawConsentByAccessToken(view.id(), "t");
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        node.properties().forEach(p -> keys.add(p.getKey()));
        return keys;
    }

    @Test
    void publicVisitRoutesAnswerWithVisitFactsAndTheTypedNameOnly() {
        AppointmentView view = visit(SHARED_CLIENT, false);
        when(booking.findByAccessToken("t")).thenReturn(Optional.of(view));
        when(booking.cancelByAccessToken("t", true)).thenReturn(view);
        when(booking.rescheduleByAccessToken(eq("t"), any())).thenReturn(view);

        for (Object body : List.of(publicApi.booking(view.id(), "t"), publicApi.cancel(view.id(), "t"),
                publicApi.reschedule(view.id(), "t", view.serviceStart()))) {
            JsonNode json = mapper.valueToTree(body);
            assertThat(keys(json)).containsExactlyInAnyOrder("id", "specialistId", "serviceId", "status",
                    "serviceStart", "serviceEnd", "serviceNameSnapshot", "priceSnapshot", "currencyCode", "enteredName");
            assertThat(json.get("enteredName").asString()).isEqualTo("Typed");
            assertThat(json.toString()).doesNotContain("Stored Name").doesNotContain("995555000001").doesNotContain("LOW");
        }
    }
}

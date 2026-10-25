package com.cadence.app.web;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.app.web.dto.DashboardView;
import com.cadence.app.web.dto.MasterTodayView;
import com.cadence.catalog.api.ServiceView;
import com.cadence.crm.api.ClientView;
import com.cadence.retention.api.TrustView;
import com.cadence.platform.tenancy.ActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The central MASTER response policy: which responses it touches and what it does to them. */
class MasterViewPolicyTest {

    private static final UUID NINO = UUID.randomUUID();

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final MasterViewPolicy policy = new MasterViewPolicy(mapper);

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private static AppointmentView visit() {
        return new AppointmentView(
                UUID.randomUUID(), NINO, UUID.randomUUID(), UUID.randomUUID(), AppointmentStatus.COMPLETED,
                Instant.parse("2026-10-24T10:00:00Z"), Instant.parse("2026-10-24T11:00:00Z"),
                Instant.parse("2026-10-24T09:50:00Z"), Instant.parse("2026-10-24T11:10:00Z"),
                "Haircut", 8000, 60, 10, 10, "GEL", 500, 7500,
                "Client 1", "note", "CONSOLE", "Client 1", "+995555000001", "OK", false
        );
    }

    private static ServiceView service() {
        return new ServiceView(UUID.randomUUID(), "Haircut", "d", 60, 8000, 10, 10, "#000", true, true);
    }

    private Object write(Object body, String path, Class<?> converter) {
        return write(body, new MockHttpServletRequest("GET", path), converter);
    }

    private Object write(Object body, MockHttpServletRequest request, Class<?> converter) {
        return policy.beforeBodyWrite(
                body,
                (MethodParameter) null,
                MediaType.APPLICATION_JSON,
                (Class) converter,
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse())
        );
    }

    @Test
    void moneyKeysAreNulledAtAnyDepthAndOtherFieldsSurvive() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("history", List.of(visit()));
        body.put("services", List.of(service()));
        body.put("nested", Map.of("totalRevenue", 120, "packages", List.of(Map.of("valueMinor", 30000, "name", "5x"))));

        JsonNode redacted = MasterViewPolicy.redact(mapper.valueToTree(body));

        JsonNode visit = redacted.get("history").get(0);
        for (String money : List.of("priceSnapshot", "discountAmount", "amountReceived")) {
            assertThat(visit.has(money)).as("%s keeps its key", money).isTrue();
            assertThat(visit.get(money).isNull()).as("%s is null, never 0", money).isTrue();
        }
        assertThat(visit.get("durationSnapshot").asInt()).isEqualTo(60);
        assertThat(visit.get("bufferBeforeSnapshot").asInt()).isEqualTo(10);
        assertThat(visit.get("currencyCode").asString()).isEqualTo("GEL");
        assertThat(visit.get("clientPhone").asString()).isEqualTo("+995555000001");
        assertThat(visit.get("specialistId").asString()).isEqualTo(NINO.toString());

        JsonNode service = redacted.get("services").get(0);
        assertThat(service.get("priceMinor").isNull()).isTrue();
        assertThat(service.get("durationMinutes").asInt()).isEqualTo(60);

        assertThat(redacted.get("nested").get("totalRevenue").isNull()).isTrue();
        JsonNode pkg = redacted.get("nested").get("packages").get(0);
        assertThat(pkg.get("valueMinor").isNull()).isTrue();
        assertThat(pkg.get("name").asString()).isEqualTo("5x");
    }

    @Test
    void moneyKeyMatchIsCaseInsensitiveSubstring() {
        for (String key : List.of("price", "priceMinorOverride", "defaultPriceMinor", "AMOUNT", "completedValueMinor",
                "revenueMinor", "discountAmount", "valueMinor", "value")) {
            assertThat(MasterViewPolicy.isMoneyKey(key)).as(key).isTrue();
        }
        for (String key : List.of("durationMinutes", "minNoticeMinutes", "currencyCode", "clientId", "level",
                "bufferAfterMinutesOverride", "noShowCount")) {
            assertThat(MasterViewPolicy.isMoneyKey(key)).as(key).isFalse();
        }
    }

    @Test
    void masterResponseUnderAppApiIsRedacted() {
        ActorContext.set(new ActorContext.Actor("m", ActorContext.Role.MASTER, NINO));

        Object out = write(List.of(service()), "/api/app/services", JacksonJsonHttpMessageConverter.class);

        assertThat(out).isInstanceOf(JsonNode.class);
        assertThat(((JsonNode) out).get(0).get("priceMinor").isNull()).isTrue();
        assertThat(((JsonNode) out).get(0).get("name").asString()).isEqualTo("Haircut");
    }

    @Test
    void ownerResponseIsUntouched() {
        ActorContext.set(new ActorContext.Actor("o", ActorContext.Role.OWNER, null));
        List<ServiceView> body = List.of(service());

        assertThat(write(body, "/api/app/services", JacksonJsonHttpMessageConverter.class)).isSameAs(body);
    }

    @Test
    void anyNonOwnerOrMissingActorIsRedactedFailClosed() {
        ActorContext.clear();
        Object anonymous = write(List.of(service()), "/api/app/services", JacksonJsonHttpMessageConverter.class);
        assertThat(((JsonNode) anonymous).get(0).get("priceMinor").isNull()).isTrue();

        ActorContext.set(ActorContext.publicActor());
        Object publicActor = write(List.of(service()), "/api/app/services", JacksonJsonHttpMessageConverter.class);
        assertThat(((JsonNode) publicActor).get(0).get("priceMinor").isNull()).isTrue();
    }

    @Test
    void responsesOutsideAppApiAreUntouched() {
        ActorContext.set(new ActorContext.Actor("m", ActorContext.Role.MASTER, NINO));
        List<ServiceView> body = List.of(service());

        assertThat(write(body, "/api/public/tenants/lumen-studio/services", JacksonJsonHttpMessageConverter.class))
                .isSameAs(body);
        assertThat(write(body, "/api/application", JacksonJsonHttpMessageConverter.class)).isSameAs(body);
    }

    @Test
    void routeIsTakenFromTheDispatchedPatternNotTheRawUri() {
        ActorContext.set(new ActorContext.Actor("m", ActorContext.Role.MASTER, NINO));
        MockHttpServletRequest tricky = new MockHttpServletRequest("GET", "/api;x=1/app/services");
        tricky.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/app/services");

        Object out = write(List.of(service()), tricky, JacksonJsonHttpMessageConverter.class);

        assertThat(((JsonNode) out).get(0).get("priceMinor").isNull()).isTrue();
    }

    @Test
    void problemDetailsAndEmptyBodiesPassThrough() {
        ActorContext.set(new ActorContext.Actor("m", ActorContext.Role.MASTER, NINO));
        ProblemDetail problem = ProblemDetail.forStatus(403);

        assertThat(write(problem, "/api/app/rules", JacksonJsonHttpMessageConverter.class)).isSameAs(problem);
        assertThat(write(null, "/api/app/tasks/1/complete", JacksonJsonHttpMessageConverter.class)).isNull();
    }

    @Test
    void nonJsonConverterForAMasterIsRefusedRatherThanLeaking() {
        ActorContext.set(new ActorContext.Actor("m", ActorContext.Role.MASTER, NINO));

        assertThatThrownBy(() -> write("priceMinor=8000", "/api/app/services", StringHttpMessageConverter.class))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- TD-18: client fields a MASTER may see ----

    private static final String PHONE = "+995555000001";

    private static ClientView client(String displayName) {
        return new ClientView(UUID.randomUUID(), PHONE, displayName, "owner notes", "vip,late", "ru", "tg-42", true,
                Instant.parse("2025-01-10T10:00:00Z"), Instant.parse("2026-10-01T10:00:00Z"), 7, 2, 1, 56000);
    }

    private static TrustView trust() {
        return new TrustView(UUID.randomUUID(), "LOW", List.of("2 no-shows recorded"), "LOW", "owner free text about the client", List.of("NO_SHOWS", "OWNER_OVERRIDE"));
    }

    private JsonNode asMaster(Object body, String path) {
        ActorContext.set(new ActorContext.Actor("m", ActorContext.Role.MASTER, NINO));
        return (JsonNode) write(body, path, JacksonJsonHttpMessageConverter.class);
    }

    private static Set<String> keysWithValues(JsonNode object) {
        Set<String> keys = new TreeSet<>();
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            if (!property.getValue().isNull()) {
                keys.add(property.getKey());
            }
        }
        return keys;
    }

    @Test
    void masterClientCardKeepsOnlyIdAndNameOfTheClientAndKeysStay() {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("client", client("Mariam K."));
        card.put("trust", trust());
        card.put("history", List.of(visit()));

        JsonNode out = asMaster(card, "/api/app/clients/42");

        JsonNode c = out.get("client");
        assertThat(keysWithValues(c)).containsExactlyInAnyOrder("id", "displayName");
        assertThat(c.get("displayName").asString()).isEqualTo("Mariam K.");
        for (String hidden : List.of("normalizedPhone", "notes", "tags", "preferredLocale", "telegramChatId",
                "marketingConsent", "firstVisitAt", "lastVisitAt", "completedVisitCount", "noShowCount",
                "lateCancelCount", "completedValueMinor")) {
            assertThat(c.has(hidden)).as("%s keeps its key", hidden).isTrue();
            assertThat(c.get(hidden).isNull()).as("%s is hidden", hidden).isTrue();
        }
        JsonNode t = out.get("trust");
        assertThat(t.get("level").asString()).isEqualTo("LOW");
        assertThat(t.get("reasons").isNull()).as("texts carry salon-wide counters (decision B)").isTrue();
        assertThat(t.get("reasonCodes").get(0).asString()).isEqualTo("NO_SHOWS");
    }

    @Test
    void masterTrustKeepsLevelOverrideLevelAndCodesButNoTextsAndNoOwnerReason() {
        // Decision B: level + whether there is an override (its level) + number-free codes; never the counted texts
        // nor the owner's free-text override reason. Applied by type, wherever the trust sits in a response.
        JsonNode t = asMaster(Map.of("trust", trust()), "/api/app/appointments/1").get("trust");

        assertThat(t.get("level").asString()).isEqualTo("LOW");
        assertThat(t.get("overrideLevel").asString()).isEqualTo("LOW");
        assertThat(t.get("reasonCodes").toString()).isEqualTo("[\"NO_SHOWS\",\"OWNER_OVERRIDE\"]");
        assertThat(t.has("reasons")).as("key kept").isTrue();
        assertThat(t.get("reasons").isNull()).isTrue();
        assertThat(t.has("overrideReason")).as("key kept").isTrue();
        assertThat(t.get("overrideReason").isNull()).isTrue();
        assertThat(t.toString()).doesNotContain("2 no-shows").doesNotContain("owner free text");

        MasterTodayView today = new MasterTodayView(Instant.now(), LocalDate.parse("2026-10-24"), NINO,
                List.of(new MasterTodayView.Item(visit(), trust(), List.of())));
        JsonNode nested = asMaster(today, "/api/app/masters/me/today").get("appointments").get(0).get("trust");
        assertThat(nested.get("reasons").isNull()).isTrue();
        assertThat(nested.get("overrideReason").isNull()).isTrue();
    }

    @Test
    void ownerTrustKeepsTheTextsAndTheOverrideReason() {
        ActorContext.set(new ActorContext.Actor("o", ActorContext.Role.OWNER, null));
        Map<String, Object> body = Map.of("trust", trust());

        assertThat(write(body, "/api/app/clients/42", JacksonJsonHttpMessageConverter.class)).isSameAs(body);
    }

    @Test
    void masterSeesWhetherAVisitIsHiddenUntilTheOwnerConfirms() {
        AppointmentView v = visit();
        AppointmentView hidden = new AppointmentView(v.id(), v.specialistId(), v.serviceId(), v.clientId(),
                AppointmentStatus.PENDING, v.serviceStart(), v.serviceEnd(), v.occupiedStart(), v.occupiedEnd(),
                v.serviceNameSnapshot(), v.priceSnapshot(), v.durationSnapshot(), v.bufferBeforeSnapshot(),
                v.bufferAfterSnapshot(), v.currencyCode(), v.discountAmount(), v.amountReceived(), "Typed",
                v.note(), v.source(), "Typed", null, null, true);

        JsonNode out = asMaster(List.of(hidden), "/api/app/calendar").get(0);

        assertThat(out.get("clientHiddenFromMaster").asBoolean()).isTrue();
        assertThat(out.get("clientDisplayName").asString()).isEqualTo("Typed");
    }

    @Test
    void aNameWithTheLocalDigitsOfThePhoneIsHidden() {
        // "+995555000001" written locally inside a name: a 9-digit run, and the end of the client's phone.
        JsonNode clients = asMaster(List.of(client("Mariam 555 00 00 01"), client("Mariam 5-00-00-01")), "/api/app/clients");

        assertThat(clients.get(0).get("displayName").isNull()).isTrue();
        assertThat(clients.get(1).get("displayName").isNull()).as("7-digit suffix of the phone").isTrue();
    }

    @Test
    void masterVisitsCarryNoClientPhoneNorEnteredNameButKeepTheVisit() {
        JsonNode out = asMaster(List.of(visit()), "/api/app/calendar");

        JsonNode v = out.get(0);
        assertThat(v.has("clientPhone")).isTrue();
        assertThat(v.get("clientPhone").isNull()).isTrue();
        assertThat(v.get("enteredName").isNull()).as("typed name may carry a stored name or a phone").isTrue();
        assertThat(v.get("clientDisplayName").asString()).isEqualTo("Client 1");
        assertThat(v.get("trustLevel").asString()).isEqualTo("OK");
        assertThat(v.get("note").asString()).isEqualTo("note");
        assertThat(v.get("durationSnapshot").asInt()).isEqualTo(60);
        assertThat(v.get("serviceStart").isNull()).isFalse();
        assertThat(v.get("priceSnapshot").isNull()).as("money policy still applies").isTrue();
    }

    @Test
    void theAllowListReachesVisitsAndClientsAtAnyDepth() {
        MasterTodayView today = new MasterTodayView(Instant.now(), LocalDate.parse("2026-10-24"), NINO,
                List.of(new MasterTodayView.Item(visit(), trust(), List.of())));
        DashboardView dashboard = new DashboardView(Instant.now(), List.of(visit()), List.of(), List.of(), List.of());
        Map<String, Object> nested = Map.of("clients", List.of(client("Nested")));

        assertThat(asMaster(today, "/api/app/masters/me/today").get("appointments").get(0).get("appointment")
                .get("clientPhone").isNull()).isTrue();
        assertThat(asMaster(dashboard, "/api/app/dashboard").get("today").get(0).get("clientPhone").isNull()).isTrue();
        assertThat(keysWithValues(asMaster(nested, "/api/app/clients").get("clients").get(0)))
                .containsExactlyInAnyOrder("id", "displayName");
    }

    @Test
    void aNameThatIsThePhoneIsHiddenToo() {
        // A client created without a name is stored with the phone as the name.
        JsonNode clients = asMaster(List.of(client(PHONE), client("Mariam 995 555 000 001")), "/api/app/clients");
        assertThat(clients.get(0).get("displayName").isNull()).isTrue();
        assertThat(clients.get(1).get("displayName").isNull()).as("phone digits in any formatting").isTrue();

        AppointmentView named = visit();
        AppointmentView phoneNamed = new AppointmentView(named.id(), named.specialistId(), named.serviceId(),
                named.clientId(), named.status(), named.serviceStart(), named.serviceEnd(), named.occupiedStart(),
                named.occupiedEnd(), named.serviceNameSnapshot(), named.priceSnapshot(), named.durationSnapshot(),
                named.bufferBeforeSnapshot(), named.bufferAfterSnapshot(), named.currencyCode(), named.discountAmount(),
                named.amountReceived(), PHONE, named.note(), named.source(), PHONE, PHONE, named.trustLevel(), false);
        JsonNode visits = asMaster(List.of(phoneNamed), "/api/app/calendar");
        assertThat(visits.get(0).get("clientDisplayName").isNull()).isTrue();
        assertThat(visits.get(0).toString()).doesNotContain("995555000001");
    }

    @Test
    void ownerStillGetsTheFullClientAndVisit() {
        ActorContext.set(new ActorContext.Actor("o", ActorContext.Role.OWNER, null));
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("client", client(PHONE));
        card.put("history", List.of(visit()));

        assertThat(write(card, "/api/app/clients/42", JacksonJsonHttpMessageConverter.class)).isSameAs(card);
    }
}

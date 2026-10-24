package com.cadence.app;

import com.cadence.app.seed.DemoIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static com.cadence.app.TestJwt.master;
import static com.cadence.app.TestJwt.owner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * PRODUCT_PLAN §5 for MASTER over the whole {@code /api/app/**} surface: walks every GET handler registered in
 * {@link RequestMappingHandlerMapping} as master Nino with real ids (own and foreign specialist, own and foreign
 * visit, a client with and one without a shared visit). Every 2xx JSON body must carry no money-like key with a value;
 * per-resource refusals must be 403. A GET route without a sample below fails the walk, so a new endpoint cannot
 * reach masters unchecked.
 *
 * <p>The key-name check shares its word list with {@code MasterViewPolicy}, so it cannot catch a money field named
 * unlike money. A second, value-based check closes that gap: the money numbers of the seeded tenant are read as the
 * OWNER (service prices, matrix overrides, visit price snapshots, client lifetime value, package values) and none of
 * them may appear as a number anywhere in any master response, whatever the field is called.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RelaxedPublicRateLimits
class MasterApiExposureIT extends AbstractTimescaleIT {

    /** Same rule as {@code MasterViewPolicy}: any key containing one of these words is money. */
    private static final Pattern MONEY_KEY = Pattern.compile("price|amount|value|revenue|discount|minor",
            Pattern.CASE_INSENSITIVE);
    /** Owner-side fields that hold money; they only feed the set of values that must never reach a master. */
    private static final Set<String> OWNER_MONEY_FIELDS = Set.of("priceMinor", "defaultPriceMinor", "priceMinorOverride",
            "priceSnapshot", "discountAmount", "amountReceived", "completedValueMinor", "valueMinor");
    /**
     * Money values below this many minor units are ignored by the value scan: durations in minutes, counts, buffers
     * and similar small integers legitimately appear in master responses and would give false positives.
     */
    private static final long MIN_SCANNED_MONEY = 1000;
    /** A package value of its own, so the scan has a distinctive number that no catalog price shares. */
    private static final int OWN_PACKAGE_VALUE = 73_210;
    private static final ZoneId TBILISI = ZoneId.of("Asia/Tbilisi");
    /** Far from the demo seed (-80..+15 days) and from ClientMergeIT (+300 days): each visit gets its own 3 hours. */
    private static final Instant BASE = Instant.now().plus(600, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
    private static final AtomicLong SLOT = new AtomicLong();

    private static Fixture fixture;

    @Autowired
    MockMvc mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    /**
     * @param sharedClient  has a COMPLETED visit with Nino (and a package), so its card is in Nino's scope
     * @param foreignClient has only a visit with Anna
     */
    private record Fixture(String ownVisit, String foreignVisit, String sharedClient, String foreignClient,
                           int packageValue) {
    }

    private record Sample(String url, int status) {
    }

    private static RequestPostProcessor nino() {
        return master(DemoIds.LUMEN, DemoIds.NINO);
    }

    private static RequestPostProcessor lumenOwner() {
        return owner(DemoIds.LUMEN);
    }

    @BeforeEach
    void fixture() throws Exception {
        if (fixture != null) {
            return;
        }
        JsonNode own = createVisit(lumenOwner(), DemoIds.NINO, nextStart(), "\"phone\": \"" + phone() + "\"", 200);
        String sharedClient = own.get("clientId").asText();
        transition(lumenOwner(), own.get("id").asText(), "COMPLETED");
        JsonNode foreign = createVisit(lumenOwner(), DemoIds.ANNA, nextStart(), "\"phone\": \"" + phone() + "\"", 200);
        MvcResult sold = mvc.perform(post("/api/app/clients/" + sharedClient + "/packages").with(lumenOwner())
                        .param("name", "5 haircuts").param("sessions", "5").param("valueMinor", "35000"))
                .andReturn();
        assertThat(sold.getResponse().getStatus()).as("sell package: %s", sold.getResponse().getContentAsString())
                .isEqualTo(200);
        createTodayVisit(sharedClient);
        fixture = new Fixture(own.get("id").asText(), foreign.get("id").asText(), sharedClient,
                foreign.get("clientId").asText(), 35_000);
    }

    /** GET route pattern -> requests to make as Nino and the status each must get. */
    private Map<String, List<Sample>> samples() {
        Fixture f = fixture;
        String from = Instant.now().minus(400, ChronoUnit.DAYS).toString();
        String to = BASE.plus(30, ChronoUnit.DAYS).toString();
        Map<String, List<Sample>> m = new LinkedHashMap<>();
        m.put("/api/app/health", List.of(new Sample("/api/app/health", 200)));
        m.put("/api/app/me", List.of(new Sample("/api/app/me", 200)));
        m.put("/api/app/dashboard", List.of(new Sample("/api/app/dashboard", 200)));
        m.put("/api/app/masters/me/today", List.of(new Sample("/api/app/masters/me/today", 200)));
        m.put("/api/app/services", List.of(new Sample("/api/app/services", 200)));
        m.put("/api/app/specialists", List.of(new Sample("/api/app/specialists", 200)));
        m.put("/api/app/specialists/{id}/matrix", List.of(
                new Sample("/api/app/specialists/" + DemoIds.NINO + "/matrix", 200),
                new Sample("/api/app/specialists/" + DemoIds.ANNA + "/matrix", 403)));
        m.put("/api/app/specialists/{id}/schedule", List.of(
                new Sample("/api/app/specialists/" + DemoIds.NINO + "/schedule", 200),
                new Sample("/api/app/specialists/" + DemoIds.ANNA + "/schedule", 403)));
        m.put("/api/app/rules", List.of(new Sample("/api/app/rules", 403)));
        m.put("/api/app/calendar", List.of(
                new Sample("/api/app/calendar?from=" + from + "&to=" + to, 200),
                new Sample("/api/app/calendar?from=" + from + "&to=" + to + "&specialistId=" + DemoIds.ANNA, 200)));
        m.put("/api/app/appointments/{id}", List.of(
                new Sample("/api/app/appointments/" + f.ownVisit(), 200),
                new Sample("/api/app/appointments/" + f.foreignVisit(), 403)));
        m.put("/api/app/clients", List.of(new Sample("/api/app/clients", 200)));
        m.put("/api/app/clients/{id}", List.of(
                new Sample("/api/app/clients/" + f.sharedClient(), 200),
                new Sample("/api/app/clients/" + f.foreignClient(), 403)));
        m.put("/api/app/tasks", List.of(new Sample("/api/app/tasks", 403)));
        m.put("/api/app/metrics", List.of(new Sample("/api/app/metrics", 403)));
        m.put("/api/app/metrics/definitions", List.of(new Sample("/api/app/metrics/definitions", 403)));
        m.put("/api/app/signals", List.of(new Sample("/api/app/signals", 403)));
        m.put("/api/app/ai/conversations/{id}", List.of(new Sample("/api/app/ai/conversations/" + UUID.randomUUID(), 403)));
        m.put("/api/app/ai/drafts", List.of(new Sample("/api/app/ai/drafts", 403)));
        return m;
    }

    private Set<String> registeredAppGetRoutes() {
        Set<String> routes = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            if (!methods.isEmpty() && !methods.contains(RequestMethod.GET)) {
                continue;
            }
            for (String pattern : info.getPatternValues()) {
                if (pattern.equals("/api/app") || pattern.startsWith("/api/app/")) {
                    routes.add(pattern);
                }
            }
        }
        return routes;
    }

    @Test
    void everyAppGetRouteHasAMasterSample() {
        Set<String> registered = registeredAppGetRoutes();
        assertThat(registered).as("handler mapping lists the console GET routes").contains("/api/app/services");
        assertThat(samples().keySet())
                .as("every GET route under /api/app needs a MASTER sample here (add one for a new endpoint)")
                .containsAll(registered);
        assertThat(registered)
                .as("samples must not name routes that no longer exist")
                .containsAll(samples().keySet());
    }

    @Test
    void masterGetsNoMoneyAndOnlyOwnResourcesOnEveryGetRoute() throws Exception {
        Set<Long> money = ownerMoneyValues(fixture.packageValue());
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, List<Sample>> route : samples().entrySet()) {
            for (Sample sample : route.getValue()) {
                MvcResult res = mvc.perform(get(sample.url()).with(nino())).andReturn();
                int status = res.getResponse().getStatus();
                if (status != sample.status()) {
                    failures.add(sample.url() + " -> " + status + " (expected " + sample.status() + ") "
                            + res.getResponse().getContentAsString());
                    continue;
                }
                if (status == 403) {
                    JsonNode problem = mapper.readTree(res.getResponse().getContentAsByteArray());
                    if (!"FORBIDDEN_RESOURCE".equals(problem.path("code").asText())) {
                        failures.add(sample.url() + " -> 403 with code " + problem.path("code").asText());
                    }
                }
                if (status / 100 == 2 && res.getResponse().getContentAsByteArray().length > 0) {
                    JsonNode body = mapper.readTree(res.getResponse().getContentAsByteArray());
                    List<String> leaks = new ArrayList<>();
                    collectMoney(body, "$", leaks);
                    leaks.forEach(leak -> failures.add(sample.url() + " leaks " + leak));
                    List<String> valueLeaks = new ArrayList<>();
                    collectMoneyValues(body, "$", money, valueLeaks);
                    valueLeaks.forEach(leak -> failures.add(sample.url() + " carries an owner money value " + leak));
                }
            }
        }
        assertThat(failures).as("MASTER exposure on /api/app GET routes").isEmpty();
    }

    @Test
    void masterSeesMoneyKeysAsNullNotAbsentOrZero() throws Exception {
        JsonNode services = getJson(nino(), "/api/app/services");
        assertThat(services.size()).isGreaterThan(0);
        services.forEach(s -> assertThat(s.has("priceMinor") && s.get("priceMinor").isNull()).as(s.toString()).isTrue());

        JsonNode visit = getJson(nino(), "/api/app/appointments/" + fixture.ownVisit()).get("appointment");
        assertThat(visit.get("specialistId").asText()).isEqualTo(DemoIds.NINO.toString());
        assertThat(visit.get("priceSnapshot").isNull()).isTrue();
        assertThat(visit.get("discountAmount").isNull()).isTrue();
        assertThat(visit.get("durationSnapshot").asInt()).isGreaterThan(0);

        JsonNode card = getJson(nino(), "/api/app/clients/" + fixture.sharedClient());
        assertThat(card.get("client").get("completedValueMinor").isNull()).isTrue();
        assertThat(card.get("client").get("completedVisitCount").asInt()).isGreaterThan(0);
        assertThat(card.get("history").size()).isGreaterThan(0);
        card.get("history").forEach(h -> {
            assertThat(h.get("specialistId").asText()).as("history only with this master").isEqualTo(DemoIds.NINO.toString());
            assertThat(h.get("priceSnapshot").isNull()).isTrue();
        });

        JsonNode today = getJson(nino(), "/api/app/masters/me/today");
        List<JsonNode> packages = new ArrayList<>();
        today.get("appointments").forEach(item -> item.get("packages").forEach(packages::add));
        assertThat(packages).as("today's visit of the shared client carries its package").isNotEmpty();
        packages.forEach(p -> assertThat(p.get("valueMinor").isNull()).isTrue());

        JsonNode matrix = getJson(nino(), "/api/app/specialists/" + DemoIds.NINO + "/matrix");
        assertThat(matrix.size()).isGreaterThan(0);
        matrix.forEach(row -> {
            assertThat(row.get("defaultPriceMinor").isNull()).isTrue();
            assertThat(row.get("priceMinorOverride").isNull()).isTrue();
            assertThat(row.get("defaultDurationMinutes").asInt()).isGreaterThan(0);
        });
    }

    @Test
    void masterClientListAndCalendarStayInOwnScope() throws Exception {
        JsonNode clients = getJson(nino(), "/api/app/clients");
        List<String> ids = new ArrayList<>();
        clients.forEach(c -> ids.add(c.get("id").asText()));
        assertThat(ids).contains(fixture.sharedClient()).doesNotContain(fixture.foreignClient());

        String from = BASE.minus(1, ChronoUnit.DAYS).toString();
        String to = BASE.plus(30, ChronoUnit.DAYS).toString();
        JsonNode calendar = getJson(nino(), "/api/app/calendar?from=" + from + "&to=" + to + "&specialistId=" + DemoIds.ANNA);
        assertThat(calendar.size()).isGreaterThan(0);
        calendar.forEach(v -> assertThat(v.get("specialistId").asText()).isEqualTo(DemoIds.NINO.toString()));
    }

    @Test
    void ownerMoneyValueSetIsNonTrivialAndTheScannerFindsThemInOwnerResponses() throws Exception {
        Set<Long> money = ownerMoneyValues(fixture.packageValue());

        assertThat(money).as("known money values of the seeded tenant").isNotEmpty();
        assertThat(money).contains((long) fixture.packageValue());
        JsonNode services = getJson(lumenOwner(), "/api/app/services");
        for (JsonNode service : services) {
            long price = service.get("priceMinor").asLong();
            if (price >= MIN_SCANNED_MONEY) {
                assertThat(money).as("price of %s", service.get("name")).contains(price);
            }
        }
        // Positive control: the same scan over the OWNER's own responses must report hits, otherwise it scans nothing.
        List<String> hits = new ArrayList<>();
        collectMoneyValues(services, "$", money, hits);
        assertThat(hits).as("scanner finds the prices in the owner's service list").isNotEmpty();
        List<String> clientHits = new ArrayList<>();
        collectMoneyValues(getJson(lumenOwner(), "/api/app/clients/" + fixture.sharedClient()), "$", money, clientHits);
        assertThat(clientHits).as("scanner finds the lifetime value in the owner's client card").isNotEmpty();
    }

    @Test
    void masterCannotConfirmAPendingVisitOfAClientWhoNeedsTheOwnersConfirmation() throws Exception {
        // An owner-marked LOW-trust client always starts PENDING; the master may not skip that through the transition.
        JsonNode first = createVisit(lumenOwner(), DemoIds.NINO, nextStart(), "\"phone\": \"" + phone() + "\"", 200);
        String clientId = first.get("clientId").asText();
        MvcResult trust = mvc.perform(post("/api/app/clients/" + clientId + "/trust").with(lumenOwner())
                .param("level", "LOW").param("reason", "exposure test")).andReturn();
        assertThat(trust.getResponse().getStatus()).as("override trust: %s", trust.getResponse().getContentAsString()).isEqualTo(200);

        String slot = TestSlots.firstFree(mvc, mapper, DemoIds.HAIRCUT, DemoIds.NINO, 1);
        MvcResult created = mvc.perform(post("/api/app/appointments").with(nino())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"specialistId": "%s", "serviceId": "%s", "serviceStart": "%s", "clientId": "%s",
                                 "marketingConsent": false, "source": "CONSOLE", "skipNoticeAndHorizon": false}
                                """.formatted(DemoIds.NINO, DemoIds.HAIRCUT, slot, clientId)))
                .andReturn();
        assertThat(created.getResponse().getStatus()).as("master create: %s", created.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode visit = mapper.readTree(created.getResponse().getContentAsByteArray());
        assertThat(visit.get("status").asText()).isEqualTo("PENDING");
        String visitId = visit.get("id").asText();

        MvcResult refused = mvc.perform(post("/api/app/appointments/" + visitId + "/transition").with(nino())
                .param("to", "CONFIRMED")).andReturn();
        assertThat(refused.getResponse().getStatus()).as("master confirm: %s", refused.getResponse().getContentAsString()).isEqualTo(403);
        assertThat(mapper.readTree(refused.getResponse().getContentAsByteArray()).path("code").asText()).isEqualTo("FORBIDDEN_RESOURCE");
        assertThat(getJson(lumenOwner(), "/api/app/appointments/" + visitId).get("appointment").get("status").asText())
                .as("still pending after the refused confirm").isEqualTo("PENDING");

        assertThat(transition(lumenOwner(), visitId, "CONFIRMED").get("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void ownerStillSeesMoney() throws Exception {
        JsonNode services = getJson(lumenOwner(), "/api/app/services");
        services.forEach(s -> assertThat(s.get("priceMinor").isNumber()).as(s.toString()).isTrue());

        JsonNode visit = getJson(lumenOwner(), "/api/app/appointments/" + fixture.ownVisit()).get("appointment");
        assertThat(visit.get("priceSnapshot").asInt()).isGreaterThan(0);
        assertThat(visit.get("discountAmount").isNumber()).isTrue();

        JsonNode card = getJson(lumenOwner(), "/api/app/clients/" + fixture.sharedClient());
        assertThat(card.get("client").get("completedValueMinor").asInt()).isGreaterThan(0);

        JsonNode foreignCard = getJson(lumenOwner(), "/api/app/clients/" + fixture.foreignClient());
        assertThat(foreignCard.get("history").size()).isGreaterThan(0);

        JsonNode matrix = getJson(lumenOwner(), "/api/app/specialists/" + DemoIds.ANNA + "/matrix");
        matrix.forEach(row -> assertThat(row.get("defaultPriceMinor").isNumber()).isTrue());

        assertThat(getJson(lumenOwner(), "/api/app/rules").get("slotStepMinutes").isNumber()).isTrue();
        assertThat(getJson(lumenOwner(), "/api/app/specialists/" + DemoIds.ANNA + "/schedule").size()).isGreaterThan(0);
    }

    @Test
    void masterWritesStayOnOwnVisitsAndNeverTouchPrices() throws Exception {
        // A master books like the public flow: a real free slot, status from the rules, no switches.
        String slot = TestSlots.firstFree(mvc, mapper, DemoIds.HAIRCUT, DemoIds.NINO, 1);
        MvcResult normal = mvc.perform(post("/api/app/appointments").with(nino())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"specialistId": "%s", "serviceId": "%s", "serviceStart": "%s", "clientId": "%s",
                                 "marketingConsent": false, "source": "CONSOLE", "skipNoticeAndHorizon": false}
                                """.formatted(DemoIds.NINO, DemoIds.HAIRCUT, slot, fixture.sharedClient())))
                .andReturn();
        assertThat(normal.getResponse().getStatus()).as("master create: %s", normal.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode created = mapper.readTree(normal.getResponse().getContentAsByteArray());
        assertThat(created.get("status").asText()).isIn("PENDING", "CONFIRMED");
        assertThat(created.get("priceSnapshot").isNull()).as("write responses are redacted too").isTrue();

        // No rule bypass and no visit born settled: both switches are owner/system-only.
        createVisit(nino(), DemoIds.NINO, nextStart(), "\"clientId\": \"" + fixture.sharedClient() + "\"", 403);
        assertStatus(post("/api/app/appointments").contentType(MediaType.APPLICATION_JSON).content("""
                {"specialistId": "%s", "serviceId": "%s", "serviceStart": "%s", "clientId": "%s",
                 "marketingConsent": false, "source": "CONSOLE", "statusOverride": "COMPLETED",
                 "skipNoticeAndHorizon": false}
                """.formatted(DemoIds.NINO, DemoIds.HAIRCUT, slot, fixture.sharedClient())), 403);
        assertStatus(post("/api/app/appointments").contentType(MediaType.APPLICATION_JSON).content("""
                {"specialistId": "%s", "serviceId": "%s", "serviceStart": "%s", "clientId": "%s",
                 "marketingConsent": false, "source": "CONSOLE", "skipNoticeAndHorizon": true}
                """.formatted(DemoIds.NINO, DemoIds.HAIRCUT, nextStart(), fixture.sharedClient())), 403);

        if ("PENDING".equals(created.get("status").asText())) {
            // The master may not confirm a client who needs the owner's confirmation; the owner does.
            MvcResult refused = mvc.perform(post("/api/app/appointments/" + created.get("id").asText() + "/transition")
                    .with(nino()).param("to", "CONFIRMED")).andReturn();
            assertThat(refused.getResponse().getStatus()).as("master confirm: %s", refused.getResponse().getContentAsString()).isEqualTo(403);
            transition(lumenOwner(), created.get("id").asText(), "CONFIRMED");
        }
        JsonNode completed = transition(nino(), created.get("id").asText(), "COMPLETED");
        assertThat(completed.get("priceSnapshot").isNull()).isTrue();

        // The write responses are scanned by value too, with a package sold here as an extra known amount.
        MvcResult sold = mvc.perform(post("/api/app/clients/" + fixture.sharedClient() + "/packages").with(lumenOwner())
                .param("name", "10 haircuts").param("sessions", "10").param("valueMinor", String.valueOf(OWN_PACKAGE_VALUE))).andReturn();
        assertThat(sold.getResponse().getStatus()).as("sell package: %s", sold.getResponse().getContentAsString()).isEqualTo(200);
        Set<Long> money = ownerMoneyValues(fixture.packageValue(), OWN_PACKAGE_VALUE);
        assertThat(money).contains((long) OWN_PACKAGE_VALUE);
        for (JsonNode body : List.of(created, completed,
                getJson(nino(), "/api/app/masters/me/today"),
                getJson(nino(), "/api/app/clients/" + fixture.sharedClient()))) {
            List<String> valueLeaks = new ArrayList<>();
            collectMoneyValues(body, "$", money, valueLeaks);
            assertThat(valueLeaks).as("owner money values in a master response").isEmpty();
        }

        // Another master's calendar: refused on ownership alone, without any switch in the command.
        assertStatus(post("/api/app/appointments").contentType(MediaType.APPLICATION_JSON).content("""
                {"specialistId": "%s", "serviceId": "%s", "serviceStart": "%s", "clientId": "%s",
                 "marketingConsent": false, "source": "CONSOLE", "skipNoticeAndHorizon": false}
                """.formatted(DemoIds.ANNA, DemoIds.HAIRCUT, slot, fixture.sharedClient())), 403);
        assertStatus(post("/api/app/appointments/" + fixture.foreignVisit() + "/transition").param("to", "CANCELLED_BY_BUSINESS"), 403);
        assertStatus(post("/api/app/appointments/" + fixture.foreignVisit() + "/reschedule").param("start", nextStart().toString()), 403);

        assertStatus(put("/api/app/services").contentType(MediaType.APPLICATION_JSON).content("""
                {"id": "%s", "name": "Haircut", "durationMinutes": 60, "priceMinor": 1, "bufferBeforeMinutes": 0,
                 "bufferAfterMinutes": 0, "color": "#000000", "active": true, "publicVisible": true}
                """.formatted(DemoIds.HAIRCUT)), 403);
        assertStatus(put("/api/app/specialists/" + DemoIds.NINO + "/matrix").contentType(MediaType.APPLICATION_JSON).content("""
                {"serviceId": "%s", "offered": true, "priceMinorOverride": 1}
                """.formatted(DemoIds.HAIRCUT)), 403);
        assertStatus(put("/api/app/specialists/" + DemoIds.NINO + "/schedule").contentType(MediaType.APPLICATION_JSON).content("[]"), 403);
        assertStatus(put("/api/app/rules").contentType(MediaType.APPLICATION_JSON).content("""
                {"slotStepMinutes": 15, "minNoticeMinutes": 0, "horizonDays": 365, "clientRescheduleAllowed": true,
                 "lateCancellationHours": 0, "newClientRequiresConfirmation": false, "deductPackageOnNoShow": false,
                 "weekStartsOn": 1, "timeFormat24h": true}
                """), 403);
        assertStatus(post("/api/app/clients/" + fixture.sharedClient() + "/packages")
                .param("name", "x").param("sessions", "1").param("valueMinor", "1"), 403);

        JsonNode haircut = null;
        for (JsonNode s : getJson(lumenOwner(), "/api/app/services")) {
            if (s.get("id").asText().equals(DemoIds.HAIRCUT.toString())) {
                haircut = s;
            }
        }
        assertThat(haircut).isNotNull();
        assertThat(haircut.get("priceMinor").asInt()).as("price unchanged by the refused master write").isGreaterThan(1);
    }

    // ---- helpers ----

    private static void collectMoney(JsonNode node, String path, List<String> leaks) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String childPath = path + "." + field.getKey();
                if (MONEY_KEY.matcher(field.getKey()).find() && !field.getValue().isNull()) {
                    leaks.add(childPath + "=" + field.getValue());
                } else {
                    collectMoney(field.getValue(), childPath, leaks);
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectMoney(node.get(i), path + "[" + i + "]", leaks);
            }
        }
    }

    /**
     * Every JSON number in {@code node} whose whole value is one of {@code money}. Looks at values only, never at
     * field names, so a money field called anything at all is caught.
     */
    private static void collectMoneyValues(JsonNode node, String path, Set<Long> money, List<String> hits) {
        if (node.isNumber()) {
            BigDecimal value = node.decimalValue().stripTrailingZeros();
            if (value.signum() > 0 && value.scale() <= 0 && value.precision() - value.scale() <= 18
                    && money.contains(value.longValue())) {
                hits.add(path + "=" + node);
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(f -> collectMoneyValues(f.getValue(), path + "." + f.getKey(), money, hits));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectMoneyValues(node.get(i), path + "[" + i + "]", money, hits);
            }
        }
    }

    /**
     * The money numbers of the seeded tenant as the OWNER sees them: service prices, matrix overrides, visit price
     * snapshots (calendar and client cards), client lifetime values, plus {@code extra} (packages sold by the test).
     * Values below {@link #MIN_SCANNED_MONEY} are dropped (see there). Fails when nothing is collected.
     */
    private Set<Long> ownerMoneyValues(long... extra) throws Exception {
        Set<Long> values = new HashSet<>();
        for (long value : extra) {
            values.add(value);
        }
        collectOwnerMoney(getJson(lumenOwner(), "/api/app/services"), values);
        for (JsonNode specialist : getJson(lumenOwner(), "/api/app/specialists")) {
            collectOwnerMoney(getJson(lumenOwner(), "/api/app/specialists/" + specialist.get("id").asText() + "/matrix"), values);
        }
        String from = Instant.now().minus(400, ChronoUnit.DAYS).toString();
        String to = BASE.plus(30, ChronoUnit.DAYS).toString();
        collectOwnerMoney(getJson(lumenOwner(), "/api/app/calendar?from=" + from + "&to=" + to), values);
        collectOwnerMoney(getJson(lumenOwner(), "/api/app/clients"), values);
        collectOwnerMoney(getJson(lumenOwner(), "/api/app/clients/" + fixture.sharedClient()), values);
        collectOwnerMoney(getJson(lumenOwner(), "/api/app/clients/" + fixture.foreignClient()), values);
        values.removeIf(v -> v < MIN_SCANNED_MONEY);
        assertThat(values).as("owner money values of the seeded tenant (must not be empty)").isNotEmpty();
        return values;
    }

    private static void collectOwnerMoney(JsonNode node, Set<Long> values) {
        if (node.isObject()) {
            node.fields().forEachRemaining(f -> {
                if (OWNER_MONEY_FIELDS.contains(f.getKey()) && f.getValue().isNumber()) {
                    values.add(f.getValue().asLong());
                } else {
                    collectOwnerMoney(f.getValue(), values);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectOwnerMoney(child, values));
        }
    }

    private JsonNode getJson(RequestPostProcessor who, String url) throws Exception {
        MvcResult res = mvc.perform(get(url).with(who)).andReturn();
        assertThat(res.getResponse().getStatus()).as("GET %s: %s", url, res.getResponse().getContentAsString()).isEqualTo(200);
        return mapper.readTree(res.getResponse().getContentAsByteArray());
    }

    private void assertStatus(MockHttpServletRequestBuilder request, int expected) throws Exception {
        MvcResult res = mvc.perform(request.with(nino())).andReturn();
        assertThat(res.getResponse().getStatus()).as("%s %s: %s", res.getRequest().getMethod(),
                res.getRequest().getRequestURI(), res.getResponse().getContentAsString()).isEqualTo(expected);
    }

    private static Instant nextStart() {
        return BASE.plus(3 * SLOT.getAndIncrement(), ChronoUnit.HOURS);
    }

    private static String phone() {
        return "+9955557" + String.format("%05d", (int) (Math.random() * 100_000));
    }

    private JsonNode createVisit(RequestPostProcessor who, UUID specialist, Instant start, String clientJson, int expected)
            throws Exception {
        MvcResult res = mvc.perform(post("/api/app/appointments").with(who)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "specialistId": "%s",
                                  "serviceId": "%s",
                                  "serviceStart": "%s",
                                  %s,
                                  "displayName": "Exposure client",
                                  "locale": "en",
                                  "marketingConsent": false,
                                  "source": "CONSOLE",
                                  "statusOverride": "CONFIRMED",
                                  "skipNoticeAndHorizon": true
                                }
                                """.formatted(specialist, DemoIds.HAIRCUT, start, clientJson)))
                .andReturn();
        assertThat(res.getResponse().getStatus()).as("create visit: %s", res.getResponse().getContentAsString())
                .isEqualTo(expected);
        return expected == 200 ? mapper.readTree(res.getResponse().getContentAsByteArray()) : null;
    }

    private JsonNode transition(RequestPostProcessor who, String visitId, String to) throws Exception {
        MvcResult res = mvc.perform(post("/api/app/appointments/" + visitId + "/transition").with(who).param("to", to))
                .andReturn();
        assertThat(res.getResponse().getStatus()).as("transition: %s", res.getResponse().getContentAsString()).isEqualTo(200);
        return mapper.readTree(res.getResponse().getContentAsByteArray());
    }

    /** A visit of the shared client with Nino today (tenant time), in the evening after the seeded day. */
    private void createTodayVisit(String clientId) throws Exception {
        LocalDate today = LocalDate.now(TBILISI);
        for (int minute : new int[]{20 * 60 + 30, 21 * 60 + 40, 22 * 60 + 50, 7 * 60, 8 * 60 + 10, 19 * 60 + 20}) {
            Instant start = today.atStartOfDay(TBILISI).plusMinutes(minute).toInstant();
            MvcResult res = mvc.perform(post("/api/app/appointments").with(lumenOwner())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"specialistId": "%s", "serviceId": "%s", "serviceStart": "%s", "clientId": "%s",
                                     "marketingConsent": false, "source": "CONSOLE", "statusOverride": "CONFIRMED",
                                     "skipNoticeAndHorizon": true}
                                    """.formatted(DemoIds.NINO, DemoIds.CONSULT, start, clientId)))
                    .andReturn();
            if (res.getResponse().getStatus() == 200) {
                return;
            }
        }
        throw new AssertionError("no free evening slot for Nino today to place the fixture visit");
    }
}

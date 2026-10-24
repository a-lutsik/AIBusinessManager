package com.cadence.app;

import com.cadence.app.seed.DemoIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.cadence.app.TestJwt.master;
import static com.cadence.app.TestJwt.owner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Client merge and owner status corrections against the real schema (RLS role {@code cadence_app}). Visits are
 * created through the console API far in the future (skipNoticeAndHorizon) so they never collide with the demo
 * schedule; JDBC checks run as the owner role {@code cadence}, which sees every tenant.
 */
@SpringBootTest
@RelaxedPublicRateLimits
@AutoConfigureMockMvc
class ClientMergeIT extends AbstractTimescaleIT {

    private static final int HAIRCUT_PRICE = 8000;
    private static final Instant BASE = Instant.now().plus(300, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
    private static final AtomicLong SLOT = new AtomicLong();

    @Autowired
    MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
    }

    private record Visit(String id, String clientId, Instant start) {
    }

    /** Next free start: every visit gets its own 3-hour slot, in creation order. */
    private static Instant nextStart() {
        return BASE.plus(3 * SLOT.getAndIncrement(), ChronoUnit.HOURS);
    }

    private Visit createVisit(UUID tenant, UUID specialist, UUID service, String phone, String name, boolean consent)
            throws Exception {
        Instant start = nextStart();
        MvcResult res = mvc.perform(post("/api/app/appointments")
                        .with(owner(tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "specialistId": "%s",
                                  "serviceId": "%s",
                                  "serviceStart": "%s",
                                  "phone": "%s",
                                  "displayName": "%s",
                                  "locale": "en",
                                  "marketingConsent": %s,
                                  "source": "CONSOLE",
                                  "statusOverride": "CONFIRMED",
                                  "skipNoticeAndHorizon": true
                                }
                                """.formatted(specialist, service, start, phone, name, consent)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = mapper.readTree(res.getResponse().getContentAsByteArray());
        return new Visit(body.get("id").asText(), body.get("clientId").asText(), start);
    }

    private Visit lumenVisit(String phone, String name, boolean consent) throws Exception {
        return createVisit(DemoIds.LUMEN, DemoIds.NINO, DemoIds.HAIRCUT, phone, name, consent);
    }

    /** Another visit of an existing client (by id). */
    private Visit lumenVisitFor(String clientId) throws Exception {
        Instant start = nextStart();
        MvcResult res = mvc.perform(post("/api/app/appointments")
                        .with(owner(DemoIds.LUMEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "specialistId": "%s",
                                  "serviceId": "%s",
                                  "serviceStart": "%s",
                                  "clientId": "%s",
                                  "marketingConsent": false,
                                  "source": "CONSOLE",
                                  "statusOverride": "CONFIRMED",
                                  "skipNoticeAndHorizon": true
                                }
                                """.formatted(DemoIds.NINO, DemoIds.HAIRCUT, start, clientId)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = mapper.readTree(res.getResponse().getContentAsByteArray());
        return new Visit(body.get("id").asText(), clientId, start);
    }

    private void transition(String appointmentId, String to) throws Exception {
        mvc.perform(post("/api/app/appointments/" + appointmentId + "/transition")
                        .with(owner(DemoIds.LUMEN))
                        .param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is(to)));
    }

    private JsonNode client(String clientId) throws Exception {
        MvcResult res = mvc.perform(get("/api/app/clients/" + clientId).with(owner(DemoIds.LUMEN)))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(res.getResponse().getContentAsByteArray());
    }

    private static Instant instant(JsonNode node) {
        return node == null || node.isNull() ? null : Instant.parse(node.asText());
    }

    private static long count(String sql, Object... args) throws SQLException {
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String scalar(String sql, Object... args) throws SQLException {
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static void exec(String sql, Object... args) throws SQLException {
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private void sellPackage(String clientId) throws Exception {
        mvc.perform(post("/api/app/clients/" + clientId + "/packages")
                        .with(owner(DemoIds.LUMEN))
                        .param("name", "5 haircuts")
                        .param("sessions", "5")
                        .param("valueMinor", "30000"))
                .andExpect(status().isOk());
    }

    private ResultActions merge(UUID tenant, String survivor, String absorbed) throws Exception {
        return mvc.perform(post("/api/app/clients/" + survivor + "/merge")
                .with(owner(tenant))
                .param("absorbedId", absorbed));
    }

    @Test
    void mergeMovesVisitsPackagesAndSumsCountersThenAnonymizesTheAbsorbedClient() throws Exception {
        Visit s1 = lumenVisit("+995555810001", "Mariam Survivor", false);
        Visit a1 = lumenVisit("+995555810002", "Mariam Duplicate", true);
        String survivor = s1.clientId();
        String absorbed = a1.clientId();
        Visit s2 = lumenVisitFor(survivor);
        Visit a2 = lumenVisitFor(absorbed);
        Visit a3 = lumenVisitFor(absorbed);
        transition(s1.id(), "COMPLETED");
        transition(a1.id(), "COMPLETED");
        transition(a2.id(), "NO_SHOW");
        sellPackage(absorbed);
        exec("update client set notes = 'prefers mornings', tags = 'vip' where id = ?", UUID.fromString(survivor));
        exec("update client set notes = 'allergic to ammonia', tags = 'vip,new' where id = ?", UUID.fromString(absorbed));
        // Reading the absorbed client assesses trust, which persists its trust_state row.
        client(absorbed);
        assertThat(count("select count(*) from trust_state where client_id = ?", UUID.fromString(absorbed))).isEqualTo(1);

        MvcResult merged = merge(DemoIds.LUMEN, survivor, absorbed).andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(survivor)))
                .andExpect(jsonPath("$.completedVisitCount", is(2)))
                .andExpect(jsonPath("$.noShowCount", is(1)))
                .andExpect(jsonPath("$.lateCancelCount", is(0)))
                .andExpect(jsonPath("$.completedValueMinor", is(2 * HAIRCUT_PRICE)))
                .andExpect(jsonPath("$.marketingConsent", is(false)))
                .andExpect(jsonPath("$.tags", is("vip,new")))
                .andReturn();
        JsonNode result = mapper.readTree(merged.getResponse().getContentAsByteArray());
        assertThat(instant(result.get("firstVisitAt"))).isEqualTo(s1.start());
        assertThat(instant(result.get("lastVisitAt"))).isEqualTo(a1.start());
        assertThat(result.get("notes").asText())
                .startsWith("prefers mornings")
                .contains("merged from Mariam Duplicate")
                .endsWith("allergic to ammonia");

        // Survivor now owns every visit; the absorbed profile owns none.
        UUID survivorId = UUID.fromString(survivor);
        UUID absorbedId = UUID.fromString(absorbed);
        assertThat(count("select count(*) from appointment where client_id = ?", survivorId)).isEqualTo(5);
        assertThat(count("select count(*) from appointment where client_id = ?", absorbedId)).isZero();
        JsonNode history = client(survivor).get("history");
        assertThat(history).extracting(n -> n.get("id").asText())
                .contains(s1.id(), s2.id(), a1.id(), a2.id(), a3.id());
        assertThat(count("select count(*) from service_package where client_id = ?", survivorId)).isEqualTo(1);
        assertThat(count("select count(*) from service_package where client_id = ?", absorbedId)).isZero();
        assertThat(count("select count(*) from trust_state where client_id = ?", absorbedId)).isZero();

        // Absorbed profile: anonymized, pointing at the survivor, aggregates cleared (no double counting).
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(
                "select display_name, anonymized_at, merged_into_client_id, completed_visit_count, no_show_count, notes "
                        + "from client where id = ?")) {
            ps.setObject(1, absorbedId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("display_name")).isEqualTo("anonymized");
                assertThat(rs.getTimestamp("anonymized_at")).isNotNull();
                assertThat(rs.getObject("merged_into_client_id", UUID.class)).isEqualTo(survivorId);
                assertThat(rs.getInt("completed_visit_count")).isZero();
                assertThat(rs.getInt("no_show_count")).isZero();
                assertThat(rs.getString("notes")).isNull();
            }
        }

        // Audit: entity is the survivor, ids and counts only.
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(
                "select after_state from audit_log where action = 'client.merge' and entity_id = ?")) {
            ps.setObject(1, survivorId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                String after = rs.getString(1);
                assertThat(after).contains(absorbed).contains("\"appointments\":3").contains("\"packages\":1")
                        .doesNotContain("+995").doesNotContain("Mariam");
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    void secondMergeOfTheSameAbsorbedClientIsConflictAndRollsBackEverything() throws Exception {
        Visit s1 = lumenVisit("+995555820001", "Survivor Two", false);
        Visit a1 = lumenVisit("+995555820002", "Absorbed Two", false);
        merge(DemoIds.LUMEN, s1.clientId(), a1.clientId()).andExpect(status().isOk());

        // A visit booked on the already absorbed profile would be moved by the ports before CRM rejects the merge.
        Visit late = lumenVisitFor(a1.clientId());
        merge(DemoIds.LUMEN, s1.clientId(), a1.clientId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CLIENT_ALREADY_MERGED")));

        assertThat(count("select count(*) from appointment where id = ? and client_id = ?",
                UUID.fromString(late.id()), UUID.fromString(a1.clientId())))
                .as("rolled back: the late visit stays on the absorbed profile").isEqualTo(1);
        assertThat(count("select count(*) from audit_log where action = 'client.merge' and entity_id = ?",
                UUID.fromString(s1.clientId()))).isEqualTo(1);
    }

    @Test
    void mergeRejectsSelfMergeAndForeignOrMissingClients() throws Exception {
        Visit lumen = lumenVisit("+995555830001", "Lumen Client", false);
        Visit other = lumenVisit("+995555830002", "Lumen Other", false);
        Visit aura = createVisit(DemoIds.AURA, DemoIds.AURA_MASTER, DemoIds.AURA_CUT, "+420777830001", "Aura Client", false);

        merge(DemoIds.LUMEN, lumen.clientId(), lumen.clientId()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_MERGE")));
        // Absorbed client of another tenant, survivor of another tenant, and a random id: all 404 from the caller's tenant.
        merge(DemoIds.LUMEN, lumen.clientId(), aura.clientId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("CLIENT_NOT_FOUND")));
        merge(DemoIds.LUMEN, aura.clientId(), lumen.clientId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("CLIENT_NOT_FOUND")));
        merge(DemoIds.AURA, lumen.clientId(), other.clientId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("CLIENT_NOT_FOUND")));
        merge(DemoIds.LUMEN, lumen.clientId(), UUID.randomUUID().toString()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("CLIENT_NOT_FOUND")));

        // Nothing happened to either tenant's rows.
        assertThat(count("select count(*) from client where merged_into_client_id is not null and id in (?, ?, ?)",
                UUID.fromString(lumen.clientId()), UUID.fromString(other.clientId()), UUID.fromString(aura.clientId())))
                .isZero();
        assertThat(count("select count(*) from appointment where id = ? and client_id = ?",
                UUID.fromString(aura.id()), UUID.fromString(aura.clientId()))).isEqualTo(1);
    }

    @Test
    void onlyAnOwnerMayMerge() throws Exception {
        Visit s = lumenVisit("+995555840001", "Master Try S", false);
        Visit a = lumenVisit("+995555840002", "Master Try A", false);

        mvc.perform(post("/api/app/clients/" + s.clientId() + "/merge")
                        .with(master(DemoIds.LUMEN, DemoIds.NINO))
                        .param("absorbedId", a.clientId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("FORBIDDEN_RESOURCE")));
        mvc.perform(post("/api/app/clients/" + s.clientId() + "/merge").param("absorbedId", a.clientId()))
                .andExpect(status().isUnauthorized());
        assertThat(count("select count(*) from client where id = ? and merged_into_client_id is not null",
                UUID.fromString(a.clientId()))).isZero();
    }

    @Test
    void ownerTasksOfTheAbsorbedClientFollowTheSurvivor() throws Exception {
        String firstSlot = TestSlots.firstFree(mvc, mapper, DemoIds.HAIRCUT, DemoIds.NINO, 2);
        MvcResult booked = mvc.perform(post("/api/public/tenants/lumen-studio/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceId": "%s",
                                  "specialistId": "%s",
                                  "serviceStart": "%s",
                                  "phone": "+995555850002",
                                  "name": "Public Duplicate",
                                  "locale": "en",
                                  "marketingConsent": false
                                }
                                """.formatted(DemoIds.HAIRCUT, DemoIds.NINO, firstSlot)))
                .andExpect(status().isOk())
                .andReturn();
        String bookingId = mapper.readTree(booked.getResponse().getContentAsByteArray()).get("id").asText();
        String absorbed = scalar("select client_id::text from appointment where id = ?", UUID.fromString(bookingId));
        Visit s = lumenVisit("+995555850001", "Task Survivor", false);
        assertThat(count("select count(*) from owner_task where client_id = ?", UUID.fromString(absorbed))).isEqualTo(1);

        merge(DemoIds.LUMEN, s.clientId(), absorbed).andExpect(status().isOk());

        assertThat(count("select count(*) from owner_task where client_id = ?", UUID.fromString(absorbed))).isZero();
        assertThat(count("select count(*) from owner_task where client_id = ?", UUID.fromString(s.clientId()))).isEqualTo(1);
    }

    @Test
    void statusCorrectionRoundTripLeavesTheClientAggregatesUnchanged() throws Exception {
        Visit v1 = lumenVisit("+995555860001", "Round Trip", false);
        Visit v2 = lumenVisitFor(v1.clientId());
        transition(v1.id(), "COMPLETED");
        transition(v2.id(), "COMPLETED");
        JsonNode start = client(v1.clientId()).get("client");
        assertThat(start.get("completedVisitCount").asInt()).isEqualTo(2);
        assertThat(start.get("completedValueMinor").asInt()).isEqualTo(2 * HAIRCUT_PRICE);
        assertThat(instant(start.get("firstVisitAt"))).isEqualTo(v1.start());
        assertThat(instant(start.get("lastVisitAt"))).isEqualTo(v2.start());

        // COMPLETED -> NO_SHOW on the latest visit: it leaves the aggregates, last visit falls back to v1.
        transition(v2.id(), "NO_SHOW");
        JsonNode corrected = client(v1.clientId()).get("client");
        assertThat(corrected.get("completedVisitCount").asInt()).isEqualTo(1);
        assertThat(corrected.get("completedValueMinor").asInt()).isEqualTo(HAIRCUT_PRICE);
        assertThat(corrected.get("noShowCount").asInt()).isEqualTo(1);
        assertThat(instant(corrected.get("firstVisitAt"))).isEqualTo(v1.start());
        assertThat(instant(corrected.get("lastVisitAt"))).isEqualTo(v1.start());

        // NO_SHOW -> COMPLETED: back to the starting aggregates, no leftover no-show.
        transition(v2.id(), "COMPLETED");
        assertSameAggregates(start, client(v1.clientId()).get("client"));

        // Same round trip on the earliest visit, which defines first_visit_at.
        transition(v1.id(), "NO_SHOW");
        JsonNode earliestOut = client(v1.clientId()).get("client");
        assertThat(earliestOut.get("completedVisitCount").asInt()).isEqualTo(1);
        assertThat(earliestOut.get("noShowCount").asInt()).isEqualTo(1);
        assertThat(instant(earliestOut.get("firstVisitAt"))).isEqualTo(v2.start());
        assertThat(instant(earliestOut.get("lastVisitAt"))).isEqualTo(v2.start());
        transition(v1.id(), "COMPLETED");
        assertSameAggregates(start, client(v1.clientId()).get("client"));

        assertThat(count("select count(*) from audit_log where action = 'appointment.status_correction' and entity_id = ?",
                UUID.fromString(v2.id()))).isEqualTo(2);
        assertThat(count("select count(*) from audit_log where action = 'appointment.transition' and entity_id = ?",
                UUID.fromString(v2.id()))).isEqualTo(1);
    }

    private static void assertSameAggregates(JsonNode expected, JsonNode actual) {
        for (String field : new String[]{"completedVisitCount", "noShowCount", "lateCancelCount", "completedValueMinor"}) {
            assertThat(actual.get(field).asInt()).as(field).isEqualTo(expected.get(field).asInt());
        }
        assertThat(instant(actual.get("firstVisitAt"))).as("firstVisitAt").isEqualTo(instant(expected.get("firstVisitAt")));
        assertThat(instant(actual.get("lastVisitAt"))).as("lastVisitAt").isEqualTo(instant(expected.get("lastVisitAt")));
    }

    @Test
    void masterCannotCorrectACompletedOrNoShowVisit() throws Exception {
        Visit v = lumenVisit("+995555870001", "Master Correction", false);
        transition(v.id(), "COMPLETED");

        mvc.perform(post("/api/app/appointments/" + v.id() + "/transition")
                        .with(master(DemoIds.LUMEN, DemoIds.NINO))
                        .param("to", "NO_SHOW"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("FORBIDDEN_RESOURCE")));

        assertThat(count("select count(*) from appointment where id = ? and status = 'COMPLETED'", UUID.fromString(v.id())))
                .isEqualTo(1);
        assertThat(client(v.clientId()).get("client").get("noShowCount").asInt()).isZero();
    }
}

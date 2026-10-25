package com.cadence.app;

import com.cadence.app.seed.DemoIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.cadence.app.TestJwt.master;
import static com.cadence.app.TestJwt.owner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Raw booking tokens never reach the database: owner tasks carry the {@code {manageLink}} placeholder, and the
 * owner obtains a link by issuing a fresh token on demand. JDBC checks run as the owner role {@code cadence}
 * (not subject to RLS), so they see every tenant.
 */
@SpringBootTest
@RelaxedPublicRateLimits
@AutoConfigureMockMvc
class AccessLinkIT extends AbstractTimescaleIT {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired
    MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
    }

    private record Booking(String id, String token) {
    }

    private Booking bookPublicly(String phone, String name) throws Exception {
        return bookPublicly(phone, name, "en");
    }

    private Booking bookPublicly(String phone, String name, String locale) throws Exception {
        String firstSlot = TestSlots.firstFree(mvc, mapper, DemoIds.HAIRCUT, DemoIds.NINO, 2);
        MvcResult created = mvc.perform(post("/api/public/tenants/lumen-studio/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceId": "%s",
                                  "specialistId": "%s",
                                  "serviceStart": "%s",
                                  "phone": "%s",
                                  "name": "%s",
                                  "locale": "%s",
                                  "marketingConsent": false
                                }
                                """.formatted(DemoIds.HAIRCUT, DemoIds.NINO, firstSlot, phone, name, locale)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode booking = mapper.readTree(created.getResponse().getContentAsByteArray());
        return new Booking(booking.get("id").asText(), booking.get("accessToken").asText());
    }

    private String issue(String appointmentId) throws Exception {
        MvcResult result = mvc.perform(post("/api/app/appointments/" + appointmentId + "/access-link")
                        .with(owner(DemoIds.LUMEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsByteArray());
        String managePath = body.get("managePath").asText();
        assertThat(managePath).startsWith("/b/" + appointmentId + "?token=");
        Matcher m = TOKEN.matcher(managePath);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private void assertTokenOpensBooking(String id, String token) throws Exception {
        mvc.perform(get("/api/public/bookings/" + id).param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id)));
    }

    @Test
    void ownerTaskForPublicBookingCarriesPlaceholderAndNoToken() throws Exception {
        Booking booking = bookPublicly("+995555700001", "Task Guest");

        MvcResult tasks = mvc.perform(get("/api/app/tasks").with(owner(DemoIds.LUMEN)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode task = null;
        for (JsonNode t : mapper.readTree(tasks.getResponse().getContentAsByteArray())) {
            if ("NEW_BOOKING".equals(t.path("kind").asText()) && booking.id().equals(t.path("appointmentId").asText())) {
                task = t;
            }
        }
        assertThat(task).as("NEW_BOOKING task for the booking").isNotNull();
        assertThat(task.path("link").isNull()).as("link").isTrue();
        assertThat(task.path("copyText").asText()).contains("{manageLink}").doesNotContain("token=")
                .doesNotContain(booking.token());
        assertThat(task.path("body").asText()).doesNotContain("token=").doesNotContain(booking.token());

        assertNoRawTokensInDatabase();
        // The client still gets the raw token exactly once, in the booking response.
        assertTokenOpensBooking(booking.id(), booking.token());
    }

    private JsonNode newBookingTask(Booking booking) throws Exception {
        MvcResult tasks = mvc.perform(get("/api/app/tasks").with(owner(DemoIds.LUMEN)))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode t : mapper.readTree(tasks.getResponse().getContentAsByteArray())) {
            if ("NEW_BOOKING".equals(t.path("kind").asText()) && booking.id().equals(t.path("appointmentId").asText())) {
                return t;
            }
        }
        throw new AssertionError("NEW_BOOKING task for the booking is missing");
    }

    @Test
    void clientTextFollowsTheClientLocaleWhileTheOwnerTitleStaysInTheOwnerLocale() throws Exception {
        Booking first = bookPublicly("+995555700015", "Locale Guest", "ru");
        JsonNode task = newBookingTask(first);
        assertThat(task.path("copyText").asText()).startsWith("Ваш визит").endsWith(": {manageLink}")
                .contains("Управление записью");
        // Owner locale defaults to en (app.i18n.owner-locale), whatever the client's language is.
        assertThat(task.path("title").asText()).isEqualTo("New booking — send confirmation manually");

        // The stored profile locale wins: the same client booking again in English is still written to in Russian.
        Booking second = bookPublicly("+995555700015", "Locale Guest", "en");
        assertThat(newBookingTask(second).path("copyText").asText()).startsWith("Ваш визит");

        // A client without a supported language gets English.
        Booking german = bookPublicly("+995555700016", "Locale Guest 2", "de");
        assertThat(newBookingTask(german).path("copyText").asText())
                .startsWith("Your visit is").endsWith("Manage it here: {manageLink}");
    }

    @Test
    void ownerIssuesFreshWorkingTokenOnEveryCall() throws Exception {
        Booking booking = bookPublicly("+995555700002", "Link Guest");

        String first = issue(booking.id());
        String second = issue(booking.id());

        assertThat(first).isNotEqualTo(second).isNotEqualTo(booking.token());
        assertTokenOpensBooking(booking.id(), first);
        assertTokenOpensBooking(booking.id(), second);
        assertTokenOpensBooking(booking.id(), booking.token());

        // Only hashes are stored: neither the issued raw tokens nor the original one appear anywhere in token rows.
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(
                "select count(*) from booking_access_token where appointment_id = ?")) {
            ps.setObject(1, UUID.fromString(booking.id()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(3);
            }
        }
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(
                "select count(*) from booking_access_token where token_hash in (?, ?, ?)")) {
            ps.setString(1, first);
            ps.setString(2, second);
            ps.setString(3, booking.token());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).as("raw tokens stored as hashes").isZero();
            }
        }
        // Audit row exists and does not contain a token.
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(
                "select after_state from audit_log where action = 'appointment.access_link.issue' and entity_id = ?")) {
            ps.setObject(1, UUID.fromString(booking.id()));
            int rows = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows++;
                    assertThat(rs.getString(1)).doesNotContain("token").doesNotContain(first).doesNotContain(second);
                }
            }
            assertThat(rows).isEqualTo(2);
        }
        assertNoRawTokensInDatabase();
    }

    @Test
    void masterCannotIssueAccessLink() throws Exception {
        Booking booking = bookPublicly("+995555700003", "Master Guest");

        mvc.perform(post("/api/app/appointments/" + booking.id() + "/access-link")
                        .with(master(DemoIds.LUMEN, DemoIds.NINO)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("FORBIDDEN_RESOURCE")));
    }

    @Test
    void anotherTenantsAppointmentIsNotFound() throws Exception {
        Booking booking = bookPublicly("+995555700004", "Foreign Guest");

        mvc.perform(post("/api/app/appointments/" + booking.id() + "/access-link")
                        .with(owner(DemoIds.AURA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("APPOINTMENT_NOT_FOUND")));
        mvc.perform(post("/api/app/appointments/" + UUID.randomUUID() + "/access-link")
                        .with(owner(DemoIds.LUMEN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("APPOINTMENT_NOT_FOUND")));
    }

    @Test
    void cancelledAppointmentIsConflict() throws Exception {
        Booking booking = bookPublicly("+995555700005", "Cancelled Guest");
        mvc.perform(post("/api/public/bookings/" + booking.id() + "/cancel").param("token", booking.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED_BY_CLIENT")));

        mvc.perform(post("/api/app/appointments/" + booking.id() + "/access-link")
                        .with(owner(DemoIds.LUMEN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("APPOINTMENT_CANCELLED")));
    }

    @Test
    void accessLinkRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/app/appointments/" + UUID.randomUUID() + "/access-link"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * V8 runs at startup on an empty schema, so a legacy-shaped row cannot exist before it. Instead the migration's
     * own SQL is read from the classpath and executed here against rows shaped like the old booking flow wrote them.
     */
    @Test
    void scrubMigrationReplacesLegacyTokensAndLeavesOtherTextAlone() throws Exception {
        String legacyId = UUID.randomUUID().toString();
        String rawToken = "AbC_-123xyzAbC_-123xyzAbC_-123xyzAbC_-12345";
        String legacyLink = "/b/" + legacyId + "?token=" + rawToken;
        String legacyCopy = "Your visit is confirmed. " + legacyLink;
        UUID taskId = UUID.randomUUID();
        UUID untouchedId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();

        try (Connection c = ownerConnection()) {
            insertTask(c, taskId, DemoIds.LUMEN, "NEW_BOOKING", legacyCopy, legacyCopy, legacyLink);
            insertTask(c, otherTenantId, DemoIds.AURA, "NEW_BOOKING",
                    "Visit " + legacyLink + " and again " + legacyLink + ".", null, legacyLink);
            insertTask(c, untouchedId, DemoIds.LUMEN, "MANUAL_MESSAGE",
                    "Call the client back", "Call the client back", "https://example.com/x?other=1");

            String sql = new String(
                    new ClassPathResource("db/migration/V8__scrub_raw_booking_tokens.sql").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            try (Statement st = c.createStatement()) {
                st.execute(sql);
            }

            assertThat(taskRow(c, taskId)).containsExactly(
                    "Your visit is confirmed. {manageLink}", "Your visit is confirmed. {manageLink}", null);
            assertThat(taskRow(c, otherTenantId)).containsExactly(
                    "Visit {manageLink} and again {manageLink}.", null, null);
            assertThat(taskRow(c, untouchedId)).containsExactly(
                    "Call the client back", "Call the client back", "https://example.com/x?other=1");
            assertNoRawTokensInDatabase();
        }
    }

    private static void insertTask(Connection c, UUID id, UUID tenant, String kind, String body, String copy, String link)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into owner_task (id, tenant_id, kind, title, body, copy_text, link) values (?, ?, ?, 'legacy', ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenant);
            ps.setString(3, kind);
            ps.setString(4, body);
            ps.setString(5, copy);
            ps.setString(6, link);
            ps.executeUpdate();
        }
    }

    private static String[] taskRow(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select body, copy_text, link from owner_task where id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return new String[]{rs.getString(1), rs.getString(2), rs.getString(3)};
            }
        }
    }

    /** As the owner role (no RLS): no owner_task row of any tenant holds a token. */
    private static void assertNoRawTokensInDatabase() throws SQLException {
        try (Connection c = ownerConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     select count(*) from owner_task
                     where body like '%token=%' or copy_text like '%token=%' or link like '%token=%'
                     """)) {
            rs.next();
            assertThat(rs.getLong(1)).as("owner_task rows containing token=").isZero();
        }
    }
}

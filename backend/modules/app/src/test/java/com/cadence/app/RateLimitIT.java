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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Public-API rate limits with the production defaults (no {@link RelaxedPublicRateLimits}): 5 slot reads a minute per
 * IP, 3 bookings a day per phone, 20 bookings an hour per tenant, 20 an hour per IP. This context has no trusted
 * proxy, so {@code X-Forwarded-For} must have no effect at all.
 *
 * <p>Every test uses its own peer addresses (set on the MockMvc request) and its own phones so tests never share a
 * counter, whatever their order. The tenant-per-hour test uses the second demo tenant for the same reason.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RateLimitIT extends AbstractTimescaleIT {

    @Autowired
    MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
    }

    private static RequestPostProcessor peer(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private MvcResult slots(String peerIp, String forwardedFor, int fromDaysAhead) throws Exception {
        LocalDate today = LocalDate.now(TestSlots.TBILISI);
        var request = get("/api/public/tenants/lumen-studio/slots")
                .with(peer(peerIp))
                .param("serviceId", DemoIds.HAIRCUT.toString())
                .param("specialistId", DemoIds.NINO.toString())
                .param("from", today.plusDays(fromDaysAhead).toString())
                .param("to", today.plusDays(TestSlots.HORIZON_DAYS).toString());
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult book(String slug, String peerIp, UUID serviceId, UUID specialistId, String slot, String phone)
            throws Exception {
        return mvc.perform(post("/api/public/tenants/" + slug + "/bookings")
                        .with(peer(peerIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceId": "%s",
                                  "specialistId": "%s",
                                  "serviceStart": "%s",
                                  "phone": "%s",
                                  "name": "Rate Limit Test",
                                  "locale": "en",
                                  "marketingConsent": false
                                }
                                """.formatted(serviceId, specialistId, slot, phone)))
                .andReturn();
    }

    private List<String> freeSlots(String peerIp, int fromDaysAhead) throws Exception {
        MvcResult res = slots(peerIp, null, fromDaysAhead);
        assertThat(res.getResponse().getStatus()).as("slots request").isEqualTo(200);
        List<String> result = new ArrayList<>();
        mapper.readTree(res.getResponse().getContentAsByteArray()).forEach(n -> result.add(n.asText()));
        assertThat(result).as("demo schedule must offer a free slot from +%d days", fromDaysAhead).isNotEmpty();
        return result;
    }

    private void assertRateLimited(MvcResult res, long maxRetryAfterSeconds) throws Exception {
        assertThat(res.getResponse().getStatus()).isEqualTo(429);
        assertThat(res.getResponse().getContentType()).startsWith("application/problem+json");
        String retryAfter = res.getResponse().getHeader("Retry-After");
        assertThat(retryAfter).as("Retry-After header").isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, maxRetryAfterSeconds);
        JsonNode body = mapper.readTree(res.getResponse().getContentAsByteArray());
        assertThat(body.get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("detail").asText()).as("human-readable text").contains(" ").endsWith(".");
    }

    private static List<String> auditAfterStates(UUID tenant, String fragment) throws SQLException {
        try (Connection c = ownerConnection();
             PreparedStatement ps = c.prepareStatement("""
                     select after_state from audit_log
                     where action = 'public.rate_limited' and tenant_id = ? and entity_type = 'tenant'
                       and entity_id = ? and actor_type = 'PUBLIC' and after_state like ?
                     """)) {
            ps.setObject(1, tenant);
            ps.setObject(2, tenant);
            ps.setString(3, "%" + fragment + "%");
            try (ResultSet rs = ps.executeQuery()) {
                List<String> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
                return rows;
            }
        }
    }

    @Test
    void sixthSlotsRequestFromOneIpWithinAMinuteIsRefusedAndAudited() throws Exception {
        String ip = "198.51.101.7";
        for (int i = 1; i <= 5; i++) {
            assertThat(slots(ip, null, 2).getResponse().getStatus()).as("request %d", i).isEqualTo(200);
        }
        MvcResult sixth = slots(ip, null, 2);
        assertRateLimited(sixth, 60);

        List<String> audit = auditAfterStates(DemoIds.LUMEN, "\"ip\":\"198.51.101.0\"");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).contains("\"limit\":\"slots_per_ip\"", "\"max\":5").doesNotContain(ip);

        // Further refusals inside the cooldown do not write more audit rows.
        assertThat(slots(ip, null, 2).getResponse().getStatus()).isEqualTo(429);
        assertThat(auditAfterStates(DemoIds.LUMEN, "\"ip\":\"198.51.101.0\"")).hasSize(1);
    }

    @Test
    void spoofedForwardedForDoesNotResetOrEvadeTheCounter() throws Exception {
        String ip = "198.51.102.7";
        for (int i = 1; i <= 5; i++) {
            assertThat(slots(ip, "10.9.9." + i, 2).getResponse().getStatus()).as("request %d", i).isEqualTo(200);
        }
        assertThat(slots(ip, "10.9.9.99", 2).getResponse().getStatus()).isEqualTo(429);
        assertThat(slots(ip, "203.0.113.200, 10.9.9.100", 2).getResponse().getStatus()).isEqualTo(429);

        // Naming an exhausted IP in the header does not hurt another client either.
        assertThat(slots("198.51.102.8", ip, 2).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void fourthBookingForOnePhoneInADayIsRefusedAndAuditedWithoutThePhone() throws Exception {
        String phone = "+995555987654";
        List<String> free = freeSlots("198.51.103.250", 20);
        String slot = free.get(0);

        MvcResult first = book("lumen-studio", "198.51.103.1", DemoIds.HAIRCUT, DemoIds.NINO, slot, phone);
        assertThat(first.getResponse().getStatus()).as(first.getResponse().getContentAsString()).isEqualTo(200);
        // Attempts count even when the booking itself fails (the slot is taken now), and the format does not matter.
        for (int i = 0; i < 2; i++) {
            String formatted = i == 0 ? "555 98 76 54" : "+995 555 987 654";
            MvcResult again = book("lumen-studio", "198.51.103." + (2 + i), DemoIds.HAIRCUT, DemoIds.NINO, slot, formatted);
            assertThat(again.getResponse().getStatus()).isEqualTo(409);
        }

        MvcResult fourth = book("lumen-studio", "198.51.103.9", DemoIds.HAIRCUT, DemoIds.NINO, slot, "555987654");
        assertRateLimited(fourth, 86_400);
        assertThat(mapper.readTree(fourth.getResponse().getContentAsByteArray()).get("detail").asText())
                .contains("phone number");

        List<String> audit = auditAfterStates(DemoIds.LUMEN, "bookings_per_phone_day");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0))
                .contains("\"phone\":\"+995*******54\"", "\"ip\":\"198.51.103.0\"")
                .doesNotContain("987654").doesNotContain("555987").doesNotContain("198.51.103.9");
    }

    @Test
    void bookingsForDifferentPhonesAreNotBlockedByThePhoneLimit() throws Exception {
        String slot = freeSlots("198.51.104.250", 20).get(0);
        for (int i = 0; i < 5; i++) {
            MvcResult res = book("lumen-studio", "198.51.104." + (1 + i), DemoIds.HAIRCUT, DemoIds.NINO, slot,
                    "+995555" + (710000 + i));
            assertThat(res.getResponse().getStatus()).as("attempt %d: %s", i, res.getResponse().getContentAsString())
                    .isNotEqualTo(429);
        }
        assertThat(auditAfterStates(DemoIds.LUMEN, "\"ip\":\"198.51.104.0\"")).isEmpty();
    }

    @Test
    void twentyFirstBookingOfATenantWithinAnHourIsRefusedAndAudited() throws Exception {
        for (int i = 0; i < 20; i++) {
            MvcResult res = book("aura-atelier", "198.51.105." + (1 + i), DemoIds.AURA_CUT, DemoIds.AURA_MASTER,
                    "2030-01-01T09:00:00Z", "+995555" + (720000 + i));
            assertThat(res.getResponse().getStatus()).as("attempt %d", i + 1).isNotEqualTo(429);
        }
        MvcResult refused = book("aura-atelier", "198.51.105.99", DemoIds.AURA_CUT, DemoIds.AURA_MASTER,
                "2030-01-01T09:00:00Z", "+995555729999");
        assertRateLimited(refused, 3_600);

        List<String> audit = auditAfterStates(DemoIds.AURA, "bookings_per_tenant_hour");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).contains("\"ip\":\"198.51.105.0\"").doesNotContain("729999");
    }

    @Test
    void unparsablePhoneFallsThroughToValidationAndOnlyTheIpLimitCountsIt() throws Exception {
        String slot = freeSlots("198.51.106.250", 20).get(freeSlots("198.51.106.250", 20).size() - 1);
        String ip = "198.51.106.7";

        MvcResult invalid = book("lumen-studio", ip, DemoIds.HAIRCUT, DemoIds.NINO, slot, "not a phone");
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(mapper.readTree(invalid.getResponse().getContentAsByteArray()).get("code").asText())
                .isEqualTo("INVALID_PHONE");

        for (int i = 1; i < 20; i++) {
            assertThat(book("lumen-studio", ip, DemoIds.HAIRCUT, DemoIds.NINO, slot, "not a phone").getResponse().getStatus())
                    .isEqualTo(400);
        }
        MvcResult refused = book("lumen-studio", ip, DemoIds.HAIRCUT, DemoIds.NINO, slot, "not a phone");
        assertRateLimited(refused, 3_600);
        assertThat(auditAfterStates(DemoIds.LUMEN, "bookings_per_ip_hour")).isNotEmpty();
    }
}

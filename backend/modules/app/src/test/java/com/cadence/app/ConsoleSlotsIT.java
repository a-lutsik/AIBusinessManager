package com.cadence.app;

import com.cadence.app.seed.DemoIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.util.List;

import static com.cadence.app.TestJwt.master;
import static com.cadence.app.TestJwt.owner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * TD-76: {@code GET /api/app/slots}, the console's own slot route. Same answer as the public route, behind the sign-in, not
 * counted against the public per-IP limit (this class runs with the production limit of 5 a minute, like {@code RateLimitIT}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsoleSlotsIT extends AbstractTimescaleIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IP = "203.0.113.76";

    @Autowired
    MockMvc mvc;

    private static RequestPostProcessor peer() {
        return peer(IP);
    }

    private static RequestPostProcessor peer(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static MockHttpServletRequestBuilder slots(String path, Object specialist) {
        LocalDate today = LocalDate.now(TestSlots.TBILISI);
        return slots(path, specialist, today.plusDays(1), today.plusDays(7));
    }

    private static MockHttpServletRequestBuilder slots(String path, Object specialist, LocalDate from, LocalDate to) {
        return get(path)
                .param("serviceId", DemoIds.HAIRCUT.toString())
                .param("specialistId", specialist.toString())
                .param("from", from.toString())
                .param("to", to.toString());
    }

    @Test
    void anOwnerGetsTheSameSlotsAsThePublicRouteWithoutUsingUpItsLimit() throws Exception {
        for (int i = 0; i < 8; i++) {
            MvcResult console = mvc.perform(slots("/api/app/slots", DemoIds.NINO).with(owner(DemoIds.LUMEN)).with(peer())).andReturn();
            assertThat(console.getResponse().getStatus()).as("request %d", i).isEqualTo(200);
        }
        JsonNode console = body(mvc.perform(slots("/api/app/slots", DemoIds.NINO).with(owner(DemoIds.LUMEN)).with(peer())).andReturn());
        assertThat(console.size()).isPositive();

        MvcResult open = mvc.perform(slots("/api/public/tenants/lumen-studio/slots", DemoIds.NINO).with(peer())).andReturn();

        assertThat(open.getResponse().getStatus()).as("the console requests did not count against the IP").isEqualTo(200);
        assertThat(body(open)).isEqualTo(console);
    }

    @Test
    void aMasterGetsOwnSlotsOnly() throws Exception {
        RequestPostProcessor nino = master(DemoIds.LUMEN, DemoIds.NINO);
        assertThat(mvc.perform(slots("/api/app/slots", DemoIds.NINO).with(nino)).andReturn().getResponse().getStatus()).isEqualTo(200);

        MvcResult other = mvc.perform(slots("/api/app/slots", DemoIds.ANNA).with(nino)).andReturn();

        assertThat(other.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(other).path("code").asText()).isEqualTo("FORBIDDEN_RESOURCE");
    }

    @Test
    void anotherTenantsSpecialistHasNoSlotsAndAnAnonymousCallerIsRefused() throws Exception {
        MvcResult foreign = mvc.perform(slots("/api/app/slots", DemoIds.NINO).with(owner(DemoIds.AURA))).andReturn();
        assertThat(foreign.getResponse().getStatus()).as("the public route answers the same for an unknown offering").isEqualTo(409);
        assertThat(body(foreign).path("code").asText()).isEqualTo("SERVICE_NOT_OFFERED");

        assertThat(mvc.perform(slots("/api/app/slots", DemoIds.NINO)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aBackwardOrTooLongRangeIsRefusedOnBothRoutes() throws Exception {
        LocalDate today = LocalDate.now(TestSlots.TBILISI);
        for (String path : List.of("/api/app/slots", "/api/public/tenants/lumen-studio/slots")) {
            for (LocalDate[] range : new LocalDate[][]{{today.plusDays(2), today.plusDays(1)}, {today, today.plusDays(63)}}) {
                MvcResult refused = mvc.perform(slots(path, DemoIds.NINO, range[0], range[1])
                        .with(owner(DemoIds.LUMEN)).with(peer("203.0.113.77"))).andReturn();
                assertThat(refused.getResponse().getStatus()).as("%s %s..%s", path, range[0], range[1]).isEqualTo(400);
                assertThat(body(refused).path("code").asText()).isEqualTo("INVALID_SLOT_RANGE");
            }
        }
        MvcResult longest = mvc.perform(slots("/api/app/slots", DemoIds.NINO, today, today.plusDays(62))
                .with(owner(DemoIds.LUMEN)).with(peer("203.0.113.77"))).andReturn();
        assertThat(longest.getResponse().getStatus()).isEqualTo(200);
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }
}

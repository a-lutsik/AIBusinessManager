package com.cadence.app;

import com.cadence.app.seed.DemoIds;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static com.cadence.app.TestJwt.master;
import static com.cadence.app.TestJwt.owner;
import static com.cadence.app.TestJwt.ownerWithoutTenant;
import static com.cadence.app.TestJwt.tenantWithoutRoles;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code /api/app/**} in the default (OAuth2 / JWT) mode: identity comes only from the token. */
@SpringBootTest
@AutoConfigureMockMvc
class AppApiAuthIT extends AbstractTimescaleIT {

    @Autowired
    MockMvc mvc;

    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    @Test
    void noTokenIsUnauthorizedEvenWithTenantAndRoleHeaders() throws Exception {
        mvc.perform(get("/api/app/specialists"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/app/specialists")
                        .header("X-Tenant-Id", DemoIds.LUMEN.toString())
                        .header("X-Actor-Role", "OWNER"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithoutTenantClaimIsForbidden() throws Exception {
        mvc.perform(get("/api/app/specialists")
                        .with(ownerWithoutTenant())
                        .header("X-Tenant-Id", DemoIds.LUMEN.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("TENANT_CLAIM_MISSING")));
    }

    @Test
    void tokenWithoutRolesIsForbiddenNotOwner() throws Exception {
        mvc.perform(get("/api/app/metrics").with(tenantWithoutRoles(DemoIds.LUMEN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ROLE_MISSING")));
    }

    @Test
    void masterCannotReadOwnerOnlyMetrics() throws Exception {
        mvc.perform(get("/api/app/metrics").with(master(DemoIds.LUMEN, DemoIds.NINO)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/app/metrics").with(owner(DemoIds.LUMEN)))
                .andExpect(status().isOk());
    }

    @Test
    void meReflectsTokenIdentity() throws Exception {
        mvc.perform(get("/api/app/me").with(master(DemoIds.LUMEN, DemoIds.NINO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role", is("MASTER")))
                .andExpect(jsonPath("$.specialistId", is(DemoIds.NINO.toString())))
                .andExpect(jsonPath("$.tenantId", is(DemoIds.LUMEN.toString())));
    }

    @Test
    void masterCannotReadAnotherSpecialistsAppointment() throws Exception {
        MvcResult calendar = mvc.perform(get("/api/app/calendar")
                        .with(owner(DemoIds.LUMEN))
                        .param("from", "2020-01-01T00:00:00Z")
                        .param("to", "2030-01-01T00:00:00Z")
                        .param("specialistId", DemoIds.ANNA.toString()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode visits = mapper.readTree(calendar.getResponse().getContentAsByteArray());
        assertThat(visits.size()).as("demo seed must contain a visit of another specialist (Anna)").isGreaterThan(0);
        String foreignId = visits.get(0).get("id").asText();

        mvc.perform(get("/api/app/appointments/" + foreignId).with(master(DemoIds.LUMEN, DemoIds.NINO)))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantHeaderCannotRedirectATokenToAnotherTenant() throws Exception {
        mvc.perform(get("/api/app/specialists")
                        .with(owner(DemoIds.AURA))
                        .header("X-Tenant-Id", DemoIds.LUMEN.toString())
                        .header("X-Tenant-Slug", "lumen-studio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(DemoIds.AURA_MASTER.toString())))
                .andExpect(jsonPath("$[*].id", not(hasItem(DemoIds.NINO.toString()))))
                .andExpect(jsonPath("$[*].id", not(hasItem(DemoIds.ANNA.toString()))));
    }
}

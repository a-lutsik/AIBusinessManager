package com.cadence.app.web;

import com.cadence.ai.api.AiAssistantService;
import com.cadence.app.config.SecurityConfig;
import com.cadence.app.seed.DemoIds;
import com.cadence.app.usecase.ClientMergeService;
import com.cadence.booking.api.BookingService;
import com.cadence.catalog.api.CatalogService;
import com.cadence.crm.api.CrmService;
import com.cadence.growth.api.GrowthService;
import com.cadence.notification.api.OwnerTaskService;
import com.cadence.platform.jooq.tables.records.TenantRecord;
import com.cadence.platform.tenant.TenantDirectory;
import com.cadence.retention.api.RetentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Spring Security chain (default OAuth2 mode) + real {@link TenantContextFilter} + real controller, without a
 * database. It only passes if the tenant filter runs after the security chain and reads its authentication.
 */
@WebMvcTest(AppApiController.class)
@Import(SecurityConfig.class)
class AppApiSecurityWebTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TenantDirectory tenants;
    @MockitoBean
    CatalogService catalogService;
    @MockitoBean
    BookingService bookingService;
    @MockitoBean
    CrmService crmService;
    @MockitoBean
    RetentionService retentionService;
    @MockitoBean
    GrowthService growthService;
    @MockitoBean
    AiAssistantService aiAssistantService;
    @MockitoBean
    OwnerTaskService ownerTaskService;
    @MockitoBean
    ClientMergeService clientMergeService;

    @BeforeEach
    void tenantsExist() {
        for (UUID id : List.of(DemoIds.LUMEN, DemoIds.AURA)) {
            TenantRecord record = new TenantRecord();
            record.setId(id);
            record.setSlug(id.equals(DemoIds.LUMEN) ? "lumen-studio" : "aura-atelier");
            when(tenants.findById(id)).thenReturn(Optional.of(record));
        }
    }

    @Test
    void anonymousRequestIsUnauthorizedEvenWithIdentityHeaders() throws Exception {
        mvc.perform(get("/api/app/me")
                        .header("X-Tenant-Id", DemoIds.LUMEN.toString())
                        .header("X-Actor-Role", "OWNER"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenTenantWinsOverTenantAndRoleHeaders() throws Exception {
        mvc.perform(get("/api/app/me")
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j
                                .subject("master-1")
                                .claim("tenant_id", DemoIds.AURA.toString())
                                .claim("roles", List.of("MASTER"))
                                .claim("specialist_id", DemoIds.AURA_MASTER.toString())))
                        .header("X-Tenant-Id", DemoIds.LUMEN.toString())
                        .header("X-Actor-Role", "OWNER")
                        .header("X-Specialist-Id", DemoIds.NINO.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId", is(DemoIds.AURA.toString())))
                .andExpect(jsonPath("$.role", is("MASTER")))
                .andExpect(jsonPath("$.specialistId", is(DemoIds.AURA_MASTER.toString())))
                .andExpect(jsonPath("$.actorId", is("master-1")));
    }

    @Test
    void tokenWithoutTenantClaimIsForbidden() throws Exception {
        mvc.perform(get("/api/app/me")
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.claim("roles", List.of("OWNER"))))
                        .header("X-Tenant-Id", DemoIds.LUMEN.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("TENANT_CLAIM_MISSING")));
    }

    @Test
    void tokenWithoutRolesIsForbiddenNotOwner() throws Exception {
        mvc.perform(get("/api/app/metrics")
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j
                                .claim("tenant_id", DemoIds.LUMEN.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ROLE_MISSING")));
    }

    @Test
    void masterCannotCallOwnerOnlyEndpointButOwnerCan() throws Exception {
        mvc.perform(get("/api/app/metrics")
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j
                                .claim("tenant_id", DemoIds.LUMEN.toString())
                                .claim("roles", List.of("MASTER"))
                                .claim("specialist_id", DemoIds.NINO.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("FORBIDDEN_RESOURCE")));

        mvc.perform(get("/api/app/metrics")
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j
                                .claim("tenant_id", DemoIds.LUMEN.toString())
                                .claim("roles", List.of("OWNER")))))
                .andExpect(status().isOk());
    }
}

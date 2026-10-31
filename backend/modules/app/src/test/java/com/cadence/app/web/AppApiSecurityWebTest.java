package com.cadence.app.web;

import com.cadence.ai.api.AiAssistantService;
import com.cadence.ai.api.AiSettingsView;
import com.cadence.app.config.SecurityConfig;
import com.cadence.app.ratelimit.ClientIpResolver;
import com.cadence.app.ratelimit.PublicRateLimiter;
import com.cadence.app.seed.DemoIds;
import com.cadence.app.usecase.ClientMergeService;
import com.cadence.booking.api.BookingService;
import com.cadence.catalog.api.CatalogService;
import com.cadence.crm.api.CrmService;
import com.cadence.growth.api.GrowthService;
import com.cadence.notification.api.OwnerTaskService;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.jooq.tables.records.TenantRecord;
import com.cadence.platform.tenant.TenantDirectory;
import com.cadence.retention.api.RetentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    // WebMvcTest picks up the MVC interceptor of the public rate limit (RateLimitWebConfig); it is not exercised here.
    @MockitoBean
    PublicRateLimiter publicRateLimiter;
    @MockitoBean
    ClientIpResolver clientIpResolver;

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

    @Test
    void masterIsForbiddenOnEveryAiEndpoint() throws Exception {
        RequestPostProcessor master = SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j
                .claim("tenant_id", DemoIds.LUMEN.toString())
                .claim("roles", List.of("MASTER"))
                .claim("specialist_id", DemoIds.NINO.toString()));
        String draft = UUID.randomUUID().toString();

        List<MockHttpServletRequestBuilder> requests = List.of(
                post("/api/app/ai/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"),
                get("/api/app/ai/conversations/" + UUID.randomUUID()),
                get("/api/app/ai/drafts"),
                post("/api/app/ai/drafts/" + draft + "/confirm"),
                post("/api/app/ai/drafts/" + draft + "/reject"),
                get("/api/app/ai/settings"),
                put("/api/app/ai/settings").contentType(MediaType.APPLICATION_JSON).content("{\"notesToLlm\":true}"));
        for (MockHttpServletRequestBuilder request : requests) {
            mvc.perform(request.with(master))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("FORBIDDEN_RESOURCE")));
        }
        verifyNoInteractions(aiAssistantService);
    }

    @Test
    void ownerReadsAndUpdatesAiSettings() throws Exception {
        AiSettingsView settings = new AiSettingsView(true, 1_000_000L, 1234L, "2026-10", Instant.parse("2026-10-31T22:00:00Z"));
        when(aiAssistantService.getSettings()).thenReturn(settings);
        when(aiAssistantService.setNotesToLlm(true)).thenReturn(settings);

        mvc.perform(get("/api/app/ai/settings").with(owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notesToLlm", is(true)))
                .andExpect(jsonPath("$.monthlyTokenBudget", is(1_000_000)))
                .andExpect(jsonPath("$.monthTokensUsed", is(1234)))
                .andExpect(jsonPath("$.period", is("2026-10")))
                .andExpect(jsonPath("$.periodResetsAt", is("2026-10-31T22:00:00Z")));

        mvc.perform(put("/api/app/ai/settings").with(owner())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notesToLlm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notesToLlm", is(true)))
                .andExpect(jsonPath("$.period", is("2026-10")));
        verify(aiAssistantService).setNotesToLlm(true);
    }

    @Test
    void aiSettingsUpdateWithMissingOrMalformedValueIsBadRequest() throws Exception {
        mvc.perform(put("/api/app/ai/settings").with(owner())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_AI_SETTINGS")));
        mvc.perform(put("/api/app/ai/settings").with(owner())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notesToLlm\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_AI_SETTINGS")));
        mvc.perform(put("/api/app/ai/settings").with(owner())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notesToLlm\":\"maybe\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/app/ai/settings").with(owner())
                        .contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest());
        verify(aiAssistantService, never()).setNotesToLlm(any(Boolean.class));
    }

    @Test
    void exhaustedAiBudgetIsTooManyRequestsWithItsCode() throws Exception {
        when(aiAssistantService.chat(any(), any(), any()))
                .thenThrow(new DomainException("AI_BUDGET_EXCEEDED", 429, "Monthly AI token budget used up"));

        mvc.perform(post("/api/app/ai/chat").with(owner())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code", is("AI_BUDGET_EXCEEDED")));
    }

    private static RequestPostProcessor owner() {
        return SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j
                .claim("tenant_id", DemoIds.LUMEN.toString())
                .claim("roles", List.of("OWNER")));
    }
}

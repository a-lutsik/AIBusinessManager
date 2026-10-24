package com.cadence.app.web;

import com.cadence.app.config.SecurityProperties;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.platform.jooq.tables.records.TenantRecord;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantContextFilterTest {

    private static final UUID LUMEN = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID AURA = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID NINO = UUID.fromString("00000000-0000-4000-8000-000000000011");

    private final ObjectMapper json = new ObjectMapper();
    private TenantDirectory tenants;
    private CatalogService catalog;

    @BeforeEach
    void setUp() {
        tenants = mock(TenantDirectory.class);
        catalog = mock(CatalogService.class);
        when(tenants.findById(LUMEN)).thenReturn(Optional.of(tenant(LUMEN, "lumen-studio")));
        when(tenants.findById(AURA)).thenReturn(Optional.of(tenant(AURA, "aura-atelier")));
        when(tenants.findBySlug("lumen-studio")).thenReturn(Optional.of(tenant(LUMEN, "lumen-studio")));
        when(tenants.findBySlug("aura-atelier")).thenReturn(Optional.of(tenant(AURA, "aura-atelier")));
        ActorContext.clear();
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        ActorContext.clear();
        TenantContext.clear();
    }

    // ---- JWT mode --------------------------------------------------------------------------------------------

    @Test
    void jwtOwnerGetsTenantAndRoleFromClaims() throws Exception {
        authenticate(jwt(LUMEN.toString(), List.of("OWNER"), null));

        Result r = run(jwtFilter(), appRequest());

        assertThat(r.chainCalled).isTrue();
        assertThat(r.tenant).isEqualTo(LUMEN);
        assertThat(r.actor.role()).isEqualTo(ActorContext.Role.OWNER);
        assertThat(r.actor.actorId()).isEqualTo("user-1");
        assertThat(r.actor.specialistId()).isNull();
        assertThat(r.response.getStatus()).isEqualTo(200);
    }

    @Test
    void jwtIgnoresTenantAndActorHeaders() throws Exception {
        authenticate(jwt(AURA.toString(), List.of("MASTER"), NINO.toString()));
        MockHttpServletRequest request = appRequest();
        request.addHeader(TenantContextFilter.HEADER_TENANT_ID, LUMEN.toString());
        request.addHeader("X-Tenant-Slug", "lumen-studio");
        request.addHeader(TenantContextFilter.HEADER_ACTOR_ROLE, "OWNER");
        request.addHeader(TenantContextFilter.HEADER_ACTOR_ID, "spoofed");
        request.addHeader(TenantContextFilter.HEADER_SPECIALIST_ID, UUID.randomUUID().toString());

        Result r = run(jwtFilter(), request);

        assertThat(r.tenant).isEqualTo(AURA);
        assertThat(r.actor.role()).isEqualTo(ActorContext.Role.MASTER);
        assertThat(r.actor.actorId()).isEqualTo("user-1");
        assertThat(r.actor.specialistId()).isEqualTo(NINO);
    }

    @Test
    void jwtWithoutTenantClaimIsForbiddenEvenWithTenantHeader() throws Exception {
        authenticate(jwt(null, List.of("OWNER"), null));
        MockHttpServletRequest request = appRequest();
        request.addHeader(TenantContextFilter.HEADER_TENANT_ID, LUMEN.toString());

        Result r = run(jwtFilter(), request);

        assertProblem(r, 403, "TENANT_CLAIM_MISSING");
        assertThat(r.chainCalled).isFalse();
    }

    @Test
    void jwtWithBlankOrMalformedTenantClaimIsForbidden() throws Exception {
        for (String claim : List.of("", "  ", "not-a-uuid")) {
            authenticate(jwt(claim, List.of("OWNER"), null));
            assertProblem(run(jwtFilter(), appRequest()), 403, "TENANT_CLAIM_MISSING");
        }
    }

    @Test
    void jwtWithUnknownTenantIsForbidden() throws Exception {
        authenticate(jwt(UUID.randomUUID().toString(), List.of("OWNER"), null));

        Result r = run(jwtFilter(), appRequest());

        assertProblem(r, 403, "TENANT_UNKNOWN");
        assertThat(r.chainCalled).isFalse();
    }

    @Test
    void jwtWithoutRolesIsForbiddenAndNeverOwner() throws Exception {
        authenticate(jwt(LUMEN.toString(), null, null));

        Result r = run(jwtFilter(), appRequest());

        assertProblem(r, 403, "ROLE_MISSING");
        assertThat(r.chainCalled).isFalse();
        assertThat(r.actor).isNull();
    }

    @Test
    void jwtWithUnknownRolesIsForbidden() throws Exception {
        authenticate(jwt(LUMEN.toString(), List.of("VIEWER", "default-roles-cadence"), null));

        assertProblem(run(jwtFilter(), appRequest()), 403, "ROLE_MISSING");
    }

    @Test
    void ownerWinsOverMaster() throws Exception {
        authenticate(jwt(LUMEN.toString(), List.of("MASTER", "OWNER"), null));

        Result r = run(jwtFilter(), appRequest());

        assertThat(r.actor.role()).isEqualTo(ActorContext.Role.OWNER);
        assertThat(r.actor.specialistId()).isNull();
    }

    @Test
    void masterSpecialistComesFromKeycloakLookupWhenClaimIsAbsent() throws Exception {
        authenticate(jwt(LUMEN.toString(), List.of("MASTER"), null));
        when(catalog.findSpecialistByKeycloakUserId("user-1"))
                .thenReturn(Optional.of(new SpecialistView(NINO, "Nino", "user-1", "#fff", true)));

        Result r = run(jwtFilter(), appRequest());

        assertThat(r.actor.role()).isEqualTo(ActorContext.Role.MASTER);
        assertThat(r.actor.specialistId()).isEqualTo(NINO);
    }

    @Test
    void masterWithoutSpecialistMappingIsForbidden() throws Exception {
        authenticate(jwt(LUMEN.toString(), List.of("MASTER"), null));
        when(catalog.findSpecialistByKeycloakUserId("user-1")).thenReturn(Optional.empty());

        Result r = run(jwtFilter(), appRequest());

        assertProblem(r, 403, "SPECIALIST_UNMAPPED");
        assertThat(r.chainCalled).isFalse();
    }

    @Test
    void masterWithMalformedSpecialistClaimIsForbidden() throws Exception {
        authenticate(jwt(LUMEN.toString(), List.of("MASTER"), "garbage"));

        assertProblem(run(jwtFilter(), appRequest()), 403, "SPECIALIST_UNMAPPED");
    }

    @Test
    void noJwtInJwtModeIsUnauthorizedAndSetsNoActor() throws Exception {
        MockHttpServletRequest request = appRequest();
        request.addHeader(TenantContextFilter.HEADER_TENANT_ID, LUMEN.toString());
        request.addHeader(TenantContextFilter.HEADER_ACTOR_ROLE, "OWNER");

        Result r = run(jwtFilter(), request);

        assertProblem(r, 401, "UNAUTHENTICATED");
        assertThat(r.chainCalled).isFalse();
        assertThat(r.actor).isNull();
        assertThat(r.tenant).isNull();
    }

    // ---- public routes ---------------------------------------------------------------------------------------

    @Test
    void publicRouteUsesSlugTenantAndPublicActorIgnoringHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/public/tenants/lumen-studio/services");
        request.addHeader(TenantContextFilter.HEADER_TENANT_ID, AURA.toString());
        request.addHeader(TenantContextFilter.HEADER_ACTOR_ROLE, "OWNER");
        request.addHeader(TenantContextFilter.HEADER_ACTOR_ID, "spoofed");

        for (TenantContextFilter filter : List.of(jwtFilter(), headerFilter())) {
            Result r = run(filter, request);

            assertThat(r.tenant).isEqualTo(LUMEN);
            assertThat(r.actor).isEqualTo(ActorContext.publicActor());
        }
    }

    @Test
    void publicRouteStaysPublicEvenWhenAJwtIsPresent() throws Exception {
        authenticate(jwt(AURA.toString(), List.of("OWNER"), null));

        Result r = run(jwtFilter(), new MockHttpServletRequest("GET", "/api/public/tenants/lumen-studio"));

        assertThat(r.tenant).isEqualTo(LUMEN);
        assertThat(r.actor.role()).isEqualTo(ActorContext.Role.PUBLIC);
    }

    @Test
    void publicRouteWithoutSlugHasNoTenantEvenWithTenantHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/public/bookings/" + UUID.randomUUID());
        request.addHeader(TenantContextFilter.HEADER_TENANT_ID, LUMEN.toString());

        Result r = run(jwtFilter(), request);

        assertThat(r.tenant).isNull();
        assertThat(r.actor).isEqualTo(ActorContext.publicActor());
    }

    @Test
    void unknownPublicSlugLeavesTenantUnset() throws Exception {
        when(tenants.findBySlug("nope")).thenReturn(Optional.empty());

        Result r = run(jwtFilter(), new MockHttpServletRequest("GET", "/api/public/tenants/nope"));

        assertThat(r.tenant).isNull();
        assertThat(r.actor).isEqualTo(ActorContext.publicActor());
    }

    // ---- encoded paths ---------------------------------------------------------------------------------------

    @Test
    void percentEncodedAppPathIsRoutedLikeTheDecodedOne() throws Exception {
        authenticate(jwt(LUMEN.toString(), List.of("OWNER"), null));

        Result r = run(jwtFilter(), new MockHttpServletRequest("GET", "/api/%61pp/me"));

        assertThat(r.chainCalled).isTrue();
        assertThat(r.tenant).isEqualTo(LUMEN);
        assertThat(r.actor.role()).isEqualTo(ActorContext.Role.OWNER);
    }

    @Test
    void percentEncodedAppPathWithoutJwtIsRejectedNotPassedThroughWithoutContext() throws Exception {
        Result r = run(jwtFilter(), new MockHttpServletRequest("GET", "/api/%61pp/me"));

        assertProblem(r, 401, "UNAUTHENTICATED");
        assertThat(r.chainCalled).isFalse();
    }

    @Test
    void encodedAppPathInHeaderModeStillRequiresTheTenantHeader() throws Exception {
        assertProblem(run(headerFilter(), new MockHttpServletRequest("GET", "/%61pi/app/me")), 400, "TENANT_HEADER_INVALID");
    }

    @Test
    void percentEncodedPublicPathAndSlugAreDecodedBeforeMatching() throws Exception {
        Result r = run(jwtFilter(), new MockHttpServletRequest("GET", "/api/%70ublic/tenants/%6Cumen-studio/services"));

        assertThat(r.tenant).isEqualTo(LUMEN);
        assertThat(r.actor).isEqualTo(ActorContext.publicActor());
    }

    @Test
    void contextPathAndPathParametersAreStrippedBeforeMatching() throws Exception {
        authenticate(jwt(LUMEN.toString(), List.of("OWNER"), null));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/cadence/api/app;jsessionid=x/me");
        request.setContextPath("/cadence");

        Result r = run(jwtFilter(), request);

        assertThat(r.tenant).isEqualTo(LUMEN);
        assertThat(r.actor.role()).isEqualTo(ActorContext.Role.OWNER);
    }

    // ---- header mode (dev-headers profile) -------------------------------------------------------------------

    @Test
    void headerModeDefaultsToOwnerWhenRoleHeaderIsAbsent() throws Exception {
        MockHttpServletRequest request = appRequest();
        request.addHeader(TenantContextFilter.HEADER_TENANT_ID, LUMEN.toString());

        Result r = run(headerFilter(), request);

        assertThat(r.tenant).isEqualTo(LUMEN);
        assertThat(r.actor.role()).isEqualTo(ActorContext.Role.OWNER);
        assertThat(r.actor.actorId()).isEqualTo("local");
    }

    @Test
    void headerModeMasterNeedsSpecialistHeader() throws Exception {
        MockHttpServletRequest ok = appRequest();
        ok.addHeader(TenantContextFilter.HEADER_TENANT_ID, LUMEN.toString());
        ok.addHeader(TenantContextFilter.HEADER_ACTOR_ROLE, "MASTER");
        ok.addHeader(TenantContextFilter.HEADER_SPECIALIST_ID, NINO.toString());
        ok.addHeader(TenantContextFilter.HEADER_ACTOR_ID, "nino");

        Result r = run(headerFilter(), ok);

        assertThat(r.actor).isEqualTo(new ActorContext.Actor("nino", ActorContext.Role.MASTER, NINO));

        MockHttpServletRequest missing = appRequest();
        missing.addHeader(TenantContextFilter.HEADER_TENANT_ID, LUMEN.toString());
        missing.addHeader(TenantContextFilter.HEADER_ACTOR_ROLE, "MASTER");
        assertProblem(run(headerFilter(), missing), 400, "SPECIALIST_HEADER_INVALID");
    }

    @Test
    void headerModeInvalidRoleIsBadRequestNotServerError() throws Exception {
        for (String role : List.of("ADMIN", "owner", "PUBLIC", "SYSTEM", "AI")) {
            MockHttpServletRequest request = appRequest();
            request.addHeader(TenantContextFilter.HEADER_TENANT_ID, LUMEN.toString());
            request.addHeader(TenantContextFilter.HEADER_ACTOR_ROLE, role);

            Result r = run(headerFilter(), request);

            assertProblem(r, 400, "ROLE_INVALID");
            assertThat(r.chainCalled).isFalse();
        }
    }

    @Test
    void headerModeRequiresValidTenantHeader() throws Exception {
        assertProblem(run(headerFilter(), appRequest()), 400, "TENANT_HEADER_INVALID");

        MockHttpServletRequest bad = appRequest();
        bad.addHeader(TenantContextFilter.HEADER_TENANT_ID, "not-a-uuid");
        assertProblem(run(headerFilter(), bad), 400, "TENANT_HEADER_INVALID");
    }

    @Test
    void headerModeInvalidSpecialistHeaderIsBadRequest() throws Exception {
        MockHttpServletRequest request = appRequest();
        request.addHeader(TenantContextFilter.HEADER_TENANT_ID, LUMEN.toString());
        request.addHeader(TenantContextFilter.HEADER_SPECIALIST_ID, "x");

        assertProblem(run(headerFilter(), request), 400, "SPECIALIST_HEADER_INVALID");
    }

    // ---- hygiene ---------------------------------------------------------------------------------------------

    @Test
    void contextsAreClearedAfterSuccessAndAfterRejection() throws Exception {
        authenticate(jwt(LUMEN.toString(), List.of("OWNER"), null));
        run(jwtFilter(), appRequest());
        assertThat(ActorContext.current()).isEmpty();
        assertThat(TenantContext.current()).isEmpty();

        authenticate(jwt(LUMEN.toString(), null, null));
        run(jwtFilter(), appRequest());
        assertThat(ActorContext.current()).isEmpty();
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void filterRunsAfterTheSpringSecurityFilterChain() {
        Order order = TenantContextFilter.class.getAnnotation(Order.class);

        assertThat(order).isNotNull();
        assertThat(order.value()).isGreaterThan(SecurityFilterProperties.DEFAULT_FILTER_ORDER);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private TenantContextFilter jwtFilter() {
        return new TenantContextFilter(tenants, catalog, new SecurityProperties(true, false));
    }

    private TenantContextFilter headerFilter() {
        return new TenantContextFilter(tenants, catalog, new SecurityProperties(false, true));
    }

    private static MockHttpServletRequest appRequest() {
        return new MockHttpServletRequest("GET", "/api/app/specialists");
    }

    private static TenantRecord tenant(UUID id, String slug) {
        TenantRecord record = new TenantRecord();
        record.setId(id);
        record.setSlug(slug);
        return record;
    }

    private static Jwt jwt(String tenantId, List<String> roles, String specialistId) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none").subject("user-1");
        if (tenantId != null) {
            builder.claim("tenant_id", tenantId);
        }
        if (roles != null) {
            builder.claim("roles", roles);
        }
        if (specialistId != null) {
            builder.claim("specialist_id", specialistId);
        }
        if (tenantId == null && roles == null && specialistId == null) {
            builder.claim("scope", "openid");
        }
        return builder.build();
    }

    private static void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private Result run(TenantContextFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Result result = new Result(response);
        FilterChain chain = (req, res) -> {
            result.chainCalled = true;
            result.tenant = TenantContext.current().orElse(null);
            result.actor = ActorContext.current().orElse(null);
        };
        filter.doFilter(request, response, chain);
        return result;
    }

    private void assertProblem(Result r, int status, String code) throws Exception {
        assertThat(r.response.getStatus()).isEqualTo(status);
        assertThat(r.response.getContentType()).startsWith("application/problem+json");
        JsonNode body = json.readTree(r.response.getContentAsByteArray());
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("title").asText()).isEqualTo(code);
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("type").asText()).isEqualTo("https://cadence.dev/problems/" + code);
        assertThat(body.get("detail").asText()).isNotBlank();
        assertThat(r.chainCalled).isFalse();
        assertThat(r.actor).isNull();
    }

    private static final class Result {
        final MockHttpServletResponse response;
        boolean chainCalled;
        UUID tenant;
        ActorContext.Actor actor;

        Result(MockHttpServletResponse response) {
            this.response = response;
        }
    }
}

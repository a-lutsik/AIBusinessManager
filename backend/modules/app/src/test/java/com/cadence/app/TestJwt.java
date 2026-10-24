package com.cadence.app;

import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

/** JWT request post-processors mirroring the claims Keycloak puts into Cadence tokens. */
final class TestJwt {

    private TestJwt() {
    }

    /** Owner of {@code tenant}: claims {@code tenant_id} and {@code roles=[OWNER]}. */
    static RequestPostProcessor owner(UUID tenant) {
        return SecurityMockMvcRequestPostProcessors.jwt().jwt(jwt -> jwt
                .subject("owner-" + tenant)
                .claim("tenant_id", tenant.toString())
                .claim("roles", List.of("OWNER")));
    }

    /** Master of {@code tenant}: claims {@code tenant_id}, {@code roles=[MASTER]} and {@code specialist_id}. */
    static RequestPostProcessor master(UUID tenant, UUID specialist) {
        return SecurityMockMvcRequestPostProcessors.jwt().jwt(jwt -> jwt
                .subject("master-" + specialist)
                .claim("tenant_id", tenant.toString())
                .claim("roles", List.of("MASTER"))
                .claim("specialist_id", specialist.toString()));
    }

    /** Valid token that carries only a role: no {@code tenant_id}. */
    static RequestPostProcessor ownerWithoutTenant() {
        return SecurityMockMvcRequestPostProcessors.jwt().jwt(jwt -> jwt
                .subject("owner-no-tenant")
                .claim("roles", List.of("OWNER")));
    }

    /** Valid token with a tenant but no {@code roles} claim. */
    static RequestPostProcessor tenantWithoutRoles(UUID tenant) {
        return SecurityMockMvcRequestPostProcessors.jwt().jwt(jwt -> jwt
                .subject("no-roles")
                .claim("tenant_id", tenant.toString()));
    }
}

package com.cadence.app.web;

import com.cadence.catalog.api.CatalogService;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String HEADER_TENANT_SLUG = "X-Tenant-Slug";
    public static final String HEADER_ACTOR_ROLE = "X-Actor-Role";
    public static final String HEADER_ACTOR_ID = "X-Actor-Id";
    public static final String HEADER_SPECIALIST_ID = "X-Specialist-Id";

    private static final Pattern PUBLIC_SLUG = Pattern.compile("^/api/public/tenants/([^/]+)");

    private final TenantDirectory tenants;
    private final CatalogService catalogService;

    public TenantContextFilter(TenantDirectory tenants, CatalogService catalogService) {
        this.tenants = tenants;
        this.catalogService = catalogService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            resolveTenantId(request).ifPresent(TenantContext::set);
            resolveActor(request).ifPresent(ActorContext::set);
            filterChain.doFilter(request, response);
        } finally {
            ActorContext.clear();
            TenantContext.clear();
        }
    }

    private Optional<UUID> resolveTenantId(HttpServletRequest request) {
        String path = request.getRequestURI();
        Matcher matcher = PUBLIC_SLUG.matcher(path);
        if (matcher.find()) {
            return tenants.findBySlug(matcher.group(1)).map(r -> r.getId());
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String claim = jwt.getClaimAsString("tenant_id");
            if (claim != null && !claim.isBlank()) {
                return Optional.of(UUID.fromString(claim));
            }
        }
        String header = request.getHeader(HEADER_TENANT_ID);
        if (header != null && !header.isBlank()) {
            try {
                return Optional.of(UUID.fromString(header));
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        }
        String slug = request.getHeader(HEADER_TENANT_SLUG);
        if (slug != null && !slug.isBlank()) {
            return tenants.findBySlug(slug).map(r -> r.getId());
        }
        return Optional.empty();
    }

    private Optional<ActorContext.Actor> resolveActor(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String actorId = jwt.getSubject();
            ActorContext.Role role = roleFromJwt(jwt);
            UUID specialistId = specialistIdFromJwt(jwt, role);
            return Optional.of(new ActorContext.Actor(actorId, role, specialistId));
        }
        String roleHeader = request.getHeader(HEADER_ACTOR_ROLE);
        if (roleHeader == null || roleHeader.isBlank()) {
            if (request.getRequestURI().startsWith("/api/public/")) {
                return Optional.of(ActorContext.publicActor());
            }
            return Optional.of(new ActorContext.Actor("local-owner", ActorContext.Role.OWNER, null));
        }
        ActorContext.Role role = ActorContext.Role.valueOf(roleHeader);
        String actorId = Optional.ofNullable(request.getHeader(HEADER_ACTOR_ID)).orElse("local");
        UUID specialistId = Optional.ofNullable(request.getHeader(HEADER_SPECIALIST_ID))
                .filter(s -> !s.isBlank())
                .map(UUID::fromString)
                .orElse(null);
        return Optional.of(new ActorContext.Actor(actorId, role, specialistId));
    }

    private ActorContext.Role roleFromJwt(Jwt jwt) {
        Object roles = jwt.getClaim("roles");
        if (roles instanceof List<?> list) {
            if (list.contains("OWNER")) {
                return ActorContext.Role.OWNER;
            }
            if (list.contains("MASTER")) {
                return ActorContext.Role.MASTER;
            }
        }
        return ActorContext.Role.OWNER;
    }

    private UUID specialistIdFromJwt(Jwt jwt, ActorContext.Role role) {
        String claim = jwt.getClaimAsString("specialist_id");
        if (claim != null && !claim.isBlank()) {
            return UUID.fromString(claim);
        }
        if (role == ActorContext.Role.MASTER && TenantContext.current().isPresent()) {
            return catalogService.findSpecialistByKeycloakUserId(jwt.getSubject()).map(s -> s.id()).orElse(null);
        }
        return null;
    }
}

package com.cadence.app.web;

import com.cadence.app.config.SecurityProperties;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Binds {@link TenantContext} and {@link ActorContext} for one request.
 *
 * <p>Runs <b>after</b> the Spring Security filter chain (security order is {@code -100}, this filter is
 * {@code LOWEST_PRECEDENCE - 20}), because it reads the authenticated JWT from the {@link SecurityContextHolder}.
 *
 * <ul>
 *   <li>{@code /api/public/**}: tenant only from the slug in the path, actor is always PUBLIC. No header is read.</li>
 *   <li>{@code /api/app/**} with OAuth2 (default): tenant, role and specialist only from JWT claims; headers are
 *       ignored. A missing or unusable claim is a 403, never a fallback to OWNER or to a header.</li>
 *   <li>{@code /api/app/**} with header auth (profile {@code dev-headers} only): tenant and role from {@code X-*}
 *       headers.</li>
 * </ul>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String HEADER_ACTOR_ROLE = "X-Actor-Role";
    public static final String HEADER_ACTOR_ID = "X-Actor-Id";
    public static final String HEADER_SPECIALIST_ID = "X-Specialist-Id";

    private static final String PUBLIC_PREFIX = "/api/public";
    private static final String APP_PREFIX = "/api/app";
    private static final Pattern PUBLIC_SLUG = Pattern.compile("^/api/public/tenants/([^/]+)");

    /**
     * Decoded, context-relative path with semicolon content removed: the form Spring Security and Spring MVC match
     * against. Routing on the raw request URI would let {@code /api/%61pp/...} slip past the {@code /api/app} check
     * while still reaching the controller.
     */
    private static final UrlPathHelper URL_PATHS = new UrlPathHelper();

    private final TenantDirectory tenants;
    private final CatalogService catalogService;
    private final SecurityProperties security;

    public TenantContextFilter(TenantDirectory tenants, CatalogService catalogService, SecurityProperties security) {
        this.tenants = tenants;
        this.catalogService = catalogService;
        this.security = security;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String path = URL_PATHS.getPathWithinApplication(request);
            if (isUnder(path, PUBLIC_PREFIX)) {
                bindPublic(path);
            } else if (isUnder(path, APP_PREFIX)) {
                Rejection rejection = security.headerAuthEnabled() ? bindFromHeaders(request) : bindFromJwt();
                if (rejection != null) {
                    writeProblem(response, rejection);
                    return;
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            ActorContext.clear();
            TenantContext.clear();
        }
    }

    private static boolean isUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private void bindPublic(String path) {
        Matcher matcher = PUBLIC_SLUG.matcher(path);
        if (matcher.find()) {
            tenants.findBySlug(matcher.group(1)).ifPresent(t -> TenantContext.set(t.getId()));
        }
        ActorContext.set(ActorContext.publicActor());
    }

    private Rejection bindFromJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return new Rejection(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required");
        }
        UUID tenantId = parseUuid(jwt.getClaimAsString("tenant_id"));
        if (tenantId == null) {
            return new Rejection(HttpStatus.FORBIDDEN, "TENANT_CLAIM_MISSING", "Token has no valid tenant_id claim");
        }
        if (tenants.findById(tenantId).isEmpty()) {
            return new Rejection(HttpStatus.FORBIDDEN, "TENANT_UNKNOWN", "Token tenant is unknown");
        }
        ActorContext.Role role = roleFromJwt(jwt);
        if (role == null) {
            return new Rejection(HttpStatus.FORBIDDEN, "ROLE_MISSING", "Token has no OWNER or MASTER role");
        }
        TenantContext.set(tenantId);
        UUID specialistId = null;
        if (role == ActorContext.Role.MASTER) {
            specialistId = specialistIdFromJwt(jwt);
            if (specialistId == null) {
                return new Rejection(HttpStatus.FORBIDDEN, "SPECIALIST_UNMAPPED", "Master is not mapped to a specialist");
            }
        }
        ActorContext.set(new ActorContext.Actor(jwt.getSubject(), role, specialistId));
        return null;
    }

    /** OWNER wins over MASTER; no known role means no access. */
    private static ActorContext.Role roleFromJwt(Jwt jwt) {
        if (jwt.getClaim("roles") instanceof List<?> list) {
            if (list.contains("OWNER")) {
                return ActorContext.Role.OWNER;
            }
            if (list.contains("MASTER")) {
                return ActorContext.Role.MASTER;
            }
        }
        return null;
    }

    private UUID specialistIdFromJwt(Jwt jwt) {
        String claim = jwt.getClaimAsString("specialist_id");
        if (claim != null && !claim.isBlank()) {
            return parseUuid(claim);
        }
        return catalogService.findSpecialistByKeycloakUserId(jwt.getSubject()).map(s -> s.id()).orElse(null);
    }

    private Rejection bindFromHeaders(HttpServletRequest request) {
        UUID tenantId = parseUuid(request.getHeader(HEADER_TENANT_ID));
        if (tenantId == null) {
            return new Rejection(HttpStatus.BAD_REQUEST, "TENANT_HEADER_INVALID", "X-Tenant-Id header is required and must be a UUID");
        }
        ActorContext.Role role = ActorContext.Role.OWNER;
        String roleHeader = request.getHeader(HEADER_ACTOR_ROLE);
        if (roleHeader != null && !roleHeader.isBlank()) {
            role = switch (roleHeader.trim()) {
                case "OWNER" -> ActorContext.Role.OWNER;
                case "MASTER" -> ActorContext.Role.MASTER;
                default -> null;
            };
            if (role == null) {
                return new Rejection(HttpStatus.BAD_REQUEST, "ROLE_INVALID", "X-Actor-Role must be OWNER or MASTER");
            }
        }
        UUID specialistId = null;
        String specialistHeader = request.getHeader(HEADER_SPECIALIST_ID);
        if (specialistHeader != null && !specialistHeader.isBlank()) {
            specialistId = parseUuid(specialistHeader);
            if (specialistId == null) {
                return new Rejection(HttpStatus.BAD_REQUEST, "SPECIALIST_HEADER_INVALID", "X-Specialist-Id must be a UUID");
            }
        }
        if (role == ActorContext.Role.MASTER && specialistId == null) {
            return new Rejection(HttpStatus.BAD_REQUEST, "SPECIALIST_HEADER_INVALID", "X-Specialist-Id is required for MASTER");
        }
        String actorId = request.getHeader(HEADER_ACTOR_ID);
        TenantContext.set(tenantId);
        ActorContext.set(new ActorContext.Actor(
                actorId == null || actorId.isBlank() ? "local" : actorId,
                role,
                role == ActorContext.Role.MASTER ? specialistId : null
        ));
        return null;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Same shape as {@code ApiExceptionHandler}: ProblemDetail with title=code and a {@code code} property. */
    private static void writeProblem(HttpServletResponse response, Rejection rejection) throws IOException {
        response.setStatus(rejection.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = "{\"type\":\"https://cadence.dev/problems/" + rejection.code()
                + "\",\"title\":\"" + rejection.code()
                + "\",\"status\":" + rejection.status().value()
                + ",\"detail\":\"" + rejection.detail()
                + "\",\"code\":\"" + rejection.code() + "\"}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    /** Code and detail are fixed literals (never request data), so they need no JSON escaping. */
    private record Rejection(HttpStatus status, String code, String detail) {
    }
}

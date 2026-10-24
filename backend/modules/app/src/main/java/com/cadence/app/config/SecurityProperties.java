package com.cadence.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authentication mode of {@code /api/app/**}.
 *
 * <ul>
 *   <li>{@code oauth2Enabled} (default mode): every request needs a valid JWT; tenant and role come from claims only.</li>
 *   <li>{@code headerAuthEnabled} (dev only, profile {@code dev-headers}): tenant/role come from {@code X-*} headers,
 *       OAuth2 must be off.</li>
 * </ul>
 * Exactly one of them must be on.
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(boolean oauth2Enabled, boolean headerAuthEnabled) {

    public void validate() {
        if (!oauth2Enabled && !headerAuthEnabled) {
            throw new IllegalStateException(
                    "No authentication configured: set app.security.oauth2-enabled=true (default) "
                            + "or activate the dev-headers profile (app.security.header-auth-enabled=true)");
        }
        if (oauth2Enabled && headerAuthEnabled) {
            throw new IllegalStateException(
                    "app.security.header-auth-enabled=true requires app.security.oauth2-enabled=false: "
                            + "header authentication is only for the dev-headers profile");
        }
    }
}

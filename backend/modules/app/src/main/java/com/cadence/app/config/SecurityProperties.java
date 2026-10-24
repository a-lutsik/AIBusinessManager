package com.cadence.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Authentication mode of {@code /api/app/**}.
 *
 * <ul>
 *   <li>{@code oauth2Enabled} (default mode): every request needs a valid JWT; tenant and role come from claims only.</li>
 *   <li>{@code headerAuthEnabled} (dev only, profile {@code dev-headers}): tenant/role come from {@code X-*} headers,
 *       OAuth2 must be off.</li>
 * </ul>
 * Exactly one of them must be on, and header authentication is never combined with the {@code prod} profile.
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(boolean oauth2Enabled, boolean headerAuthEnabled) {

    /** Name of the production profile. */
    static final String PROD_PROFILE = "prod";
    /** Name of the local-development profile that switches header authentication on. */
    static final String DEV_HEADERS_PROFILE = "dev-headers";

    public void validate() {
        validate(false, false);
    }

    /**
     * Validates the mode and the profiles together. Profile order does not matter: with {@code prod,dev-headers} the
     * dev profile would override {@code prod}'s {@code oauth2-enabled=true} (last profile wins), so the combination is
     * refused outright instead of relying on the resolved flags.
     */
    public void validate(Environment env) {
        validate(env.acceptsProfiles(Profiles.of(PROD_PROFILE)), env.acceptsProfiles(Profiles.of(DEV_HEADERS_PROFILE)));
    }

    void validate(boolean prodProfile, boolean devHeadersProfile) {
        if (prodProfile && (headerAuthEnabled || devHeadersProfile)) {
            throw new IllegalStateException(
                    "The prod profile must not be combined with header authentication "
                            + "(profile dev-headers / app.security.header-auth-enabled=true): "
                            + "anyone could then act as any tenant and role with plain X-* headers. "
                            + "Remove dev-headers from SPRING_PROFILES_ACTIVE.");
        }
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

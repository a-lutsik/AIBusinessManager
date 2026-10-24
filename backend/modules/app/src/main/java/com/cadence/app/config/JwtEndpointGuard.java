package com.cadence.app.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Fails the startup when the OAuth2 resource server points at an unusable issuer or key set. Without it a missing
 * {@code APP_SECURITY_ISSUER_URI} / {@code APP_SECURITY_JWK_SET_URI} in the {@code prod} profile leaves the literal
 * {@code ${...}} (the Binder keeps unresolved placeholders), Spring Boot still builds the decoder, the application
 * starts and then rejects every token.
 *
 * <p>Rules (only while {@code app.security.oauth2-enabled=true}): {@code issuer-uri} is required; {@code jwk-set-uri}
 * is required in the {@code prod} profile (the container reaches Keycloak over the internal network) and optional
 * elsewhere (OIDC discovery on the issuer); every value that is set must be an absolute http(s) URI without an
 * unresolved placeholder.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class JwtEndpointGuard {

    static final String ISSUER_URI = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    static final String JWK_SET_URI = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri";

    public JwtEndpointGuard(SecurityProperties props, Environment env) {
        if (!props.oauth2Enabled()) {
            return;
        }
        // Binder, not Environment#getProperty: the latter throws on an unresolved placeholder, the former keeps it.
        Binder binder = Binder.get(env);
        boolean prod = env.acceptsProfiles(Profiles.of(SecurityProperties.PROD_PROFILE));
        check(ISSUER_URI, "APP_SECURITY_ISSUER_URI", binder.bind(ISSUER_URI, String.class).orElse(null), true);
        check(JWK_SET_URI, "APP_SECURITY_JWK_SET_URI", binder.bind(JWK_SET_URI, String.class).orElse(null), prod);
    }

    static void check(String property, String envVar, String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new IllegalStateException(property + " is not set: configure " + envVar
                        + " with an absolute http(s) URL, otherwise every token would be rejected");
            }
            return;
        }
        String uri = value.strip();
        if (uri.contains("${")) {
            throw new IllegalStateException(property + " contains the unresolved placeholder '" + uri + "': set " + envVar);
        }
        if (!isAbsoluteHttpUri(uri)) {
            throw new IllegalStateException(property + " must be an absolute http(s) URL, but is '" + uri + "' (" + envVar + ")");
        }
    }

    private static boolean isAbsoluteHttpUri(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }
}

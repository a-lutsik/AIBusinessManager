package com.cadence.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup guards against the real {@code application*.yml} files: profiles are resolved by Spring Boot's config data
 * loader exactly as in a running application (no mocked flags), in both orders where order could matter.
 */
class SecurityStartupProfilesTest {

    private static final String[] PROD_ENV = {
            "APP_SECURITY_ISSUER_URI=https://auth.example.com/realms/cadence",
            "APP_SECURITY_JWK_SET_URI=http://keycloak:8081/realms/cadence/protocol/openid-connect/certs",
            "SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/cadence",
            "APP_CORS_ALLOWED_ORIGINS=https://console.example.com"
    };

    private static ApplicationContextRunner runner(String profiles, String... extra) {
        String[] all = new String[PROD_ENV.length + extra.length + 1];
        all[0] = "spring.profiles.active=" + profiles;
        System.arraycopy(PROD_ENV, 0, all, 1, PROD_ENV.length);
        System.arraycopy(extra, 0, all, 1 + PROD_ENV.length, extra.length);
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Guards.class)
                .withPropertyValues(all);
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage();
    }

    // --- finding 1: prod + dev-headers, either order ---

    @Test
    void prodThenDevHeadersFailsAndTheYamlIsReallyLastWins() {
        runner("prod,dev-headers").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("prod profile", "dev-headers");
        });
    }

    @Test
    void devHeadersThenProdFails() {
        runner("dev-headers,prod").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("prod profile", "dev-headers");
        });
    }

    @Test
    void whichProfileWinsDependsOnOrderSoOrderCannotBeTheDefence() {
        // Documents the premise of the guard: the resolved flags differ by order, hence the explicit profile check.
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod,dev-headers").run(context -> {
                    assertThat(context.getEnvironment().getProperty("app.security.header-auth-enabled")).isEqualTo("true");
                    assertThat(context.getEnvironment().getProperty("app.security.oauth2-enabled")).isEqualTo("false");
                });
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=dev-headers,prod").run(context -> {
                    assertThat(context.getEnvironment().getProperty("app.security.header-auth-enabled")).isEqualTo("false");
                    assertThat(context.getEnvironment().getProperty("app.security.oauth2-enabled")).isEqualTo("true");
                });
    }

    @Test
    void prodAloneStarts() {
        runner("prod").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void devHeadersAloneStarts() {
        runner("dev-headers").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void defaultProfileStartsWithOauth2() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Guards.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void prodWithHeaderAuthFlagFromTheEnvironmentFails() {
        runner("prod", "app.security.oauth2-enabled=false", "app.security.header-auth-enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("prod profile");
        });
    }

    // --- finding 3: issuer / jwk-set URIs ---

    @Test
    void prodWithoutIssuerUriFailsInsteadOfStartingWithALiteralPlaceholder() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Guards.class)
                .withPropertyValues("spring.profiles.active=prod",
                        "APP_SECURITY_JWK_SET_URI=http://keycloak:8081/certs",
                        "SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/cadence",
                        "APP_CORS_ALLOWED_ORIGINS=https://console.example.com")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure()))
                            .contains("spring.security.oauth2.resourceserver.jwt.issuer-uri", "APP_SECURITY_ISSUER_URI", "${");
                });
    }

    @Test
    void prodWithoutJwkSetUriFails() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Guards.class)
                .withPropertyValues("spring.profiles.active=prod",
                        "APP_SECURITY_ISSUER_URI=https://auth.example.com/realms/cadence",
                        "SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/cadence",
                        "APP_CORS_ALLOWED_ORIGINS=https://console.example.com")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure()))
                            .contains("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", "APP_SECURITY_JWK_SET_URI");
                });
    }

    @Test
    void prodWithBlankOrMalformedUrisFails() {
        for (String bad : new String[]{"", "   ", "auth.example.com/realms/cadence", "ftp://auth.example.com", "https://", "//auth.example.com", "not a uri"}) {
            runner("prod", "APP_SECURITY_ISSUER_URI=" + bad).run(context -> {
                assertThat(context).as("issuer '" + bad + "'").hasFailed();
                assertThat(rootMessage(context.getStartupFailure())).contains("issuer-uri");
            });
            runner("prod", "APP_SECURITY_JWK_SET_URI=" + bad).run(context -> {
                assertThat(context).as("jwk '" + bad + "'").hasFailed();
                assertThat(rootMessage(context.getStartupFailure())).contains("jwk-set-uri");
            });
        }
    }

    @Test
    void devDefaultsPassWithoutJwkSetUri() {
        // application.yml: issuer-uri localhost default, no jwk-set-uri (OIDC discovery).
        runner("dev-headers").run(context -> assertThat(context).hasNotFailed());
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Guards.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void defaultProfileWithUnresolvedIssuerFails() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Guards.class)
                .withPropertyValues("spring.security.oauth2.resourceserver.jwt.issuer-uri=${NOT_DEFINED_ANYWHERE}")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure())).contains("issuer-uri", "${");
                });
    }

    @Test
    void uriChecksAreSkippedWhenOauth2IsOff() {
        runner("dev-headers", "spring.security.oauth2.resourceserver.jwt.issuer-uri=").run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration
    @Import({SecurityModeGuard.class, JwtEndpointGuard.class})
    static class Guards {
    }
}

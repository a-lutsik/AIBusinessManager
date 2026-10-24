package com.cadence.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsPropertiesTest {

    /** Sources in precedence order, first wins (like Spring's property sources). */
    @SafeVarargs
    private static CorsProperties bind(Map<String, Object>... sources) {
        List<ConfigurationPropertySource> list = java.util.Arrays.stream(sources)
                .<ConfigurationPropertySource>map(MapConfigurationPropertySource::new).toList();
        Binder binder = new Binder(list);
        return binder.bind("app.cors", CorsProperties.class).orElseGet(() -> new CorsProperties(null, false));
    }

    @Test
    void commaSeparatedValueBindsToSeveralOrigins() {
        CorsProperties props = bind(Map.of("app.cors.allowed-origins", "https://console.example.com, https://*.example.org ,"));

        assertThat(props.allowedOrigins()).containsExactly("https://console.example.com", "https://*.example.org");
        assertThat(props.allowCredentials()).isFalse();
    }

    @Test
    void listFormBindsToSeveralOrigins() {
        CorsProperties props = bind(Map.of(
                "app.cors.allowed-origins[0]", "http://localhost:*",
                "app.cors.allowed-origins[1]", "http://127.0.0.1:*"));

        assertThat(props.allowedOrigins()).containsExactly("http://localhost:*", "http://127.0.0.1:*");
    }

    @Test
    void profileScalarReplacesBaseIndexedList() {
        // application-prod.yml (scalar from the env var) wins over the dev list of application.yml; no merging.
        CorsProperties props = bind(
                Map.of("app.cors.allowed-origins", "https://console.example.com"),
                Map.of("app.cors.allowed-origins[0]", "http://localhost:*", "app.cors.allowed-origins[1]", "http://127.0.0.1:*"));

        assertThat(props.allowedOrigins()).containsExactly("https://console.example.com");
    }

    @Test
    void missingOrBlankValueMeansNoOrigins() {
        assertThat(bind(Map.of()).allowedOrigins()).isEmpty();
        assertThat(bind(Map.of("app.cors.allowed-origins", "")).allowedOrigins()).isEmpty();
        assertThat(new CorsProperties(null, false).allowedOrigins()).isEmpty();
    }

    @Test
    void unresolvedPlaceholderIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new CorsProperties(List.of("${APP_CORS_ALLOWED_ORIGINS}"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_CORS_ALLOWED_ORIGINS");
    }

    @Test
    void anyHostPatternWithCredentialsIsRejected() {
        for (String pattern : List.of("*", "*:*", "https://*", "http://*:8080", " * ")) {
            assertThatThrownBy(() -> new CorsProperties(List.of("https://ok.example.com", pattern), true))
                    .as(pattern)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("any website");
        }
    }

    @Test
    void explicitAndSubdomainPatternsWithCredentialsAreAccepted() {
        CorsProperties props = new CorsProperties(
                List.of("https://console.example.com", "https://*.example.com", "https://*.eu.example.com",
                        "http://localhost:3000", "https://console.example.com:8443", "https://*.example.com:8443",
                        "https://*.example.co.uk", "http://[::1]:3000"), true);

        assertThat(props.allowedOrigins()).hasSize(8);
    }

    @Test
    void wildcardInFrontOfAPublicSuffixWithCredentialsIsRejected() {
        for (String pattern : List.of("https://*.com", "http://*.com:8080", "*.com", "https://*.uk", "https://*.co.uk",
                "https://*.CO.UK", "https://*.com.au", "https://*.github.io", "https://*.vercel.app", "https://*.",
                "https://*..com")) {
            assertThatThrownBy(() -> new CorsProperties(List.of("https://ok.example.com", pattern), true))
                    .as(pattern)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.cors.allowed-origins")
                    .hasMessageContaining("allow-credentials=true");
        }
    }

    @Test
    void wildcardInSchemePortOrMiddleOfTheHostWithCredentialsIsRejected() {
        for (String pattern : List.of("*://console.example.com", "ht*://console.example.com", "http://localhost:*",
                "https://console.example.com:*", "https://console.example.com:80*", "https://con*.example.com",
                "https://*.*.example.com", "https://foo.*.example.com", "https://*example.com", "https://*.example.*")) {
            assertThatThrownBy(() -> new CorsProperties(List.of(pattern), true))
                    .as(pattern)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("allow-credentials=true");
        }
    }

    @Test
    void sameBroadPatternsWithoutCredentialsAreNotRefusedByTheCheck() {
        List<String> patterns = List.of("https://*.com", "https://*.co.uk", "http://localhost:*", "*://x.example.com");

        assertThat(new CorsProperties(patterns, false).allowedOrigins()).containsExactlyElementsOf(patterns);
    }

    @Test
    void anyHostPatternWithoutCredentialsIsNotRefusedByTheCheck() {
        assertThat(new CorsProperties(List.of("*"), false).allowedOrigins()).containsExactly("*");
    }

    @Test
    void startupFailsWhenWildcardIsCombinedWithCredentials() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues("app.cors.allowed-origins=*", "app.cors.allow-credentials=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(rootMessage(context.getStartupFailure())).contains("app.cors.allowed-origins", "any website");
                });
    }

    @Test
    void startupSucceedsWithExplicitOrigin() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues("app.cors.allowed-origins=https://console.example.com,https://admin.example.com")
                .run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                        .containsExactly("https://console.example.com", "https://admin.example.com"));
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage();
    }

    @Configuration
    @EnableConfigurationProperties(CorsProperties.class)
    static class PropertiesOnly {
    }
}

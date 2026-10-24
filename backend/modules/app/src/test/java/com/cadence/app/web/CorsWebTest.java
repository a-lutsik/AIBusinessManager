package com.cadence.app.web;

import com.cadence.app.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real security chains with configured CORS origins (comma-separated, as the prod env var supplies them), no database.
 */
@WebMvcTest(value = AppApiController.class, properties =
        "app.cors.allowed-origins=https://console.cadence.test, https://*.studio.cadence.test")
@Import(SecurityConfig.class)
class CorsWebTest extends CorsWebTestBase {

    private static final String ALLOWED = "https://console.cadence.test";
    private static final String ALLOWED_BY_PATTERN = "https://lumen.studio.cadence.test";
    private static final String FOREIGN = "https://evil.example";

    @Test
    void preflightFromListedOriginIsAnsweredAndEchoesTheOrigin() throws Exception {
        mvc.perform(options("/api/app/me")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void secondListedPatternIsAllowedToo() throws Exception {
        mvc.perform(options("/api/app/me")
                        .header(HttpHeaders.ORIGIN, ALLOWED_BY_PATTERN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_BY_PATTERN));
    }

    @Test
    void preflightFromUnlistedOriginIsForbiddenWithoutAllowOrigin() throws Exception {
        mvc.perform(options("/api/app/me")
                        .header(HttpHeaders.ORIGIN, FOREIGN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void preflightWithUnlistedHeaderIsForbidden() throws Exception {
        mvc.perform(options("/api/app/me")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-something-else"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void preflightWithUnlistedMethodIsForbidden() throws Exception {
        mvc.perform(options("/api/app/me")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "TRACE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicEndpointFromUnlistedOriginGetsNoAllowOrigin() throws Exception {
        mvc.perform(get("/api/public/health").header(HttpHeaders.ORIGIN, FOREIGN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void publicEndpointFromListedOriginEchoesOriginAndExposesRetryAfter() throws Exception {
        mvc.perform(get("/api/public/health").header(HttpHeaders.ORIGIN, ALLOWED))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Retry-After"));
    }

    @Test
    void requestWithoutOriginIsNotAffected() throws Exception {
        String allowOrigin = mvc.perform(get("/api/public/health")).andReturn().getResponse()
                .getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(allowOrigin).isNull();
    }
}

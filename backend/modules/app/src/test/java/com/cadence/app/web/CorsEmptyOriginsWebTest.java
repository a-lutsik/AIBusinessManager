package com.cadence.app.web;

import com.cadence.app.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fail closed: with no configured origins no cross-origin request is allowed, not even from localhost. */
@WebMvcTest(value = AppApiController.class, properties = "app.cors.allowed-origins=")
@Import(SecurityConfig.class)
class CorsEmptyOriginsWebTest extends CorsWebTestBase {

    @Test
    void emptyListAllowsNoPreflight() throws Exception {
        for (String origin : new String[] {"https://console.cadence.test", "http://localhost:8082"}) {
            mvc.perform(options("/api/app/me")
                            .header(HttpHeaders.ORIGIN, origin)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    @Test
    void emptyListGivesNoAllowOriginOnSimpleRequests() throws Exception {
        mvc.perform(get("/api/public/health").header(HttpHeaders.ORIGIN, "http://localhost:8082"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}

package com.cadence.app;

import com.cadence.app.seed.DemoIds;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The {@code dev-headers} profile is the only place where {@code X-*} headers establish identity. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev-headers")
class DevHeadersIT extends AbstractTimescaleIT {

    @Autowired
    MockMvc mvc;

    @Test
    void headersAuthenticateInDevProfile() throws Exception {
        mvc.perform(get("/api/app/me").header("X-Tenant-Id", DemoIds.LUMEN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role", is("OWNER")))
                .andExpect(jsonPath("$.tenantId", is(DemoIds.LUMEN.toString())));
    }

    @Test
    void invalidRoleHeaderIsBadRequest() throws Exception {
        mvc.perform(get("/api/app/me")
                        .header("X-Tenant-Id", DemoIds.LUMEN.toString())
                        .header("X-Actor-Role", "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ROLE_INVALID")));
    }

    @Test
    void missingTenantHeaderIsBadRequest() throws Exception {
        mvc.perform(get("/api/app/me"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("TENANT_HEADER_INVALID")));
    }
}

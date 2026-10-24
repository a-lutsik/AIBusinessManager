package com.cadence.app;

import com.cadence.app.seed.DemoIds;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;


import static com.cadence.app.TestJwt.master;
import static com.cadence.app.TestJwt.owner;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@RelaxedPublicRateLimits
@AutoConfigureMockMvc
class BookingCoreIT extends AbstractTimescaleIT {

    @Autowired
    MockMvc mvc;

    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    @Test
    void publicGoldenPathCreatesCrmProfileAndCalendarVisit() throws Exception {
        mvc.perform(get("/api/public/tenants/lumen-studio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName", is("Lumen Studio")))
                .andExpect(jsonPath("$.timezone", is("Asia/Tbilisi")));

        mvc.perform(get("/api/public/tenants/lumen-studio/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThan(3)));

        mvc.perform(get("/api/public/tenants/lumen-studio/services/" + DemoIds.HAIRCUT + "/specialists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id", notNullValue()));

        String firstSlot = TestSlots.firstFree(mvc, mapper, DemoIds.HAIRCUT, DemoIds.NINO, 2);
        String start = firstSlot;

        MvcResult created = mvc.perform(post("/api/public/tenants/lumen-studio/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceId": "%s",
                                  "specialistId": "%s",
                                  "serviceStart": "%s",
                                  "phone": "555111222",
                                  "name": "Mariam Test",
                                  "locale": "en",
                                  "marketingConsent": true
                                }
                                """.formatted(DemoIds.HAIRCUT, DemoIds.NINO, start)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.status").exists())
                .andReturn();
        JsonNode booking = mapper.readTree(created.getResponse().getContentAsByteArray());
        String id = booking.get("id").asText();
        String token = booking.get("accessToken").asText();

        mvc.perform(get("/api/public/bookings/" + id).param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id)));

        mvc.perform(get("/api/public/bookings/" + id + "/calendar.ics").param("token", token))
                .andExpect(status().isOk());

        // Token write path without a slug: tenant resolved via booking_token_lookup, then RLS-scoped writes.
        mvc.perform(post("/api/public/bookings/" + id + "/cancel").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED_BY_CLIENT")));

        mvc.perform(get("/api/app/clients").with(owner(DemoIds.LUMEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.displayName == 'Mariam Test')]", notNullValue()));
    }

    @Test
    void newClientPendingWhenConfirmationRequired() throws Exception {
        MvcResult rulesRes = mvc.perform(get("/api/app/rules").with(owner(DemoIds.LUMEN)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rules = mapper.readTree(rulesRes.getResponse().getContentAsByteArray());
        var body = mapper.createObjectNode();
        rules.fields().forEachRemaining(e -> body.set(e.getKey(), e.getValue()));
        body.put("newClientRequiresConfirmation", true);
        mvc.perform(put("/api/app/rules")
                        .with(owner(DemoIds.LUMEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isOk());

        String firstSlot = TestSlots.firstFree(mvc, mapper, DemoIds.CONSULT, DemoIds.NINO, 3);
        String start = firstSlot;
        mvc.perform(post("/api/public/tenants/lumen-studio/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceId": "%s",
                                  "specialistId": "%s",
                                  "serviceStart": "%s",
                                  "phone": "+995555999001",
                                  "name": "Pending Guest",
                                  "locale": "en",
                                  "marketingConsent": false
                                }
                                """.formatted(DemoIds.CONSULT, DemoIds.NINO, start)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PENDING")));
    }

    @Test
    void secondTenantIsIsolated() throws Exception {
        mvc.perform(get("/api/public/tenants/aura-atelier"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName", is("Aura Atelier")))
                .andExpect(jsonPath("$.currencyCode", is("CZK")));
        mvc.perform(get("/api/app/specialists").with(owner(DemoIds.AURA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName", is("Klára Svobodová")));
    }

    @Test
    void masterCannotReadAnotherSpecialistsAppointment() throws Exception {
        var calendar = mvc.perform(get("/api/app/calendar")
                        .with(owner(DemoIds.LUMEN))
                        .param("from", "2020-01-01T00:00:00Z")
                        .param("to", "2030-01-01T00:00:00Z")
                        .param("specialistId", DemoIds.ANNA.toString()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode visits = mapper.readTree(calendar.getResponse().getContentAsByteArray());
        assertThat(visits.size()).as("demo seed must contain a visit of another specialist (Anna)").isGreaterThan(0);
        String foreignId = visits.get(0).get("id").asText();

        mvc.perform(get("/api/app/appointments/" + foreignId)
                        .with(master(DemoIds.LUMEN, DemoIds.NINO)))
                .andExpect(status().isForbidden());
    }
}

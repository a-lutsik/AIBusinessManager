package com.aibusinessmanager.app;

import com.aibusinessmanager.app.seed.DemoIds;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
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

        LocalDate from = LocalDate.now(ZoneId.of("Asia/Tbilisi")).plusDays(2);
        MvcResult slots = mvc.perform(get("/api/public/tenants/lumen-studio/slots")
                        .param("serviceId", DemoIds.HAIRCUT.toString())
                        .param("specialistId", DemoIds.NINO.toString())
                        .param("from", from.toString())
                        .param("to", from.plusDays(5).toString()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode slotArray = mapper.readTree(slots.getResponse().getContentAsByteArray());
        org.junit.jupiter.api.Assumptions.assumeTrue(slotArray.size() > 0, "Need a free slot in demo schedule");
        String start = slotArray.get(0).asText();

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

        mvc.perform(get("/api/app/clients").header("X-Tenant-Id", DemoIds.LUMEN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.displayName == 'Mariam Test')]", notNullValue()));
    }

    @Test
    void newClientPendingWhenConfirmationRequired() throws Exception {
        MvcResult rulesRes = mvc.perform(get("/api/app/rules").header("X-Tenant-Id", DemoIds.LUMEN.toString()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rules = mapper.readTree(rulesRes.getResponse().getContentAsByteArray());
        var body = mapper.createObjectNode();
        rules.fields().forEachRemaining(e -> body.set(e.getKey(), e.getValue()));
        body.put("newClientRequiresConfirmation", true);
        mvc.perform(put("/api/app/rules")
                        .header("X-Tenant-Id", DemoIds.LUMEN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isOk());

        LocalDate from = LocalDate.now(ZoneId.of("Asia/Tbilisi")).plusDays(3);
        MvcResult slots = mvc.perform(get("/api/public/tenants/lumen-studio/slots")
                        .param("serviceId", DemoIds.CONSULT.toString())
                        .param("specialistId", DemoIds.NINO.toString())
                        .param("from", from.toString())
                        .param("to", from.plusDays(4).toString()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode slotArray = mapper.readTree(slots.getResponse().getContentAsByteArray());
        org.junit.jupiter.api.Assumptions.assumeTrue(slotArray.size() > 0);
        String start = slotArray.get(0).asText();
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
        mvc.perform(get("/api/app/specialists").header("X-Tenant-Id", DemoIds.AURA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName", is("Klára Svobodová")));
    }

    @Test
    void masterCannotReadAnotherSpecialistsAppointment() throws Exception {
        var calendar = mvc.perform(get("/api/app/calendar")
                        .header("X-Tenant-Id", DemoIds.LUMEN.toString())
                        .header("X-Actor-Role", "OWNER")
                        .param("from", "2020-01-01T00:00:00Z")
                        .param("to", "2030-01-01T00:00:00Z")
                        .param("specialistId", DemoIds.ANNA.toString()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode visits = mapper.readTree(calendar.getResponse().getContentAsByteArray());
        org.junit.jupiter.api.Assumptions.assumeTrue(visits.size() > 0);
        String foreignId = visits.get(0).get("id").asText();

        mvc.perform(get("/api/app/appointments/" + foreignId)
                        .header("X-Tenant-Id", DemoIds.LUMEN.toString())
                        .header("X-Actor-Role", "MASTER")
                        .header("X-Specialist-Id", DemoIds.NINO.toString())
                        .header("X-Actor-Id", "nino"))
                .andExpect(status().isForbidden());
    }
}

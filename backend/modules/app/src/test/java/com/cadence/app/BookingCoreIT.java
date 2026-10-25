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

    /**
     * Finding 4 and decision D (stage 03): a public booking whose phone belongs to an existing client shows on the
     * manage link only what the visitor typed (no stored name, phone or trust), and it may add consent but never
     * revokes it, keeps the stored locale and the stored name.
     */
    @Test
    void publicLinkOnAPhoneOfAnExistingClientShowsOnlyWhatTheVisitorTyped() throws Exception {
        String phone = "+9955557" + String.format("%05d", (int) (Math.random() * 100_000));
        String storedName = "Owner Stored " + java.util.UUID.randomUUID().toString().substring(0, 8);
        MvcResult ownerCreated = mvc.perform(post("/api/app/appointments").with(owner(DemoIds.LUMEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"specialistId": "%s", "serviceId": "%s", "serviceStart": "%s", "phone": "%s",
                                 "displayName": "%s", "locale": "ka", "marketingConsent": true, "consentVersion": "owner-v1",
                                 "source": "CONSOLE", "statusOverride": "CONFIRMED", "skipNoticeAndHorizon": false}
                                """.formatted(DemoIds.NINO, DemoIds.HAIRCUT,
                                TestSlots.firstFree(mvc, mapper, DemoIds.HAIRCUT, DemoIds.NINO, 3), phone, storedName)))
                .andExpect(status().isOk())
                .andReturn();
        String clientId = mapper.readTree(ownerCreated.getResponse().getContentAsByteArray()).get("clientId").asText();

        MvcResult booked = mvc.perform(post("/api/public/tenants/lumen-studio/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId": "%s", "specialistId": "%s", "serviceStart": "%s", "phone": "%s",
                                 "name": "Somebody Typing", "locale": "en", "marketingConsent": false}
                                """.formatted(DemoIds.HAIRCUT, DemoIds.NINO,
                                TestSlots.firstFree(mvc, mapper, DemoIds.HAIRCUT, DemoIds.NINO, 3), phone)))
                .andExpect(status().isOk())
                .andReturn();
        String bookedRaw = booked.getResponse().getContentAsString();
        assertThat(bookedRaw).doesNotContain(storedName).doesNotContain(phone.substring(1));
        JsonNode booking = mapper.readTree(bookedRaw);
        String id = booking.get("id").asText();
        String token = booking.get("accessToken").asText();
        // Decision D first: the booking with consent false revoked nothing.
        assertThat(ownerCard(clientId).get("marketingConsent").asBoolean()).as("false never revokes").isTrue();

        // Review minor 3: the manage link withdraws consent, only for its own visit.
        mvc.perform(post("/api/public/bookings/" + java.util.UUID.randomUUID() + "/consent/withdraw").param("token", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("TOKEN_INVALID")));
        assertThat(ownerCard(clientId).get("marketingConsent").asBoolean()).as("wrong visit id withdraws nothing").isTrue();
        mvc.perform(post("/api/public/bookings/" + id + "/consent/withdraw").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketingConsent", is(false)));
        assertThat(ownerCard(clientId).get("marketingConsent").asBoolean()).isFalse();

        for (MvcResult res : new MvcResult[]{
                mvc.perform(get("/api/public/bookings/" + id).param("token", token)).andExpect(status().isOk()).andReturn(),
                mvc.perform(post("/api/public/bookings/" + id + "/cancel").param("token", token)).andExpect(status().isOk()).andReturn()}) {
            String raw = res.getResponse().getContentAsString();
            JsonNode visit = mapper.readTree(raw);
            assertThat(visit.get("enteredName").asText()).isEqualTo("Somebody Typing");
            assertThat(visit.has("clientDisplayName")).isFalse();
            assertThat(visit.has("clientPhone")).isFalse();
            assertThat(visit.has("trustLevel")).isFalse();
            assertThat(raw).doesNotContain(storedName).doesNotContain(phone.substring(1));
        }

        // Decision D: the stored client keeps its name, locale and consent; the owner sees the typed/stored hint.
        JsonNode card = mapper.readTree(mvc.perform(get("/api/app/clients/" + clientId).with(owner(DemoIds.LUMEN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(card.get("client").get("displayName").asText()).isEqualTo(storedName);
        assertThat(card.get("client").get("preferredLocale").asText()).isEqualTo("ka");

        // A later booking with consent true adds it again (decision D); the owner withdraws it on request, a master cannot.
        mvc.perform(post("/api/public/tenants/lumen-studio/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId": "%s", "specialistId": "%s", "serviceStart": "%s", "phone": "%s",
                                 "name": "Somebody Typing", "locale": "en", "marketingConsent": true}
                                """.formatted(DemoIds.HAIRCUT, DemoIds.NINO,
                                TestSlots.firstFree(mvc, mapper, DemoIds.HAIRCUT, DemoIds.NINO, 3), phone)))
                .andExpect(status().isOk());
        assertThat(ownerCard(clientId).get("marketingConsent").asBoolean()).as("true adds").isTrue();
        mvc.perform(post("/api/app/clients/" + clientId + "/consent/withdraw").with(master(DemoIds.LUMEN, DemoIds.NINO)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/app/clients/" + clientId + "/consent/withdraw").with(owner(DemoIds.LUMEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketingConsent", is(false)));
        assertThat(ownerCard(clientId).get("marketingConsent").asBoolean()).isFalse();
        assertThat(ownerCard(clientId).get("displayName").asText()).isEqualTo(storedName);
        JsonNode ownerVisit = mapper.readTree(mvc.perform(get("/api/app/appointments/" + id).with(owner(DemoIds.LUMEN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).get("appointment");
        assertThat(ownerVisit.get("enteredName").asText()).isEqualTo("Somebody Typing / " + storedName);
    }

    private JsonNode ownerCard(String clientId) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/app/clients/" + clientId).with(owner(DemoIds.LUMEN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).get("client");
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

package com.cadence.app;

import com.cadence.platform.tenant.TenantDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.cadence.app.TestJwt.master;
import static com.cadence.app.TestJwt.masterBySubject;
import static com.cadence.app.TestJwt.owner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The console catalog API (stage 07): refused input answers 4xx with a code instead of 500, writes are owner-only,
 * another tenant's ids behave like unknown ids, and a deactivated service or master stops offering slots while the
 * existing visits and their snapshots stay. Every test works in tenants of its own: the demo seed is not touched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RelaxedPublicRateLimits
class CatalogEditingIT extends AbstractTimescaleIT {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tbilisi");
    private static final AtomicInteger PHONE = new AtomicInteger(new Random().nextInt(100_000));

    @Autowired
    MockMvc mvc;
    @Autowired
    TenantDirectory tenants;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private UUID tenant;
    private String slug;
    private UUID otherTenant;

    @BeforeEach
    void tenants() {
        tenant = UUID.randomUUID();
        slug = "catalog-" + tenant;
        tenants.upsert(tenant, slug, "Catalog Studio", ZONE.getId(), "GEL", "GE");
        otherTenant = UUID.randomUUID();
        tenants.upsert(otherTenant, "catalog-" + otherTenant, "Other Studio", ZONE.getId(), "GEL", "GE");
    }

    // --- validation ---

    @Test
    void anInvalidServiceIsRefusedWithItsCodeAndNothingIsStored() throws Exception {
        assertRefused(Map.of("name", " "), 400, "INVALID_NAME");
        assertRefused(Map.of("name", "x".repeat(257)), 400, "INVALID_NAME");
        assertRefused(Map.of("description", "x".repeat(2049)), 400, "INVALID_DESCRIPTION");
        assertRefused(Map.of("durationMinutes", 0), 400, "INVALID_DURATION");
        assertRefused(Map.of("durationMinutes", -5), 400, "INVALID_DURATION");
        assertRefused(Map.of("durationMinutes", 1441), 400, "INVALID_DURATION");
        assertRefused(Map.of("priceMinor", -1), 400, "INVALID_PRICE");
        assertRefused(Map.of("bufferBeforeMinutes", -1), 400, "INVALID_BUFFER");
        assertRefused(Map.of("bufferAfterMinutes", 1441), 400, "INVALID_BUFFER");
        assertRefused(Map.of("color", "red"), 400, "INVALID_COLOR");
        assertRefused(Map.of("color", "#12345"), 400, "INVALID_COLOR");
        assertRefused(Collections.singletonMap("name", null), 400, "INVALID_NAME");
        assertRefused(Collections.singletonMap("color", null), 400, "INVALID_COLOR");

        assertThat(json(get("/api/app/services"), owner(tenant))).isEmpty();
    }

    @Test
    void aBodyThatCannotBeReadIsRefusedWithACode() throws Exception {
        assertCode(put("/api/app/services").contentType(MediaType.APPLICATION_JSON).content("{not json"), owner(tenant), 400, "INVALID_REQUEST_BODY");
        assertCode(put("/api/app/services").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Cut\",\"durationMinutes\":\"soon\"}"), owner(tenant), 400, "INVALID_REQUEST_BODY");
        UUID specialist = specialist(tenant, "Nino");
        assertCode(put("/api/app/specialists/" + specialist + "/exceptions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"31.12.2026\",\"kind\":\"DAY_OFF\"}"), owner(tenant), 400, "INVALID_REQUEST_BODY");
        assertCode(get("/api/app/specialists/not-a-uuid/matrix"), owner(tenant), 400, "INVALID_PARAMETER");
    }

    /** A value the owner did not send must not become a price of 0, or an inactive service. */
    @Test
    void aMissingOrFractionalValueIsRefusedInsteadOfBecomingZeroOrFalse() throws Exception {
        UUID specialist = specialist(tenant, "Nino");
        UUID service = service(tenant, "Cut");
        for (String field : List.of("durationMinutes", "priceMinor", "bufferBeforeMinutes", "bufferAfterMinutes", "active", "publicVisible")) {
            Map<String, Object> missing = serviceBody("Missing");
            missing.remove(field);
            assertCode(putJson("/api/app/services", missing), owner(tenant), 400, "INVALID_REQUEST_BODY");
            Map<String, Object> blank = serviceBody("Blank");
            blank.put(field, null);
            assertCode(putJson("/api/app/services", blank), owner(tenant), 400, "INVALID_REQUEST_BODY");
        }
        Map<String, Object> fractional = serviceBody("Fractional");
        fractional.put("priceMinor", 80.5);
        assertCode(putJson("/api/app/services", fractional), owner(tenant), 400, "INVALID_REQUEST_BODY");
        Map<String, Object> huge = serviceBody("Huge");
        huge.put("priceMinor", 3_000_000_000L);
        assertCode(putJson("/api/app/services", huge), owner(tenant), 400, "INVALID_REQUEST_BODY");

        Map<String, Object> noActive = specialistBody("Nino", "#0f766e");
        noActive.remove("active");
        assertCode(putJson("/api/app/specialists", noActive), owner(tenant), 400, "INVALID_REQUEST_BODY");

        String matrix = "/api/app/specialists/" + specialist + "/matrix";
        assertCode(putJson(matrix, Map.of("serviceId", service.toString())), owner(tenant), 400, "INVALID_REQUEST_BODY");
        assertCode(putJson(matrix, matrixBody(service, Map.of("priceMinorOverride", 1.5))), owner(tenant), 400, "INVALID_REQUEST_BODY");

        String schedule = "/api/app/specialists/" + specialist + "/schedule";
        assertCode(putJson(schedule, List.of(Map.of("weekday", 1, "endMinute", 600))), owner(tenant), 400, "INVALID_REQUEST_BODY");
        assertCode(putJson(schedule, List.of(Map.of("weekday", 1, "startMinute", 60.5, "endMinute", 600))), owner(tenant), 400, "INVALID_REQUEST_BODY");
        assertCode(putJson(schedule, Map.of("weekday", 1)), owner(tenant), 400, "INVALID_REQUEST_BODY");

        String exceptions = "/api/app/specialists/" + specialist + "/exceptions";
        assertCode(putJson(exceptions, exception(LocalDate.of(2031, 3, 10), "SHIFT", 600, 700).entrySet().stream()
                .collect(HashMap::new, (m, e) -> m.put(e.getKey(), "startMinute".equals(e.getKey()) ? (Object) 600.5 : e.getValue()), HashMap::putAll)),
                owner(tenant), 400, "INVALID_REQUEST_BODY");

        assertThat(json(get("/api/app/services"), owner(tenant))).hasSize(1);
        assertThat(json(get(matrix), owner(tenant)).get(0).get("offered").asBoolean()).isFalse();
    }

    @Test
    void aDateAsANumberAndAnEmptyIdAreRefused() throws Exception {
        UUID specialist = specialist(tenant, "Nino");
        String exceptions = "/api/app/specialists/" + specialist + "/exceptions";
        Map<String, Object> numericDate = exception(LocalDate.of(2031, 3, 10), "DAY_OFF", null, null);
        numericDate.put("date", 20310310);
        assertCode(putJson(exceptions, numericDate), owner(tenant), 400, "INVALID_REQUEST_BODY");
        Map<String, Object> emptyExceptionId = exception(LocalDate.of(2031, 3, 10), "DAY_OFF", null, null);
        emptyExceptionId.put("id", "");
        assertCode(putJson(exceptions, emptyExceptionId), owner(tenant), 400, "INVALID_REQUEST_BODY");
        assertThat(json(get(exceptions), owner(tenant))).isEmpty();

        Map<String, Object> emptyServiceId = serviceBody("Cut");
        emptyServiceId.put("id", "");
        assertCode(putJson("/api/app/services", emptyServiceId), owner(tenant), 400, "INVALID_REQUEST_BODY");
        assertThat(json(get("/api/app/services"), owner(tenant))).isEmpty();
        Map<String, Object> emptySpecialistId = specialistBody("Anna", "#0f766e");
        emptySpecialistId.put("id", "");
        assertCode(putJson("/api/app/specialists", emptySpecialistId), owner(tenant), 400, "INVALID_REQUEST_BODY");
        Map<String, Object> numericId = serviceBody("Cut");
        numericId.put("id", 7);
        assertCode(putJson("/api/app/services", numericId), owner(tenant), 400, "INVALID_REQUEST_BODY");
    }

    /** The ids of a replaced schedule are new ones: a body cannot run into a row of another specialist or tenant. */
    @Test
    void theIdsInAScheduleBodyAreIgnored() throws Exception {
        UUID own = specialist(tenant, "Nino");
        UUID colleague = specialist(tenant, "Anna");
        String other = "/api/app/specialists/" + colleague + "/schedule";
        String taken = json(putJson(other, List.of(interval(1, 600, 720))), owner(tenant)).get(0).get("id").asText();

        Map<String, Object> reused = new HashMap<>(interval(2, 600, 720));
        reused.put("id", taken);
        JsonNode saved = json(putJson("/api/app/specialists/" + own + "/schedule", List.of(reused, reused.entrySet().stream()
                .collect(HashMap::new, (m, e) -> m.put(e.getKey(), "weekday".equals(e.getKey()) ? (Object) 3 : e.getValue()), HashMap::putAll))), owner(tenant));

        assertThat(saved).hasSize(2);
        assertThat(ids(saved)).doesNotContain(taken);
        assertThat(ids(json(get(other), owner(tenant)))).containsExactly(taken);
    }

    @Test
    void anInvalidSpecialistIsRefusedWithItsCode() throws Exception {
        assertCode(putJson("/api/app/specialists", specialistBody(" ", "#0f766e")), owner(tenant), 400, "INVALID_NAME");
        assertCode(putJson("/api/app/specialists", specialistBody("Nino", "blue")), owner(tenant), 400, "INVALID_COLOR");
        Map<String, Object> blankLink = specialistBody("Nino", "#0f766e");
        blankLink.put("keycloakUserId", " ");
        assertCode(putJson("/api/app/specialists", blankLink), owner(tenant), 400, "INVALID_KEYCLOAK_USER_ID");
        assertThat(json(get("/api/app/specialists"), owner(tenant))).isEmpty();
    }

    @Test
    void anInvalidMatrixRowIsRefusedWithItsCode() throws Exception {
        UUID specialist = specialist(tenant, "Nino");
        UUID service = service(tenant, "Cut");
        String path = "/api/app/specialists/" + specialist + "/matrix";

        assertCode(putJson(path, matrixBody(service, Map.of("durationMinutesOverride", 0))), owner(tenant), 400, "INVALID_DURATION");
        assertCode(putJson(path, matrixBody(service, Map.of("priceMinorOverride", -1))), owner(tenant), 400, "INVALID_PRICE");
        assertCode(putJson(path, matrixBody(service, Map.of("bufferBeforeMinutesOverride", -1))), owner(tenant), 400, "INVALID_BUFFER");
        assertCode(putJson(path, matrixBody(service, Map.of("bufferAfterMinutesOverride", -1))), owner(tenant), 400, "INVALID_BUFFER");
        assertCode(putJson(path, matrixBody(null, Map.of())), owner(tenant), 400, "INVALID_MATRIX_ROW");
        assertCode(putJson(path, matrixBody(UUID.randomUUID(), Map.of())), owner(tenant), 404, "SERVICE_NOT_FOUND");
        assertCode(putJson("/api/app/specialists/" + UUID.randomUUID() + "/matrix", matrixBody(service, Map.of())), owner(tenant), 404, "SPECIALIST_NOT_FOUND");
    }

    @Test
    void savingAMatrixRowTwiceKeepsOneRowAndAnswersItsStoredId() throws Exception {
        UUID specialist = specialist(tenant, "Nino");
        UUID service = service(tenant, "Cut");
        String path = "/api/app/specialists/" + specialist + "/matrix";

        JsonNode first = json(putJson(path, matrixBody(service, Map.of("durationMinutesOverride", 45))), owner(tenant));
        JsonNode second = json(putJson(path, matrixBody(service, Map.of("durationMinutesOverride", 50))), owner(tenant));

        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        JsonNode rows = json(get(path), owner(tenant));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("matrixId").asText()).isEqualTo(first.get("id").asText());
        assertThat(rows.get(0).get("durationMinutesOverride").asInt()).isEqualTo(50);
    }

    @Test
    void anInvalidScheduleIsRefusedAndTheStoredOneStays() throws Exception {
        UUID specialist = specialist(tenant, "Nino");
        String path = "/api/app/specialists/" + specialist + "/schedule";
        assertThat(mvc.perform(putJson(path, List.of(interval(1, 600, 720), interval(1, 720, 900), interval(2, 600, 720))).with(owner(tenant)))
                .andReturn().getResponse().getStatus()).as("touching intervals and another day are fine").isEqualTo(200);

        for (List<Object> invalid : List.of(
                List.<Object>of(interval(0, 600, 720)),
                List.<Object>of(interval(8, 600, 720)),
                List.<Object>of(interval(3, -1, 720)),
                List.<Object>of(interval(3, 600, 1441)),
                List.<Object>of(interval(3, 720, 720)),
                List.<Object>of(interval(3, 900, 720)),
                Collections.singletonList(null))) {
            assertCode(putJson(path, invalid), owner(tenant), 400, "INVALID_SCHEDULE_INTERVAL");
        }
        assertCode(putJson(path, List.of(interval(3, 600, 800), interval(3, 700, 900))), owner(tenant), 400, "SCHEDULE_OVERLAP");
        assertCode(putJson(path, List.of(interval(3, 600, 800), interval(3, 600, 800))), owner(tenant), 400, "SCHEDULE_OVERLAP");

        assertThat(json(get(path), owner(tenant))).hasSize(3);
    }

    @Test
    void exceptionsAreValidatedListedMovedAndDeleted() throws Exception {
        UUID specialist = specialist(tenant, "Nino");
        String path = "/api/app/specialists/" + specialist + "/exceptions";
        LocalDate day = LocalDate.of(2031, 3, 10);

        assertCode(putJson(path, exception(day, "VACATION", null, null)), owner(tenant), 400, "INVALID_EXCEPTION_KIND");
        assertCode(putJson(path, exception(day, null, null, null)), owner(tenant), 400, "INVALID_EXCEPTION_KIND");
        assertCode(putJson(path, exception(null, "DAY_OFF", null, null)), owner(tenant), 400, "INVALID_EXCEPTION_DATE");
        assertCode(putJson(path, exception(day, "DAY_OFF", 600, 720)), owner(tenant), 400, "INVALID_EXCEPTION_MINUTES");
        assertCode(putJson(path, exception(day, "SHIFT", null, 720)), owner(tenant), 400, "INVALID_EXCEPTION_MINUTES");
        assertCode(putJson(path, exception(day, "SHIFT", 720, 600)), owner(tenant), 400, "INVALID_EXCEPTION_MINUTES");
        assertCode(putJson(path, exception(day, "SHIFT", -1, 600)), owner(tenant), 400, "INVALID_EXCEPTION_MINUTES");
        assertCode(putJson(path, exception(day, "SHIFT", 600, 1441)), owner(tenant), 400, "INVALID_EXCEPTION_MINUTES");
        Map<String, Object> longNote = exception(day, "DAY_OFF", null, null);
        longNote.put("note", "x".repeat(513));
        assertCode(putJson(path, longNote), owner(tenant), 400, "INVALID_NOTE");
        assertThat(json(get(path), owner(tenant))).isEmpty();

        String dayOff = json(putJson(path, exception(day, "DAY_OFF", null, null)), owner(tenant)).get("id").asText();
        String shift = json(putJson(path, exception(day.plusDays(1), "SHIFT", 600, 720)), owner(tenant)).get("id").asText();
        assertThat(ids(json(get(path), owner(tenant)))).containsExactly(dayOff, shift);
        assertThat(ids(json(get(path + "?from=" + day.plusDays(1)), owner(tenant)))).containsExactly(shift);
        assertThat(ids(json(get(path + "?to=" + day), owner(tenant)))).containsExactly(dayOff);
        assertCode(get(path + "?from=soon"), owner(tenant), 400, "INVALID_PARAMETER");

        // Saving by id moves the exception: the date is part of what the owner edits.
        Map<String, Object> moved = exception(day.plusDays(5), "SHIFT", 610, 730);
        moved.put("id", shift);
        JsonNode saved = json(putJson(path, moved), owner(tenant));
        assertThat(saved.get("id").asText()).isEqualTo(shift);
        assertThat(ids(json(get(path + "?from=" + day.plusDays(5)), owner(tenant)))).containsExactly(shift);

        // An id that is unknown, or belongs to another specialist of the tenant, is not created or taken over.
        Map<String, Object> unknown = exception(day, "DAY_OFF", null, null);
        unknown.put("id", UUID.randomUUID().toString());
        assertCode(putJson(path, unknown), owner(tenant), 404, "EXCEPTION_NOT_FOUND");
        UUID colleague = specialist(tenant, "Anna");
        Map<String, Object> takeover = exception(day, "DAY_OFF", null, null);
        takeover.put("id", shift);
        assertCode(putJson("/api/app/specialists/" + colleague + "/exceptions", takeover), owner(tenant), 404, "EXCEPTION_NOT_FOUND");
        assertCode(delete("/api/app/specialists/" + colleague + "/exceptions/" + shift), owner(tenant), 404, "EXCEPTION_NOT_FOUND");

        assertThat(mvc.perform(delete(path + "/" + dayOff).with(owner(tenant))).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertCode(delete(path + "/" + dayOff), owner(tenant), 404, "EXCEPTION_NOT_FOUND");
        assertThat(ids(json(get(path), owner(tenant)))).containsExactly(shift);
    }

    // --- who may write ---

    @Test
    void aMasterWritesNothingAndReadsOnlyTheirOwnExceptions() throws Exception {
        UUID own = specialist(tenant, "Nino");
        UUID colleague = specialist(tenant, "Anna");
        UUID service = service(tenant, "Cut");
        String ownException = json(putJson("/api/app/specialists/" + own + "/exceptions", exception(LocalDate.of(2031, 3, 10), "DAY_OFF", null, null)), owner(tenant)).get("id").asText();
        RequestPostProcessor nino = master(tenant, own);

        for (String target : List.of(own.toString(), colleague.toString())) {
            String base = "/api/app/specialists/" + target;
            assertCode(putJson(base + "/matrix", matrixBody(service, Map.of())), nino, 403, "FORBIDDEN_RESOURCE");
            assertCode(putJson(base + "/schedule", List.of(interval(1, 600, 720))), nino, 403, "FORBIDDEN_RESOURCE");
            assertCode(putJson(base + "/exceptions", exception(LocalDate.of(2031, 3, 11), "DAY_OFF", null, null)), nino, 403, "FORBIDDEN_RESOURCE");
            assertCode(delete(base + "/exceptions/" + ownException), nino, 403, "FORBIDDEN_RESOURCE");
        }
        assertCode(putJson("/api/app/services", serviceBody("Sneaky")), nino, 403, "FORBIDDEN_RESOURCE");
        assertCode(putJson("/api/app/specialists", specialistBody("Sneaky", "#0f766e")), nino, 403, "FORBIDDEN_RESOURCE");

        assertThat(ids(json(get("/api/app/specialists/" + own + "/exceptions"), nino))).containsExactly(ownException);
        assertCode(get("/api/app/specialists/" + colleague + "/exceptions"), nino, 403, "FORBIDDEN_RESOURCE");
    }

    // --- tenant isolation ---

    @Test
    void anotherTenantsIdsBehaveLikeUnknownIds() throws Exception {
        UUID service = service(tenant, "Cut");
        UUID specialist = specialist(tenant, "Nino");
        String exceptionId = json(putJson("/api/app/specialists/" + specialist + "/exceptions", exception(LocalDate.of(2031, 3, 10), "DAY_OFF", null, null)), owner(tenant)).get("id").asText();
        RequestPostProcessor stranger = owner(otherTenant);
        UUID strangerSpecialist = specialist(otherTenant, "Stranger");
        UUID strangerService = service(otherTenant, "Stranger cut");

        Map<String, Object> takeService = serviceBody("Taken");
        takeService.put("id", service.toString());
        assertCode(putJson("/api/app/services", takeService), stranger, 404, "SERVICE_NOT_FOUND");
        Map<String, Object> takeSpecialist = specialistBody("Taken", "#0f766e");
        takeSpecialist.put("id", specialist.toString());
        assertCode(putJson("/api/app/specialists", takeSpecialist), stranger, 404, "SPECIALIST_NOT_FOUND");

        String foreign = "/api/app/specialists/" + specialist;
        assertCode(get(foreign + "/matrix"), stranger, 404, "SPECIALIST_NOT_FOUND");
        assertCode(get(foreign + "/schedule"), stranger, 404, "SPECIALIST_NOT_FOUND");
        assertCode(get(foreign + "/exceptions"), stranger, 404, "SPECIALIST_NOT_FOUND");
        // The foreign keys are not tenant-scoped and do not see RLS: these writes must not reach the database.
        assertCode(putJson(foreign + "/matrix", matrixBody(strangerService, Map.of())), stranger, 404, "SPECIALIST_NOT_FOUND");
        assertCode(putJson(foreign + "/schedule", List.of(interval(1, 600, 720))), stranger, 404, "SPECIALIST_NOT_FOUND");
        assertCode(putJson(foreign + "/exceptions", exception(LocalDate.of(2031, 3, 11), "DAY_OFF", null, null)), stranger, 404, "SPECIALIST_NOT_FOUND");
        assertCode(delete(foreign + "/exceptions/" + exceptionId), stranger, 404, "SPECIALIST_NOT_FOUND");
        assertCode(putJson("/api/app/specialists/" + strangerSpecialist + "/matrix", matrixBody(service, Map.of())), stranger, 404, "SERVICE_NOT_FOUND");
        Map<String, Object> takeException = exception(LocalDate.of(2031, 3, 12), "DAY_OFF", null, null);
        takeException.put("id", exceptionId);
        assertCode(putJson("/api/app/specialists/" + strangerSpecialist + "/exceptions", takeException), stranger, 404, "EXCEPTION_NOT_FOUND");
        assertCode(delete("/api/app/specialists/" + strangerSpecialist + "/exceptions/" + exceptionId), stranger, 404, "EXCEPTION_NOT_FOUND");

        assertThat(json(get("/api/app/services"), owner(tenant)).get(0).get("name").asText()).isEqualTo("Cut");
        assertThat(json(get("/api/app/specialists"), owner(tenant)).get(0).get("displayName").asText()).isEqualTo("Nino");
        assertThat(ids(json(get(foreign + "/exceptions"), owner(tenant)))).containsExactly(exceptionId);
        assertThat(json(get("/api/app/specialists/" + strangerSpecialist + "/schedule"), stranger)).isEmpty();
    }

    @Test
    void anIdNobodyKnowsDoesNotCreateARow() throws Exception {
        Map<String, Object> unknownService = serviceBody("Cut");
        unknownService.put("id", UUID.randomUUID().toString());
        assertCode(putJson("/api/app/services", unknownService), owner(tenant), 404, "SERVICE_NOT_FOUND");
        Map<String, Object> unknownSpecialist = specialistBody("Nino", "#0f766e");
        unknownSpecialist.put("id", UUID.randomUUID().toString());
        assertCode(putJson("/api/app/specialists", unknownSpecialist), owner(tenant), 404, "SPECIALIST_NOT_FOUND");
    }

    // --- the sign-in link of a master ---

    @Test
    void savingASpecialistWithoutTheLinkKeepsIt() throws Exception {
        Map<String, Object> linked = specialistBody("Nino", "#0f766e");
        linked.put("keycloakUserId", "kc-" + tenant);
        UUID id = UUID.fromString(json(putJson("/api/app/specialists", linked), owner(tenant)).get("id").asText());

        Map<String, Object> edit = specialistBody("Nino B.", "#7c3aed");
        edit.put("id", id.toString());
        edit.put("keycloakUserId", null);
        JsonNode saved = json(putJson("/api/app/specialists", edit), owner(tenant));

        assertThat(saved.get("displayName").asText()).isEqualTo("Nino B.");
        assertThat(saved.get("keycloakUserId").asText()).isEqualTo("kc-" + tenant);
        assertThat(json(get("/api/app/specialists"), owner(tenant)).get(0).get("keycloakUserId").asText()).isEqualTo("kc-" + tenant);

        Map<String, Object> second = specialistBody("Anna", "#0f766e");
        second.put("keycloakUserId", "kc-" + tenant);
        assertCode(putJson("/api/app/specialists", second), owner(tenant), 409, "KEYCLOAK_USER_ALREADY_LINKED");
    }

    // --- what the booking side sees ---

    @Test
    void aDeactivatedServiceGivesNoSlotsAndItsVisitsStay() throws Exception {
        Bookable b = bookable();
        List<Instant> free = freeSlots(b);
        JsonNode visit = book(b, free.getFirst());
        assertThat(publicServices()).hasSize(1);

        Map<String, Object> off = serviceBody("Cut");
        off.put("id", b.service.toString());
        off.put("active", false);
        json(putJson("/api/app/services", off), owner(tenant));

        assertThat(publicServices()).isEmpty();
        assertCodeAnonymous(slots(b), 409, "SERVICE_INACTIVE");
        assertCodeAnonymous(bookRequest(b, free.getLast()), 409, "SERVICE_INACTIVE");
        assertThat(json(get("/api/app/appointments/" + visit.get("id").asText()), owner(tenant)).get("appointment").get("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void aDeactivatedMasterGivesNoSlotsAndTheirVisitsStay() throws Exception {
        Bookable b = bookable();
        List<Instant> free = freeSlots(b);
        JsonNode visit = book(b, free.getFirst());

        Map<String, Object> off = specialistBody("Nino", "#0f766e");
        off.put("id", b.specialist.toString());
        off.put("active", false);
        json(putJson("/api/app/specialists", off), owner(tenant));

        JsonNode offering = json(get("/api/public/tenants/" + slug + "/services/" + b.service + "/specialists"), null);
        assertThat(offering).as("the public step lists no inactive master").isEmpty();
        assertCodeAnonymous(slots(b), 409, "SERVICE_NOT_OFFERED");
        assertCodeAnonymous(bookRequest(b, free.getLast()), 409, "SERVICE_NOT_OFFERED");
        assertThat(json(get("/api/app/appointments/" + visit.get("id").asText()), owner(tenant)).get("appointment").get("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void aNewPriceAndDurationChangeOnlyNewVisits() throws Exception {
        Bookable b = bookable();
        List<Instant> free = freeSlots(b);
        Instant firstStart = free.getFirst();
        JsonNode first = book(b, firstStart);

        Map<String, Object> edit = serviceBody("Cut");
        edit.put("id", b.service.toString());
        edit.put("priceMinor", 9900);
        edit.put("durationMinutes", 90);
        json(putJson("/api/app/services", edit), owner(tenant));

        Instant secondStart = freeSlots(b).getLast();
        JsonNode second = book(b, secondStart);
        JsonNode firstNow = json(get("/api/app/appointments/" + first.get("id").asText()), owner(tenant)).get("appointment");
        JsonNode secondNow = json(get("/api/app/appointments/" + second.get("id").asText()), owner(tenant)).get("appointment");

        assertThat(firstNow.get("priceSnapshot").asInt()).isEqualTo(8000);
        assertThat(firstNow.get("durationSnapshot").asInt()).isEqualTo(60);
        assertThat(secondNow.get("priceSnapshot").asInt()).isEqualTo(9900);
        assertThat(secondNow.get("durationSnapshot").asInt()).isEqualTo(90);
    }

    // --- a deactivated master's visits: cancelled, never moved ---

    @Test
    void aVisitOfADeactivatedMasterCanOnlyBeCancelled() throws Exception {
        Bookable b = bookable();
        List<Instant> free = freeSlots(b);
        JsonNode byOwner = book(b, free.getFirst());
        JsonNode byClient = book(b, free.get(10));
        setActive(b.specialist, false);
        Instant target = free.getLast();

        assertCode(post("/api/app/appointments/" + byOwner.get("id").asText() + "/reschedule").param("start", target.toString()),
                owner(tenant), 409, "SERVICE_NOT_OFFERED");
        assertCodeAnonymous(post("/api/public/bookings/" + byClient.get("id").asText() + "/reschedule")
                .param("token", byClient.get("accessToken").asText()).param("start", target.toString()), 409, "SERVICE_NOT_OFFERED");

        JsonNode cancelledByOwner = json(post("/api/app/appointments/" + byOwner.get("id").asText() + "/transition")
                .param("to", "CANCELLED_BY_BUSINESS"), owner(tenant));
        assertThat(cancelledByOwner.get("status").asText()).isEqualTo("CANCELLED_BY_BUSINESS");
        JsonNode cancelledByClient = json(post("/api/public/bookings/" + byClient.get("id").asText() + "/cancel")
                .param("token", byClient.get("accessToken").asText()), null);
        assertThat(cancelledByClient.get("status").asText()).isEqualTo("CANCELLED_BY_CLIENT");
    }

    // --- a deactivated master's access ---

    @Test
    void aDeactivatedMasterIsLockedOutUntilReactivated() throws Exception {
        String subject = "kc-" + UUID.randomUUID();
        Map<String, Object> linked = specialistBody("Nino", "#0f766e");
        linked.put("keycloakUserId", subject);
        UUID id = UUID.fromString(json(putJson("/api/app/specialists", linked), owner(tenant)).get("id").asText());
        RequestPostProcessor byClaim = master(tenant, id);
        RequestPostProcessor bySubject = masterBySubject(tenant, subject);
        assertThat(json(get("/api/app/me"), byClaim).get("role").asText()).isEqualTo("MASTER");
        assertThat(json(get("/api/app/me"), bySubject).get("specialistId").asText()).isEqualTo(id.toString());

        setActive(id, false);
        assertCode(get("/api/app/me"), byClaim, 403, "SPECIALIST_INACTIVE");
        assertCode(get("/api/app/me"), bySubject, 403, "SPECIALIST_INACTIVE");
        assertCode(get("/api/app/specialists/" + id + "/schedule"), byClaim, 403, "SPECIALIST_INACTIVE");
        assertThat(json(get("/api/app/me"), owner(tenant)).get("role").asText()).as("the owner is not affected").isEqualTo("OWNER");

        setActive(id, true);
        assertThat(json(get("/api/app/me"), byClaim).get("role").asText()).isEqualTo("MASTER");
        assertThat(json(get("/api/app/me"), bySubject).get("role").asText()).isEqualTo("MASTER");
    }

    @Test
    void onlyTheOwnerRemovesASignInLinkAndDoingItTwiceIsFine() throws Exception {
        String subject = "kc-" + UUID.randomUUID();
        Map<String, Object> linked = specialistBody("Nino", "#0f766e");
        linked.put("keycloakUserId", subject);
        UUID id = UUID.fromString(json(putJson("/api/app/specialists", linked), owner(tenant)).get("id").asText());
        String path = "/api/app/specialists/" + id + "/keycloak-link";

        assertCode(delete(path), master(tenant, id), 403, "FORBIDDEN_RESOURCE");
        assertCode(delete(path), owner(otherTenant), 404, "SPECIALIST_NOT_FOUND");
        assertThat(json(get("/api/app/specialists"), owner(tenant)).get(0).get("keycloakUserId").asText()).isEqualTo(subject);

        assertThat(mvc.perform(delete(path).with(owner(tenant))).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(mvc.perform(delete(path).with(owner(tenant))).andReturn().getResponse().getStatus()).isEqualTo(204);

        assertThat(json(get("/api/app/specialists"), owner(tenant)).get(0).get("keycloakUserId").isNull()).isTrue();
        assertCode(get("/api/app/me"), masterBySubject(tenant, subject), 403, "SPECIALIST_UNMAPPED");
        assertThat(audit("specialist.keycloak_link.remove", id)).hasSize(1);
        assertThat(audit("specialist.keycloak_link.remove", id).getFirst().before()).isEqualTo("{\"linked\":true}");
        assertThat(audit("specialist.keycloak_link.remove", id).getFirst().after()).isEqualTo("{\"linked\":false}").doesNotContain(subject);
    }

    /** Removing the link ends the sign-in by subject only: revoking access is deactivation, or Keycloak. */
    @Test
    void removingTheLinkDoesNotRevokeATokenWithTheClaimDeactivationDoes() throws Exception {
        UUID id = specialist(tenant, "Nino");
        RequestPostProcessor token = master(tenant, id);
        Map<String, Object> linked = specialistBody("Nino", "#0f766e");
        linked.put("id", id.toString());
        linked.put("keycloakUserId", "master-" + id);
        json(putJson("/api/app/specialists", linked), owner(tenant));

        assertThat(mvc.perform(delete("/api/app/specialists/" + id + "/keycloak-link").with(owner(tenant))).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(json(get("/api/app/me"), token).get("specialistId").asText()).isEqualTo(id.toString());
        assertCode(get("/api/app/me"), masterBySubject(tenant, "master-" + id), 403, "SPECIALIST_UNMAPPED");

        setActive(id, false);
        assertCode(get("/api/app/me"), token, 403, "SPECIALIST_INACTIVE");
    }

    @Test
    void oneSignInCannotBeLinkedTwiceEvenInARace() throws Exception {
        String subject = "kc-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                String link = subject + "-" + round;
                List<Future<Integer>> answers = new ArrayList<>();
                for (int thread = 0; thread < 2; thread++) {
                    Map<String, Object> body = specialistBody("Racer", "#0f766e");
                    body.put("keycloakUserId", link);
                    MockHttpServletRequestBuilder request = putJson("/api/app/specialists", body);
                    RequestPostProcessor who = owner(tenant);
                    Callable<Integer> call = () -> {
                        MvcResult result = mvc.perform(request.with(who)).andReturn();
                        int status = result.getResponse().getStatus();
                        if (status != 200) {
                            assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("code").asText()).isEqualTo("KEYCLOAK_USER_ALREADY_LINKED");
                        }
                        return status;
                    };
                    answers.add(pool.submit(call));
                }
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> answer : answers) {
                    statuses.add(answer.get());
                }
                assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            }
        } finally {
            pool.shutdownNow();
        }
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
             PreparedStatement insert = c.prepareStatement("""
                     insert into specialist (id, tenant_id, display_name, keycloak_user_id, calendar_color, active)
                     values (gen_random_uuid(), ?, 'Direct', ?, '#0f766e', true)""")) {
            insert.setObject(1, tenant);
            insert.setString(2, subject + "-0");
            assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
        }
    }

    // --- audit ---

    @Test
    void catalogChangesAreAuditedWithBeforeAfterAndActor() throws Exception {
        JsonNode created = json(putJson("/api/app/services", serviceBody("Cut")), owner(tenant));
        UUID service = UUID.fromString(created.get("id").asText());
        AuditRow create = audit("service.create", service).getFirst();
        assertThat(create.before()).isNull();
        assertThat(create.after()).contains("\"priceMinor\":8000");
        assertThat(create.actorType()).isEqualTo("OWNER");
        assertThat(create.actorId()).isEqualTo("owner-" + tenant);

        Map<String, Object> edit = serviceBody("Cut");
        edit.put("id", service.toString());
        edit.put("priceMinor", 9900);
        edit.put("active", false);
        json(putJson("/api/app/services", edit), owner(tenant));
        AuditRow update = audit("service.update", service).getFirst();
        assertThat(update.before()).contains("\"priceMinor\":8000", "\"active\":true");
        assertThat(update.after()).contains("\"priceMinor\":9900", "\"active\":false");

        Map<String, Object> link = specialistBody("Nino Beridze", "#0f766e");
        link.put("keycloakUserId", "kc-secret-" + tenant);
        UUID specialist = UUID.fromString(json(putJson("/api/app/specialists", link), owner(tenant)).get("id").asText());
        assertThat(audit("specialist.create", specialist).getFirst().after()).contains("\"active\":true", "\"linked\":true");
        setActive(specialist, false);
        AuditRow deactivated = audit("specialist.update", specialist).getFirst();
        assertThat(deactivated.before()).contains("\"active\":true");
        assertThat(deactivated.after()).contains("\"active\":false");
        assertThat(deactivated.before() + deactivated.after()).doesNotContain("Nino", "kc-secret");

        String base = "/api/app/specialists/" + specialist;
        String matrixId = json(putJson(base + "/matrix", matrixBody(service, Map.of("priceMinorOverride", 700))), owner(tenant)).get("id").asText();
        json(putJson(base + "/matrix", matrixBody(service, Map.of("priceMinorOverride", 800))), owner(tenant));
        List<AuditRow> matrix = audit("master_service.save", UUID.fromString(matrixId));
        assertThat(matrix).hasSize(2);
        assertThat(matrix.get(1).before()).contains("\"priceMinorOverride\":700");
        assertThat(matrix.get(1).after()).contains("\"priceMinorOverride\":800");

        json(putJson(base + "/schedule", List.of(interval(1, 600, 720))), owner(tenant));
        json(putJson(base + "/schedule", List.of(interval(2, 660, 780))), owner(tenant));
        AuditRow schedule = audit("schedule.replace", specialist).get(1);
        assertThat(schedule.before()).isEqualTo("{\"intervals\":\"1:600-720\"}");
        assertThat(schedule.after()).isEqualTo("{\"intervals\":\"2:660-780\"}");

        Map<String, Object> dayOff = exception(LocalDate.of(2031, 3, 10), "DAY_OFF", null, null);
        dayOff.put("note", "Clinic visit");
        UUID exceptionId = UUID.fromString(json(putJson(base + "/exceptions", dayOff), owner(tenant)).get("id").asText());
        AuditRow saved = audit("schedule_exception.save", exceptionId).getFirst();
        assertThat(saved.after()).contains("2031-03-10", "DAY_OFF").doesNotContain("Clinic");
        assertThat(mvc.perform(delete(base + "/exceptions/" + exceptionId).with(owner(tenant))).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(audit("schedule_exception.delete", exceptionId).getFirst().before()).contains("2031-03-10");
    }

    // --- helpers ---

    private record AuditRow(String actorId, String actorType, String before, String after) {
    }

    /** The audit rows of one action and entity, oldest first, read as the schema owner. */
    private List<AuditRow> audit(String action, UUID entity) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
             PreparedStatement query = c.prepareStatement("""
                     select actor_id, actor_type, before_state, after_state from audit_log
                     where tenant_id = ? and action = ? and entity_id = ? order by created_at""")) {
            query.setObject(1, tenant);
            query.setString(2, action);
            query.setObject(3, entity);
            List<AuditRow> rows = new ArrayList<>();
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) {
                    rows.add(new AuditRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
            assertThat(rows).as(action + " rows").isNotEmpty();
            return rows;
        }
    }

    private void setActive(UUID specialist, boolean active) throws Exception {
        Map<String, Object> body = specialistBody("Nino", "#0f766e");
        body.put("id", specialist.toString());
        body.put("active", active);
        json(putJson("/api/app/specialists", body), owner(tenant));
    }

    private void assertCodeAnonymous(MockHttpServletRequestBuilder request, int status, String code) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        String answer = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(answer).isEqualTo(status);
        assertThat(mapper.readTree(answer).path("code").asText()).as(answer).isEqualTo(code);
    }

    private record Bookable(UUID service, UUID specialist) {
    }

    /** A public service and an active master who offers it, working every day. */
    private Bookable bookable() throws Exception {
        UUID service = service(tenant, "Cut");
        UUID specialist = specialist(tenant, "Nino");
        json(putJson("/api/app/specialists/" + specialist + "/matrix", matrixBody(service, Map.of())), owner(tenant));
        List<Object> week = new ArrayList<>();
        for (int weekday = 1; weekday <= 7; weekday++) {
            week.add(interval(weekday, 0, 1440));
        }
        json(putJson("/api/app/specialists/" + specialist + "/schedule", week), owner(tenant));
        return new Bookable(service, specialist);
    }

    private MockHttpServletRequestBuilder slots(Bookable b) {
        LocalDate from = LocalDate.now(ZONE).plusDays(1);
        return get("/api/public/tenants/" + slug + "/slots")
                .param("serviceId", b.service.toString())
                .param("specialistId", b.specialist.toString())
                .param("from", from.toString())
                .param("to", from.plusDays(3).toString());
    }

    private List<Instant> freeSlots(Bookable b) throws Exception {
        List<Instant> free = new ArrayList<>();
        for (JsonNode slot : json(slots(b), null)) {
            free.add(Instant.parse(slot.asText()));
        }
        assertThat(free).as("the fixture schedule has slots").isNotEmpty();
        return free;
    }

    private MockHttpServletRequestBuilder bookRequest(Bookable b, Instant start) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("serviceId", b.service.toString());
        body.put("specialistId", b.specialist.toString());
        body.put("serviceStart", start.toString());
        body.put("phone", "+9955550" + String.format("%05d", PHONE.incrementAndGet() % 100_000));
        body.put("name", "Client");
        body.put("locale", "en");
        body.put("marketingConsent", false);
        body.put("countryCode", "GE");
        return post("/api/public/tenants/" + slug + "/bookings").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }

    private JsonNode book(Bookable b, Instant start) throws Exception {
        return json(bookRequest(b, start), null);
    }

    private JsonNode publicServices() throws Exception {
        return json(get("/api/public/tenants/" + slug + "/services"), null);
    }

    private UUID service(UUID owner, String name) throws Exception {
        return UUID.fromString(json(putJson("/api/app/services", serviceBody(name)), owner(owner)).get("id").asText());
    }

    private UUID specialist(UUID owner, String name) throws Exception {
        return UUID.fromString(json(putJson("/api/app/specialists", specialistBody(name, "#0f766e")), owner(owner)).get("id").asText());
    }

    private static Map<String, Object> serviceBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", null);
        body.put("name", name);
        body.put("description", null);
        body.put("durationMinutes", 60);
        body.put("priceMinor", 8000);
        body.put("bufferBeforeMinutes", 0);
        body.put("bufferAfterMinutes", 0);
        body.put("color", "#0f766e");
        body.put("active", true);
        body.put("publicVisible", true);
        return body;
    }

    private static Map<String, Object> specialistBody(String name, String color) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", null);
        body.put("displayName", name);
        body.put("keycloakUserId", null);
        body.put("calendarColor", color);
        body.put("active", true);
        return body;
    }

    private static Map<String, Object> matrixBody(UUID service, Map<String, Object> overrides) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceId", service == null ? null : service.toString());
        body.put("offered", true);
        body.putAll(overrides);
        return body;
    }

    private static Map<String, Object> interval(int weekday, int start, int end) {
        return Map.of("weekday", weekday, "startMinute", start, "endMinute", end);
    }

    private static Map<String, Object> exception(LocalDate date, String kind, Integer start, Integer end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", date == null ? null : date.toString());
        body.put("kind", kind);
        body.put("startMinute", start);
        body.put("endMinute", end);
        body.put("note", null);
        return body;
    }

    private MockHttpServletRequestBuilder putJson(String path, Object body) throws Exception {
        return put(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }

    private void assertRefused(Map<String, Object> change, int status, String code) throws Exception {
        Map<String, Object> body = serviceBody("Cut");
        body.putAll(change);
        assertCode(putJson("/api/app/services", body), owner(tenant), status, code);
    }

    private void assertCode(MockHttpServletRequestBuilder request, RequestPostProcessor who, int status, String code) throws Exception {
        MvcResult result = mvc.perform(request.with(who)).andReturn();
        String answer = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(answer).isEqualTo(status);
        assertThat(mapper.readTree(answer).path("code").asText()).as(answer).isEqualTo(code);
    }

    private JsonNode json(MockHttpServletRequestBuilder request, RequestPostProcessor who) throws Exception {
        MvcResult result = mvc.perform(who == null ? request : request.with(who)).andReturn();
        String answer = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(answer).isEqualTo(200);
        return mapper.readTree(answer);
    }

    private static List<String> ids(JsonNode rows) {
        List<String> ids = new ArrayList<>();
        for (JsonNode row : rows) {
            ids.add(row.get("id").asText());
        }
        return ids;
    }
}

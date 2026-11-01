package com.cadence.app;

import com.cadence.ai.internal.llm.FinishReason;
import com.cadence.ai.internal.llm.LlmMessage;
import com.cadence.ai.internal.llm.LlmResponse;
import com.cadence.ai.internal.llm.StubLlmProvider;
import com.cadence.ai.internal.llm.ToolCall;
import com.cadence.ai.internal.llm.Usage;
import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.BookingService;
import com.cadence.booking.api.CreateBookingCommand;
import com.cadence.booking.api.PublicBookingResult;
import com.cadence.catalog.api.BookingRulesView;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.MasterServiceView;
import com.cadence.catalog.api.ServiceView;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.catalog.api.WeeklyInterval;
import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.notification.api.OwnerTaskService;
import com.cadence.platform.ai.AiLogStore;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.cadence.retention.api.RetentionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static com.cadence.app.TestJwt.master;
import static com.cadence.app.TestJwt.owner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * TD-46, PRODUCT_PLAN §10: {@code POST /api/app/clients/{id}/anonymize} end to end against the real schema (RLS role), each
 * test in its own tenant. The personal data of the client is planted wherever the product can hold it, and a scan of every
 * text column of every table (read with the owner connection, which bypasses RLS) looks for it before and after.
 */
@SpringBootTest
@RelaxedPublicRateLimits
@AutoConfigureMockMvc
class ClientAnonymizationIT extends AbstractTimescaleIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm");
    private static final ZoneId TBILISI = ZoneId.of("Asia/Tbilisi");
    private static final AtomicInteger CALL_IDS = new AtomicInteger();
    /** Recalculations fold the journal as of now minus {@code app.metrics.as-of-lag} (2 s in ITs). */
    private static final Duration AS_OF_LAG = Duration.ofMillis(2_200);

    private record Studio(UUID tenant, UUID master, UUID service) {
    }

    private record Person(UUID id, String name, String phone) {
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    StubLlmProvider stub;
    @Autowired
    TenantDirectory tenants;
    @Autowired
    CatalogService catalog;
    @Autowired
    BookingService booking;
    @Autowired
    CrmService crm;
    @Autowired
    RetentionService retention;
    @Autowired
    OwnerTaskService ownerTasks;
    @Autowired
    AiLogStore logs;

    private Studio studio;

    @BeforeEach
    void freshStudio() {
        stub.reset();
        studio = newStudio();
    }

    @AfterEach
    void clear() {
        ActorContext.clear();
        TenantContext.clear();
        stub.reset();
    }

    // ---------------------------------------------------------------- the end-to-end scenario

    @Test
    void noTableKeepsTheNameOrPhoneWhileVisitFactsAndMetricsStay() throws Exception {
        Person client = client();
        PublicBookingResult link = publicVisit(client, slot(2, 0));
        settleAsPast(link.appointment().id());
        UUID visit = pastVisit(client);
        ok(call(post("/api/app/appointments/" + visit + "/transition").param("to", "COMPLETED")
                .param("note", "Note about " + client.name() + ", call " + client.phone())));
        UUID conversation = plantInEveryModule(client);

        Thread.sleep(AS_OF_LAG.toMillis());
        ok(call(post("/api/app/metrics/recalculate")));
        Map<String, String> metricsBefore = metrics();
        assertThat(metricsBefore.values()).as("the visit counts in the metrics").anyMatch(metric -> !metric.contains("sample=0"));
        int eventsBefore = count("select count(*) from appointment_event where tenant_id = ?", studio.tenant());
        int visitsBefore = count("select count(*) from appointment where tenant_id = ?", studio.tenant());
        ClientView aggregatesBefore = asOwner(() -> crm.findById(client.id())).orElseThrow();

        assertThat(scan(client)).as("the scan must see the data it looks for")
                .contains("client.display_name", "client.normalized_phone", "client.notes", "client.tags",
                        "appointment.entered_name", "appointment.note", "owner_task.title", "owner_task.body",
                        "ai_pseudonym.display", "ai_draft.payload", "trust_state.override_reason", "service_package.name");

        JsonNode answer = ok(call(post("/api/app/clients/" + client.id() + "/anonymize")));

        assertThat(answer.path("displayName").asText()).isEqualTo("anonymized");
        assertThat(answer.path("normalizedPhone").isNull()).as(answer.toString()).isTrue();
        assertThat(scan(client)).as("tables that still hold the client's name or phone").isEmpty();

        // CRM: the profile is empty, the numbers stay.
        assertThat(one("select coalesce(notes, '-') || '|' || coalesce(tags, '-') || '|' || coalesce(preferred_locale, '-') || '|'"
                + " || coalesce(telegram_chat_id, '-') || '|' || marketing_consent || '|' || coalesce(consent_version, '-') || '|'"
                + " || coalesce(consent_at::text, '-') from client where id = ?", client.id()))
                .isEqualTo("-|-|-|-|false|-|-");
        ClientView aggregatesAfter = asOwner(() -> crm.findById(client.id())).orElseThrow();
        assertThat(aggregatesAfter.completedVisitCount()).isEqualTo(aggregatesBefore.completedVisitCount()).isPositive();
        assertThat(aggregatesAfter.completedValueMinor()).isEqualTo(aggregatesBefore.completedValueMinor());
        assertThat(aggregatesAfter.firstVisitAt()).isEqualTo(aggregatesBefore.firstVisitAt());

        // Visits and their journal stay, without what was typed on them.
        assertThat(count("select count(*) from appointment where tenant_id = ?", studio.tenant())).isEqualTo(visitsBefore);
        assertThat(count("select count(*) from appointment_event where tenant_id = ?", studio.tenant())).isEqualTo(eventsBefore);
        assertThat(one("select price_snapshot || ' ' || status from appointment where id = ?", visit)).isEqualTo("5000 COMPLETED");
        assertThat(count("select count(*) from appointment where client_id = ? and (entered_name is not null or note is not null)",
                client.id())).isZero();

        // Metrics of past periods do not move.
        ok(call(post("/api/app/metrics/recalculate")));
        assertThat(metrics()).isEqualTo(metricsBefore);

        // The dialogue about the client is gone, with its draft.
        assertThat(error(call(get("/api/app/ai/conversations/" + conversation)), 404)).isEqualTo("CONVERSATION_NOT_FOUND");
        assertThat(ok(call(get("/api/app/ai/drafts")))).isEmpty();

        // Audit: exactly one record, ids and counters, nothing about the person.
        assertThat(column("select coalesce(before_state, '') || coalesce(after_state, '') from audit_log "
                + "where action = 'client.anonymize' and entity_id = ?", client.id()))
                .singleElement().satisfies(state -> assertThat(state)
                        .contains("\"visits\":2").contains("\"packages\":1").doesNotContain(client.name()).doesNotContain(client.phone()));
    }

    @Test
    void theManageLinkDiesAndNoNewOneOrVisitCanBeMade() throws Exception {
        Person client = client();
        PublicBookingResult link = publicVisit(client, slot(2, 1));
        String path = "/api/public/bookings/" + link.appointment().id();
        assertThat(mvc.perform(get(path).param("token", link.accessToken())).andReturn().getResponse().getStatus()).isEqualTo(200);
        settleAsPast(link.appointment().id());

        ok(call(post("/api/app/clients/" + client.id() + "/anonymize")));

        assertThat(error(mvc.perform(get(path).param("token", link.accessToken())).andReturn(), 404)).isEqualTo("TOKEN_INVALID");
        assertThat(error(mvc.perform(get(path + "/calendar.ics").param("token", link.accessToken())).andReturn(), 404))
                .isEqualTo("TOKEN_INVALID");
        assertThat(count("select count(*) from booking_access_token where appointment_id = ? and revoked_at is null",
                link.appointment().id())).isZero();
        assertThat(error(call(post("/api/app/appointments/" + link.appointment().id() + "/access-link")), 409))
                .isEqualTo("CLIENT_ANONYMIZED");
        assertThat(error(call(post("/api/app/appointments").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(
                Map.of("specialistId", studio.master(), "serviceId", studio.service(), "clientId", client.id(),
                        "serviceStart", slot(3, 0).toString(), "marketingConsent", false, "source", "CONSOLE",
                        "skipNoticeAndHorizon", false)))), 409)).isEqualTo("CLIENT_ANONYMIZED");
        assertThat(error(call(post("/api/app/clients/" + client.id() + "/trust").param("level", "LOW").param("reason", "x")), 409))
                .isEqualTo("CLIENT_ANONYMIZED");
        assertThat(error(call(post("/api/app/clients/" + client.id() + "/packages")
                .param("name", "x").param("sessions", "1").param("valueMinor", "1")), 409)).isEqualTo("CLIENT_ANONYMIZED");
    }

    @Test
    void theSamePhoneBooksANewClientWithoutAnyLinkToTheOldOne() throws Exception {
        Person client = client();
        UUID oldVisit = pastVisit(client);
        ok(call(post("/api/app/clients/" + client.id() + "/anonymize")));

        PublicBookingResult again = publicVisit(new Person(null, "Another Person", client.phone()), slot(2, 2));

        UUID newClient = asOwner(() -> booking.find(again.appointment().id())).orElseThrow().clientId();
        assertThat(newClient).isNotEqualTo(client.id());
        JsonNode card = ok(call(get("/api/app/clients/" + newClient)));
        assertThat(card.path("client").path("normalizedPhone").asText()).isEqualTo(client.phone());
        assertThat(card.path("client").path("displayName").asText()).isEqualTo("Another Person");
        assertThat(card.path("client").path("completedVisitCount").asInt()).isZero();
        assertThat(card.path("history").toString()).doesNotContain(oldVisit.toString());
        assertThat(card.path("history")).hasSize(1);
        assertThat(one("select coalesce(merged_into_client_id::text, 'none') from client where id = ?", client.id())).isEqualTo("none");
    }

    @Test
    void aMasterKeepsTheVisitButNeverTheNameAndCannotAnonymize() throws Exception {
        Person client = client();
        UUID visit = pastVisit(client);
        RequestPostProcessor nino = master(studio.tenant(), studio.master());
        assertThat(error(mvc.perform(post("/api/app/clients/" + client.id() + "/anonymize").with(nino)).andReturn(), 403))
                .isEqualTo("FORBIDDEN_RESOURCE");
        assertThat(ok(mvc.perform(get("/api/app/clients/" + client.id()).with(nino)).andReturn()).toString()).contains(client.name());

        ok(call(post("/api/app/clients/" + client.id() + "/anonymize")));

        for (MockHttpServletRequestBuilder read : List.of(
                get("/api/app/clients/" + client.id()),
                get("/api/app/appointments/" + visit),
                get("/api/app/clients").param("q", client.name()),
                get("/api/app/calendar").param("from", Instant.now().minus(30, ChronoUnit.DAYS).toString())
                        .param("to", Instant.now().plus(1, ChronoUnit.DAYS).toString()))) {
            String seen = ok(mvc.perform(read.with(nino)).andReturn()).toString();
            assertThat(seen).doesNotContain(client.name()).doesNotContain(client.phone());
        }
        assertThat(ok(mvc.perform(get("/api/app/appointments/" + visit).with(nino)).andReturn())
                .path("appointment").path("clientDisplayName").asText()).isEqualTo("anonymized");
    }

    /** Free text about the person has no name in it: the conversation goes as a whole, journals included. */
    @Test
    void textWithoutAnyNameLeavesTheAiTablesWithTheConversation() throws Exception {
        Person client = client();
        String noteMarker = "NOTEMARK" + letters().toUpperCase();
        String reasonMarker = "REASONMARK" + letters().toUpperCase();
        asOwner(() -> crm.updateNotes(client.id(), noteMarker, noteMarker));
        ok(call(put("/api/app/ai/settings").contentType(MediaType.APPLICATION_JSON).content("{\"notesToLlm\":true}")));
        Instant start = slot(5, 0);
        AppointmentView visit = asOwner(() -> booking.create(new CreateBookingCommand(studio.master(), studio.service(), start, client.id(),
                null, null, null, false, null, null, "CONSOLE", null, false)));
        String day = start.atZone(TBILISI).toLocalDate().toString();
        stub.enqueue(
                toolTurn("get_schedule", "{\"dateFrom\":\"" + day + "\",\"dateTo\":\"" + day + "\"}"),
                toolTurn("get_client_profile", "{\"clientRef\":\"CLIENT_1\"}"),
                toolTurn("draft_cancel", "{\"visitRef\":\"VISIT_1\",\"initiatedBy\":\"client\",\"reason\":\"" + reasonMarker + "\"}"),
                new LlmResponse(new LlmMessage.Assistant("Done " + reasonMarker, List.of()), FinishReason.STOP, new Usage(100, 10)));
        JsonNode turn = ok(call(post("/api/app/ai/chat").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(
                Map.of("locale", "en", "message", "Cancel for " + client.name() + " " + reasonMarker)))));
        UUID conversation = UUID.fromString(turn.path("conversationId").asText());
        ok(call(post("/api/app/appointments/" + visit.id() + "/transition").param("to", "CANCELLED_BY_BUSINESS")));

        assertThat(scanFor(List.of(noteMarker))).as("the note went to the LLM log").contains("client.notes", "ai_tool_call.result");
        assertThat(scanFor(List.of(reasonMarker))).contains("ai_message.content", "ai_tool_call.arguments", "ai_prompt_log.response", "ai_draft.payload");

        ok(call(post("/api/app/clients/" + client.id() + "/anonymize")));

        assertThat(scanFor(List.of(noteMarker, reasonMarker))).isEmpty();
        assertThat(scan(client)).isEmpty();
        assertThat(error(call(get("/api/app/ai/conversations/" + conversation)), 404)).isEqualTo("CONVERSATION_NOT_FOUND");
        assertThat(count("select count(*) from ai_prompt_log where conversation_id = ?", conversation)).isZero();
        assertThat(count("select count(*) from ai_tool_call where conversation_id = ?", conversation)).isZero();
        assertThat(count("select count(*) from ai_prompt_log where conversation_id is null and tenant_id = ?", studio.tenant())).isZero();
        assertThat(count("select count(*) from ai_tool_call where conversation_id is null and tenant_id = ?", studio.tenant())).isZero();
    }

    /** A merge retires a profile but not the person: the dialogue about either client stays, only the absorbed name goes. */
    @Test
    void aMergeKeepsTheConversationsOfTheSurvivor() throws Exception {
        Person survivor = client();
        Person absorbed = client();
        UUID ofSurvivor = plantInEveryModule(survivor);
        UUID ofAbsorbed = plantInEveryModule(absorbed);
        int messages = count("select count(*) from ai_message where conversation_id = ?", ofSurvivor);
        assertThat(messages).isPositive();

        ok(call(post("/api/app/clients/" + survivor.id() + "/merge").param("absorbedId", absorbed.id().toString())));

        assertThat(count("select count(*) from ai_message where conversation_id = ?", ofSurvivor)).isEqualTo(messages);
        assertThat(count("select count(*) from ai_prompt_log where conversation_id = ?", ofSurvivor)).isPositive();
        assertThat(ok(call(get("/api/app/ai/conversations/" + ofSurvivor))).toString()).contains(survivor.name());
        assertThat(ok(call(get("/api/app/ai/conversations/" + ofAbsorbed))).toString()).doesNotContain(absorbed.name()).contains("anonymized");
    }

    /** A read that fetched the override before an erasure committed must not write the reason back. */
    @Test
    void aTrustReadThatWaitedForTheErasureDoesNotBringTheReasonBack() throws Exception {
        Person client = client();
        asOwner(() -> retention.override(client.id(), "LOW", "Argued, " + client.name()));
        try (Connection held = heldOpen();
             PreparedStatement delete = held.prepareStatement("delete from trust_state where client_id = ?")) {
            delete.setObject(1, client.id());
            assertThat(delete.executeUpdate()).isEqualTo(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<?> assessed = pool.submit(() -> asOwner(() -> retention.assess(client.id())));
                waitUntilBlocked();
                held.commit();
                assessed.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(count("select count(*) from trust_state where client_id = ? and (override_reason is not null or override_level is not null)",
                client.id())).isZero();
    }

    @Test
    void anUnsettledPastVisitCannotBeMovedButStillSettlesWithoutItsNote() throws Exception {
        Person client = client();
        UUID visit = pastVisit(client);
        ok(call(post("/api/app/clients/" + client.id() + "/anonymize")));

        assertThat(error(call(post("/api/app/appointments/" + visit + "/reschedule").param("start", slot(3, 2).toString())), 409))
                .isEqualTo("CLIENT_ANONYMIZED");

        JsonNode settled = ok(call(post("/api/app/appointments/" + visit + "/transition").param("to", "COMPLETED")
                .param("note", "Typed after the erasure")));
        assertThat(settled.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(settled.path("clientAnonymized").asBoolean()).isTrue();
        assertThat(one("select coalesce(note, 'none') from appointment where id = ?", visit)).isEqualTo("none");
        assertThat(ok(call(get("/api/app/clients/" + client.id()))).path("client").path("completedVisitCount").asInt()).isEqualTo(1);
    }

    @Test
    void consentCannotBeWithdrawnOnAnAnonymizedProfile() throws Exception {
        Person client = client();
        ok(call(post("/api/app/clients/" + client.id() + "/anonymize")));

        assertThat(error(call(post("/api/app/clients/" + client.id() + "/consent/withdraw")), 409)).isEqualTo("CLIENT_ANONYMIZED");

        assertThat(count("select count(*) from audit_log where action = 'client.consent.withdraw' and entity_id = ?", client.id())).isZero();
    }

    /** The card tells retirement by its fields, not by the name, and carries the sessions the confirmation warns about. */
    @Test
    void theCardNamesTheRetirementAndTheRemainingSessions() throws Exception {
        Person active = client();
        asOwner(() -> retention.sellPackage(active.id(), "Five cuts", 5, 10_000, "GEL"));
        JsonNode card = ok(call(get("/api/app/clients/" + active.id())));
        assertThat(card.path("anonymizedAt").isNull()).isTrue();
        assertThat(card.path("mergedIntoClientId").isNull()).isTrue();
        assertThat(card.path("packages").get(0).path("remainingSessions").asInt()).isEqualTo(5);

        ClientView named = asOwner(() -> crm.findOrCreate("+995591000777", "anonymized", "en", false, null));
        assertThat(ok(call(get("/api/app/clients/" + named.id()))).path("anonymizedAt").isNull()).as("a person may be called that").isTrue();

        ok(call(post("/api/app/clients/" + active.id() + "/anonymize")));
        assertThat(ok(call(get("/api/app/clients/" + active.id()))).path("anonymizedAt").isNull()).isFalse();

        Person survivor = client();
        Person absorbed = client();
        ok(call(post("/api/app/clients/" + survivor.id() + "/merge").param("absorbedId", absorbed.id().toString())));
        assertThat(ok(call(get("/api/app/clients/" + absorbed.id()))).path("mergedIntoClientId").asText()).isEqualTo(survivor.id().toString());
    }

    /** A merged into B merged into C: the free text of A's dialogue goes when C is erased. */
    @Test
    void erasingTheSurvivorErasesTheDialoguesOfEveryProfileMergedIntoIt() throws Exception {
        Person first = client();
        Person second = client();
        Person survivor = client();
        String marker = "CHAINMARK" + letters().toUpperCase();
        UUID dialogue = dialogueNaming(first, marker);
        pastVisit(first);
        pastVisit(second);
        asOwner(() -> crm.updateNotes(second.id(), "About " + second.name() + " and " + first.name(), null));
        ok(call(post("/api/app/clients/" + second.id() + "/merge").param("absorbedId", first.id().toString())));
        ok(call(post("/api/app/clients/" + survivor.id() + "/merge").param("absorbedId", second.id().toString())));
        assertThat(scanFor(List.of(marker))).as("a merge keeps the dialogue").contains("ai_message.content");
        assertThat(scan(first)).as("the visit carried over keeps what was typed").contains("appointment.entered_name", "client.notes");
        assertThat(scan(second)).contains("appointment.entered_name", "client.notes");

        ok(call(post("/api/app/clients/" + survivor.id() + "/anonymize")));

        assertThat(scanFor(List.of(marker))).isEmpty();
        assertThat(scan(first)).as("the whole schema, AI or not").isEmpty();
        assertThat(scan(second)).isEmpty();
        assertThat(scan(survivor)).isEmpty();
        assertThat(error(call(get("/api/app/ai/conversations/" + dialogue)), 404)).isEqualTo("CONVERSATION_NOT_FOUND");
    }

    private UUID dialogueNaming(Person client, String marker) throws Exception {
        stub.enqueue(new LlmResponse(new LlmMessage.Assistant("Noted " + marker, List.of()), FinishReason.STOP, new Usage(100, 10)));
        JsonNode turn = ok(call(post("/api/app/ai/chat").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(
                Map.of("locale", "en", "message", client.name() + " said " + marker)))));
        return UUID.fromString(turn.path("conversationId").asText());
    }

    private AiLogStore.ToolCall toolLog(UUID conversation) {
        return new AiLogStore.ToolCall(conversation, null, "get_metrics", "{}", "{}", AiLogStore.ToolStatus.OK, null, 1);
    }

    /** A turn logging while the erasure holds the row waits for it, finds nothing and writes nothing. */
    @Test
    void aLogOfARunningTurnDoesNotAppearAfterItsDialogueIsDeleted() throws Exception {
        Person client = client();
        UUID dialogue = dialogueNaming(client, "LOGMARK");
        int before = count("select count(*) from ai_tool_call where conversation_id = ?", dialogue);
        try (Connection held = heldOpen();
             PreparedStatement delete = held.prepareStatement("delete from ai_conversation where id = ?")) {
            delete.setObject(1, dialogue);
            assertThat(delete.executeUpdate()).isEqualTo(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<UUID> logged = pool.submit(() -> asOwner(() -> logs.logToolCall(toolLog(dialogue))));
                waitUntilBlocked();
                assertThat(logged.isDone()).isFalse();
                held.commit();
                logged.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(count("select count(*) from ai_tool_call where conversation_id = ?", dialogue)).isEqualTo(before);
    }

    /** The prompt log goes the same way as the tool log. */
    @Test
    void aPromptLogOfARunningTurnDoesNotAppearAfterItsDialogueIsDeleted() throws Exception {
        Person client = client();
        UUID dialogue = dialogueNaming(client, "PROMPTMARK");
        int before = count("select count(*) from ai_prompt_log where conversation_id = ?", dialogue);
        try (Connection held = heldOpen();
             PreparedStatement delete = held.prepareStatement("delete from ai_conversation where id = ?")) {
            delete.setObject(1, dialogue);
            assertThat(delete.executeUpdate()).isEqualTo(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<UUID> logged = pool.submit(() -> asOwner(() -> logs.logLlmCall(new AiLogStore.LlmCall(
                        dialogue, null, "stub", "stub", "LATEPROMPTMARK", null, null, null, null, null, null))));
                waitUntilBlocked();
                held.commit();
                logged.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(count("select count(*) from ai_prompt_log where conversation_id = ?", dialogue)).isEqualTo(before);
        assertThat(scanFor(List.of("LATEPROMPTMARK"))).isEmpty();
    }

    /** The turn waits on its usage row after the first answer while the dialogue goes: the provider hears nothing more. */
    @Test
    void aTurnErasedBetweenTwoCallsSendsNothingMoreToTheProvider() throws Exception {
        Person client = client();
        UUID dialogue = dialogueNaming(client, "STOPMARK");
        stub.reset();
        stub.enqueue(toolTurn("get_metrics", "{}"),
                new LlmResponse(new LlmMessage.Assistant("Must not be asked for", List.of()), FinishReason.STOP, new Usage(100, 10)));
        try (Connection held = heldOpen();
             PreparedStatement usage = held.prepareStatement("select 1 from ai_usage_month where tenant_id = ? for update")) {
            usage.setObject(1, studio.tenant());
            usage.executeQuery().close();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<MvcResult> turn = pool.submit(() -> call(post("/api/app/ai/chat").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(
                        Map.of("conversationId", dialogue.toString(), "locale", "en", "message", "and one more thing")))));
                waitUntilBlocked();
                assertThat(stub.recordedRequests()).as("the first answer is in").hasSize(1);
                assertThat(update("delete from ai_conversation where id = ?", dialogue)).isEqualTo(1);
                held.commit();
                assertThat(error(turn.get(30, TimeUnit.SECONDS), 409)).isEqualTo("AI_CONVERSATION_ERASED");
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(stub.recordedRequests()).as("no call after the erasure").hasSize(1);
    }

    /** A log row in flight when the erasure starts: the erasure waits for it and then removes it as well. */
    @Test
    void anErasureThatWaitsForALogInFlightRemovesThatRowToo() throws Exception {
        Person client = client();
        UUID dialogue = dialogueNaming(client, "FLIGHTMARK");
        UUID row = UUID.randomUUID();
        try (Connection held = heldOpen();
             PreparedStatement share = held.prepareStatement("select 1 from ai_conversation where id = ? for key share");
             PreparedStatement insert = held.prepareStatement("insert into ai_tool_call (id, tenant_id, conversation_id, tool_name, arguments, status, duration_ms, created_at)"
                     + " values (?, ?, ?, 'get_metrics', 'INFLIGHTMARK', 'OK', 1, now())")) {
            share.setObject(1, dialogue);
            share.executeQuery().close();
            insert.setObject(1, row);
            insert.setObject(2, studio.tenant());
            insert.setObject(3, dialogue);
            insert.executeUpdate();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<MvcResult> anonymized = pool.submit(() -> call(post("/api/app/clients/" + client.id() + "/anonymize")));
                waitUntilBlocked();
                held.commit();
                ok(anonymized.get(30, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(scanFor(List.of("INFLIGHTMARK"))).isEmpty();
    }

    /** Turns do not queue: the log lock conflicts neither with another log nor with the lock of a ref or a message. */
    @Test
    void logsOfTurnsDoNotWaitForEachOtherOrForARefBeingWritten() throws Exception {
        Person client = client();
        UUID dialogue = dialogueNaming(client, "QUEUEMARK");
        int before = count("select count(*) from ai_tool_call where conversation_id = ?", dialogue);
        for (String lock : List.of("for no key update", "for key share")) {
            try (Connection held = heldOpen();
                 PreparedStatement hold = held.prepareStatement("select 1 from ai_conversation where id = ? " + lock)) {
                hold.setObject(1, dialogue);
                hold.executeQuery().close();
                ExecutorService pool = Executors.newSingleThreadExecutor();
                try {
                    pool.submit(() -> asOwner(() -> logs.logToolCall(toolLog(dialogue)))).get(20, TimeUnit.SECONDS);
                } finally {
                    pool.shutdownNow();
                }
                held.rollback();
            }
        }
        assertThat(count("select count(*) from ai_tool_call where conversation_id = ?", dialogue)).isEqualTo(before + 2);
    }

    /** The dialogue goes while the turn waits to write: 409, so the console does not send the text on to a new dialogue. */
    @Test
    void aTurnWhoseDialogueIsErasedMeanwhileAnswersConflictNotNotFound() throws Exception {
        Person client = client();
        UUID dialogue = dialogueNaming(client, "TURNMARK");
        assertThat(error(call(post("/api/app/ai/chat").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(
                Map.of("conversationId", UUID.randomUUID().toString(), "locale", "en", "message", "hello")))), 404))
                .as("an unknown dialogue stays a plain 404").isEqualTo("CONVERSATION_NOT_FOUND");
        try (Connection held = heldOpen();
             PreparedStatement delete = held.prepareStatement("delete from ai_conversation where id = ?")) {
            delete.setObject(1, dialogue);
            assertThat(delete.executeUpdate()).isEqualTo(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<MvcResult> turn = pool.submit(() -> call(post("/api/app/ai/chat").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(
                        Map.of("conversationId", dialogue.toString(), "locale", "en", "message", "and one more thing")))));
                waitUntilBlocked();
                held.commit();
                assertThat(error(turn.get(30, TimeUnit.SECONDS), 409)).isEqualTo("AI_CONVERSATION_ERASED");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // ---------------------------------------------------------------- refusals

    @Test
    void anUnknownClientOrOneOfAnotherTenantIsNotFound() throws Exception {
        Person client = client();
        assertThat(error(call(post("/api/app/clients/" + UUID.randomUUID() + "/anonymize")), 404)).isEqualTo("CLIENT_NOT_FOUND");

        UUID other = newStudio().tenant();
        MvcResult foreign = mvc.perform(post("/api/app/clients/" + client.id() + "/anonymize").with(owner(other))).andReturn();

        assertThat(error(foreign, 404)).isEqualTo("CLIENT_NOT_FOUND");
        assertThat(one("select display_name from client where id = ?", client.id())).isEqualTo(client.name());
    }

    @Test
    void anUpcomingActiveVisitRefusesWithNothingChangedAndCancellingFreesIt() throws Exception {
        Person client = client();
        UUID conversation = plantInEveryModule(client);
        PublicBookingResult upcoming = publicVisit(client, slot(2, 3));
        List<String> before = column("select display_name || normalized_phone || coalesce(notes, '') from client where id = ?", client.id());
        List<String> draftBefore = column("select status || payload from ai_draft where conversation_id = ?", conversation);

        assertThat(error(call(post("/api/app/clients/" + client.id() + "/anonymize")), 409)).isEqualTo("CLIENT_HAS_UPCOMING_VISITS");

        assertThat(column("select display_name || normalized_phone || coalesce(notes, '') from client where id = ?", client.id()))
                .isEqualTo(before);
        assertThat(column("select status || payload from ai_draft where conversation_id = ?", conversation))
                .as("the AI scrub rolls back with the refusal").isEqualTo(draftBefore);
        assertThat(mvc.perform(get("/api/public/bookings/" + upcoming.appointment().id()).param("token", upcoming.accessToken()))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(count("select count(*) from audit_log where action = 'client.anonymize' and entity_id = ?", client.id())).isZero();

        ok(call(post("/api/app/appointments/" + upcoming.appointment().id() + "/transition").param("to", "CANCELLED_BY_BUSINESS")));
        ok(call(post("/api/app/clients/" + client.id() + "/anonymize")));

        assertThat(scan(client)).isEmpty();
    }

    @Test
    void aVisitInProgressCountsAsUpcomingAndAnUnsettledPastOneDoesNot() throws Exception {
        Person client = client();
        UUID unsettled = pastVisit(client);
        UUID running = pastVisit(client);
        update("update appointment set service_start = now() - interval '10 minutes', occupied_start = now() - interval '10 minutes',"
                + " service_end = now() + interval '10 minutes', occupied_end = now() + interval '10 minutes' where id = ?", running);

        assertThat(error(call(post("/api/app/clients/" + client.id() + "/anonymize")), 409)).isEqualTo("CLIENT_HAS_UPCOMING_VISITS");

        update("update appointment set status = 'COMPLETED' where id = ?", running);
        ok(call(post("/api/app/clients/" + client.id() + "/anonymize")));

        assertThat(one("select status from appointment where id = ?", unsettled)).isEqualTo("CONFIRMED");
        assertThat(count("select count(*) from appointment where id = ? and entered_name is null", unsettled)).isEqualTo(1);
    }

    /** Decision: a repeat is refused rather than answered 200, so no second audit record and no "success" for a merged profile. */
    @Test
    void aRepeatAndAMergedProfileAreRefusedWithOneAuditRecord() throws Exception {
        Person client = client();
        ok(call(post("/api/app/clients/" + client.id() + "/anonymize")));

        assertThat(error(call(post("/api/app/clients/" + client.id() + "/anonymize")), 409)).isEqualTo("CLIENT_ALREADY_ANONYMIZED");
        assertThat(count("select count(*) from audit_log where action = 'client.anonymize' and entity_id = ?", client.id())).isEqualTo(1);

        Person survivor = client();
        Person absorbed = client();
        ok(call(post("/api/app/clients/" + survivor.id() + "/merge").param("absorbedId", absorbed.id().toString())));
        assertThat(error(call(post("/api/app/clients/" + absorbed.id() + "/anonymize")), 409)).isEqualTo("CLIENT_ALREADY_ANONYMIZED");
    }

    @Test
    void twoAnonymizationsAtOnceMakeOneAuditRecord() throws Exception {
        Person client = client();
        List<Integer> statuses = race(
                () -> call(post("/api/app/clients/" + client.id() + "/anonymize")).getResponse().getStatus(),
                () -> call(post("/api/app/clients/" + client.id() + "/anonymize")).getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(count("select count(*) from audit_log where action = 'client.anonymize' and entity_id = ?", client.id())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- races, held open with a second connection

    /** The booking read the row before the anonymization committed: it must not write the name back onto the row. */
    @Test
    void aBookingThatReadTheClientBeforeTheCommitCreatesANewClientInstead() throws Exception {
        Person client = client();
        try (Connection held = heldOpen()) {
            retire(held, client);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<ClientView> found = pool.submit(() -> asOwner(() -> crm.findOrCreate(client.phone(), client.name(), "en", true, "v1")));
                waitUntilBlocked();
                held.commit();
                ClientView result = found.get(30, TimeUnit.SECONDS);

                assertThat(result.id()).isNotEqualTo(client.id());
                assertThat(result.normalizedPhone()).isEqualTo(client.phone());
                assertThat(one("select display_name from client where id = ?", client.id())).isEqualTo("anonymized");
                assertThat(one("select marketing_consent::text from client where id = ?", client.id())).isEqualTo("false");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /** A booking by client id that got past every read waits for the anonymization and is then refused. */
    @Test
    void aBookingByIdThatWaitsForTheAnonymizationIsRefused() throws Exception {
        Person client = client();
        Instant start = slot(3, 1);
        try (Connection held = heldOpen()) {
            retire(held, client);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<AppointmentView> created = pool.submit(() -> asOwner(() -> booking.create(new CreateBookingCommand(
                        studio.master(), studio.service(), start, client.id(), null, "Typed Name", null, false, null, null, "CONSOLE", null, false))));
                waitUntilBlocked();
                held.commit();
                assertThatThrownBy(() -> {
                    try {
                        created.get(30, TimeUnit.SECONDS);
                    } catch (ExecutionException e) {
                        throw e.getCause();
                    }
                }).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("CLIENT_ANONYMIZED"));
                assertThat(count("select count(*) from appointment where client_id = ?", client.id())).isZero();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /** A booking of the client still open when the anonymization starts: it waits, then sees the new visit and refuses. */
    @Test
    void anAnonymizationThatWaitsForABookingSeesTheVisitAndRefuses() throws Exception {
        Person client = client();
        UUID visit = pastVisit(client);
        try (Connection held = heldOpen();
             PreparedStatement lock = held.prepareStatement("select id from client where id = ? for key share");
             PreparedStatement move = held.prepareStatement("update appointment set service_start = now() + interval '5 days',"
                     + " service_end = now() + interval '5 days 30 minutes', occupied_start = now() + interval '5 days',"
                     + " occupied_end = now() + interval '5 days 30 minutes' where id = ?")) {
            lock.setObject(1, client.id());
            lock.executeQuery().close();
            move.setObject(1, visit);
            assertThat(move.executeUpdate()).isEqualTo(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<MvcResult> anonymized = pool.submit(() -> call(post("/api/app/clients/" + client.id() + "/anonymize")));
                waitUntilBlocked();
                held.commit();
                assertThat(error(anonymized.get(30, TimeUnit.SECONDS), 409)).isEqualTo("CLIENT_HAS_UPCOMING_VISITS");
                assertThat(one("select display_name from client where id = ?", client.id())).isEqualTo(client.name());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static Connection heldOpen() throws SQLException {
        Connection connection = DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
        connection.setAutoCommit(false);
        return connection;
    }

    /** What the anonymization writes, not yet committed. */
    private static void retire(Connection held, Person client) throws SQLException {
        try (PreparedStatement retire = held.prepareStatement(
                "update client set display_name = 'anonymized', normalized_phone = ?, marketing_consent = false, anonymized_at = now() where id = ?")) {
            retire.setString(1, "anon-" + client.id().toString().substring(0, 20));
            retire.setObject(2, client.id());
            assertThat(retire.executeUpdate()).isEqualTo(1);
        }
    }

    /** Until a session of the runtime role waits for a row lock. */
    private static void waitUntilBlocked() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (count("select count(*) from pg_stat_activity where usename = 'cadence_app' and wait_event_type = 'Lock'") > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("No session of the runtime role started waiting for a lock");
    }

    private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<T> one = pool.submit(first);
            Future<T> two = pool.submit(second);
            return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- data planted

    /** Notes, tags, trust, a package, an owner task and an AI conversation with a draft that all name the client. */
    private UUID plantInEveryModule(Person client) throws Exception {
        asOwner(() -> crm.updateNotes(client.id(), "Allergic, " + client.name() + ", " + client.phone(), "vip " + client.name()));
        asOwner(() -> retention.override(client.id(), "LOW", "Argued, " + client.name()));
        asOwner(() -> retention.sellPackage(client.id(), "Pack of " + client.name(), 5, 10_000, "GEL"));
        asOwner(() -> ownerTasks.create("MANUAL_MESSAGE", "Call " + client.name(), "Phone " + client.phone(), "Hi " + client.name(), null, null, client.id()));
        Instant start = slot(4, 0);
        stub.enqueue(
                new LlmResponse(new LlmMessage.Assistant(null, List.of(new ToolCall("call-" + CALL_IDS.incrementAndGet(), "draft_booking",
                        "{\"clientRef\":\"CLIENT_1\",\"masterRef\":\"MASTER_1\",\"serviceRef\":\"SERVICE_1\",\"start\":\""
                                + LOCAL.format(start.atZone(TBILISI)) + "\"}"))), FinishReason.TOOL_CALLS, new Usage(100, 10)),
                new LlmResponse(new LlmMessage.Assistant("Proposed CLIENT_1", List.of()), FinishReason.STOP, new Usage(100, 10)));
        Map<String, String> body = new LinkedHashMap<>();
        body.put("locale", "en");
        body.put("message", "Book " + client.name() + " for a cut");
        JsonNode turn = ok(call(post("/api/app/ai/chat").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body))));
        return UUID.fromString(turn.path("conversationId").asText());
    }

    private static LlmResponse toolTurn(String tool, String args) {
        return new LlmResponse(new LlmMessage.Assistant(null, List.of(new ToolCall("call-" + CALL_IDS.incrementAndGet(), tool, args))),
                FinishReason.TOOL_CALLS, new Usage(100, 10));
    }

    private PublicBookingResult publicVisit(Person person, Instant start) {
        return as(studio.tenant(), null, () -> booking.createPublic(new CreateBookingCommand(
                studio.master(), studio.service(), start, null, person.phone(), person.name(), "en", true, "public-v1", null, "PUBLIC", null, false)));
    }

    /** A settled-looking past visit of the client: created in the past, with a note and the typed name on it. */
    private UUID pastVisit(Person client) {
        Instant start = Instant.now().minus(3 + CALL_IDS.incrementAndGet(), ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        return asOwner(() -> booking.create(new CreateBookingCommand(studio.master(), studio.service(), start, null,
                client.phone(), client.name(), "en", false, null, "About " + client.name(), "CONSOLE", AppointmentStatus.CONFIRMED, true))).id();
    }

    /** Moves a public booking into the past, so the upcoming-visit rule does not apply to it. */
    private static void settleAsPast(UUID visit) throws SQLException {
        update("update appointment set service_start = now() - interval '26 hours', service_end = now() - interval '25 hours',"
                + " occupied_start = now() - interval '26 hours', occupied_end = now() - interval '25 hours', status = 'COMPLETED' where id = ?", visit);
    }

    // ---------------------------------------------------------------- reads

    /** Every text column of every table of the application schema that holds the client's name, a word of it, or the phone. */
    private static List<String> scan(Person client) throws SQLException {
        List<String> terms = new ArrayList<>(List.of(client.name().split(" ")));
        terms.add(client.phone());
        terms.add(client.phone().substring(1));
        terms.add(client.phone().substring(4));
        return scanFor(terms);
    }

    private static List<String> scanFor(List<String> terms) throws SQLException {
        List<String> hits = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
             Statement columns = c.createStatement();
             ResultSet rs = columns.executeQuery("select c.table_name, c.column_name from information_schema.columns c "
                     + "join information_schema.tables t on t.table_schema = c.table_schema and t.table_name = c.table_name "
                     + "where c.table_schema = 'public' and t.table_type = 'BASE TABLE' and c.table_name <> 'flyway_schema_history' "
                     + "and c.data_type in ('character varying', 'text', 'character', 'json', 'jsonb') order by 1, 2")) {
            while (rs.next()) {
                String table = rs.getString(1);
                String column = rs.getString(2);
                for (String term : terms) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "select count(*) from \"" + table + "\" where \"" + column + "\"::text ilike ?")) {
                        ps.setString(1, "%" + term + "%");
                        try (ResultSet found = ps.executeQuery()) {
                            found.next();
                            if (found.getInt(1) > 0) {
                                hits.add(table + "." + column);
                                break;
                            }
                        }
                    }
                }
            }
        }
        return hits;
    }

    private Map<String, String> metrics() throws Exception {
        Map<String, String> metrics = new LinkedHashMap<>();
        for (JsonNode metric : ok(call(get("/api/app/metrics")))) {
            metrics.put(metric.path("key").asText(), "value=" + metric.path("value") + " sample=" + metric.path("sampleSize").asInt()
                    + " insufficient=" + metric.path("insufficientData").asBoolean());
        }
        return metrics;
    }

    private MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(owner(studio.tenant()))).andReturn();
    }

    private static JsonNode ok(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    private static String error(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return JSON.readTree(result.getResponse().getContentAsByteArray()).path("code").asText();
    }

    // ---------------------------------------------------------------- fixtures

    private <T> T as(UUID tenant, ActorContext.Actor actor, Supplier<T> call) {
        try (TenantContext.Scope ignored = TenantContext.open(tenant)) {
            if (actor != null) {
                ActorContext.set(actor);
            }
            return call.get();
        } finally {
            ActorContext.clear();
        }
    }

    private <T> T asOwner(Supplier<T> call) {
        return as(studio.tenant(), new ActorContext.Actor("owner-it", ActorContext.Role.OWNER, null), call);
    }

    private Studio newStudio() {
        UUID tenant = UUID.randomUUID();
        tenants.upsert(tenant, "anon-it-" + tenant, "Anon IT Studio", "Asia/Tbilisi", "GEL", "GE");
        return as(tenant, ActorContext.system(), () -> {
            catalog.saveRules(new BookingRulesView(15, 60, 30, true, 2, false, false, 1, true));
            UUID service = catalog.upsertService(new ServiceView(null, "Cut", "Cut", 30, 5000, 0, 0, "#0369a1", true, true)).id();
            UUID specialist = catalog.upsertSpecialist(new SpecialistView(null, "Tamar Gelashvili", null, "#0f766e", true)).id();
            catalog.upsertMatrix(new MasterServiceView(UUID.randomUUID(), specialist, service, true, null, null, null, null));
            catalog.replaceWeeklySchedule(specialist, IntStream.rangeClosed(1, 7)
                    .mapToObj(day -> new WeeklyInterval(UUID.randomUUID(), specialist, day, 0, 1440))
                    .toList());
            return new Studio(tenant, specialist, service);
        });
    }

    private Person client() {
        String name = "Zephyrina" + letters() + " Quillfeather" + letters();
        String phone = "+995591" + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        ClientView client = asOwner(() -> crm.findOrCreate(phone, name, "en", true, "public-v1"));
        return new Person(client.id(), name, phone);
    }

    private Instant slot(int daysAhead, int index) {
        LocalDate day = LocalDate.now(TBILISI).plusDays(daysAhead);
        return asOwner(() -> booking.freeSlots(studio.master(), studio.service(), day, day)).get(index);
    }

    private static String letters() {
        return Long.toString(ThreadLocalRandom.current().nextLong(1L << 40), 26).replaceAll("[0-9]", "x");
    }

    private static List<String> column(String sql, Object... args) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            List<String> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
            return rows;
        }
    }

    private static String one(String sql, Object... args) throws SQLException {
        List<String> rows = column(sql, args);
        assertThat(rows).as(sql).hasSize(1);
        return rows.get(0);
    }

    private static int count(String sql, Object... args) throws SQLException {
        return Integer.parseInt(one(sql, args));
    }

    private static int update(String sql, Object... args) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }
}

package com.cadence.app;

import com.cadence.ai.api.AiAssistantService;
import com.cadence.ai.internal.llm.FinishReason;
import com.cadence.ai.internal.llm.LlmMessage;
import com.cadence.ai.internal.llm.LlmRequest;
import com.cadence.ai.internal.llm.LlmResponse;
import com.cadence.ai.internal.llm.StubLlmProvider;
import com.cadence.ai.internal.llm.ToolCall;
import com.cadence.ai.internal.llm.Usage;
import com.cadence.ai.internal.privacy.PseudonymRegistry;
import com.cadence.ai.internal.privacy.Restorer;
import com.cadence.app.seed.DemoIds;
import com.cadence.app.usecase.ClientMergeService;
import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.BookingService;
import com.cadence.booking.api.CreateBookingCommand;
import com.cadence.catalog.api.BookingRulesView;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.MasterServiceView;
import com.cadence.catalog.api.ServiceView;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.catalog.api.WeeklyInterval;
import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.platform.ai.AiPseudonymStore;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.phone.PhoneShape;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
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
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * ADR 0002 §2 to §8 end to end through the HTTP API ({@code /api/app/ai/**}, JWT owner) with the scripted
 * {@link StubLlmProvider}: one scripted response per LLM call of a turn. Every test runs in its own tenant with one master
 * and one service, so the refs are the first of their kind in a fresh conversation ({@code CLIENT_1}, {@code MASTER_1},
 * {@code SERVICE_1}, {@code VISIT_1}); the system prompt recorded by the stub proves it. Stored rows are read with the
 * owner connection (it bypasses RLS). The scenarios are (a) to (j) of {@code docs/stages/05-ai-manager.md}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiAssistantIT extends AbstractTimescaleIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm");
    private static final ZoneId TBILISI = ZoneId.of("Asia/Tbilisi");
    private static final AtomicInteger CALL_IDS = new AtomicInteger();

    private static final String CLIENT = "CLIENT_1";
    private static final String MASTER = "MASTER_1";
    private static final String SERVICE = "SERVICE_1";
    private static final String VISIT = "VISIT_1";
    private static final String WITHHELD = "[withheld by tenant setting]";

    private record Studio(UUID tenant, UUID master, String masterName, UUID service) {
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
    AiPseudonymStore pseudonyms;
    @Autowired
    AiAssistantService assistant;
    @Autowired
    ClientMergeService merge;

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

    // ---------------------------------------------------------------- scenarios

    /** (a) metrics, slots, a draft, text: numbers of the port, nothing booked until the owner confirms. */
    @Test
    void metricsSlotsDraftThenConfirmBooksTheVisitOnceWithAudit() throws Exception {
        Person client = client();
        Instant start = slot(3, 20);
        String day = start.atZone(TBILISI).toLocalDate().toString();
        assertThat(ok(call(post("/api/app/metrics/recalculate")))).isNotEmpty();
        stub.enqueue(
                calls(tool("get_metrics", "{}")),
                calls(tool("find_free_slots", "{\"masterRef\":\"" + MASTER + "\",\"serviceRef\":\"" + SERVICE
                        + "\",\"dateFrom\":\"" + day + "\",\"dateTo\":\"" + day + "\"}")),
                calls(bookDraft(client, start)),
                text("Proposed a visit for " + CLIENT + " with " + MASTER));

        JsonNode turn = chat(null, "Book " + client.name() + " for a cut");

        List<LlmRequest> requests = stub.recordedRequests();
        assertThat(requests).hasSize(4);
        String systemPrompt = ((LlmMessage.System) requests.get(0).messages().get(0)).content();
        assertThat(systemPrompt).contains(SERVICE + ": Cut").contains("Masters: " + MASTER);
        JsonNode sent = toolResult(requests, 1).path("metrics");
        JsonNode served = ok(call(get("/api/app/metrics")));
        assertThat(sent).hasSameSizeAs(served);
        assertThat(sent.size()).isPositive();
        for (JsonNode metric : sent) {
            JsonNode expected = byKey(served, metric.path("key").asText());
            assertThat(metric.path("sampleSize").asInt()).as(metric.path("key").asText()).isEqualTo(expected.path("sampleSize").asInt());
            assertThat(metric.path("insufficientData").asBoolean()).isEqualTo(expected.path("insufficientData").asBoolean());
            assertThat(metric.path("unit").asText()).isEqualTo(expected.path("unit").asText());
            assertThat(metric.path("value").isNumber()).isEqualTo(expected.path("value").isNumber());
            if (expected.path("value").isNumber()) {
                assertThat(metric.path("value").asDouble()).isCloseTo(expected.path("value").asDouble(), within(1e-9));
            }
        }
        assertThat(toolResult(requests, 2).path("slots").toString()).contains(LOCAL.format(start.atZone(TBILISI)));

        assertThat(turn.path("reply").asText()).isEqualTo("Proposed a visit for " + client.name() + " with " + studio.masterName());
        assertThat(turn.path("drafts")).hasSize(1);
        JsonNode draft = turn.path("drafts").get(0);
        assertThat(draft.path("status").asText()).isEqualTo("PENDING");
        assertThat(draft.path("type").asText()).isEqualTo("draft_booking");
        assertThat(draft.path("summary").path("clientName").asText()).isEqualTo(client.name());
        assertThat(calendar()).as("nothing is booked before the confirmation").isEmpty();

        UUID draftId = UUID.fromString(draft.path("id").asText());
        JsonNode confirmed = ok(call(post("/api/app/ai/drafts/" + draftId + "/confirm")));

        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        UUID visitId = UUID.fromString(confirmed.path("appliedEntityId").asText());
        List<JsonNode> visits = calendar();
        assertThat(visits).hasSize(1);
        assertThat(visits.get(0).path("id").asText()).isEqualTo(visitId.toString());
        assertThat(visits.get(0).path("source").asText()).isEqualTo("AI");
        assertThat(visits.get(0).path("clientId").asText()).isEqualTo(client.id().toString());
        assertThat(startOf(visits.get(0))).isEqualTo(start);
        assertThat(column("select action from audit_log where tenant_id = ? and entity_id = ?", studio.tenant(), draftId))
                .containsExactlyInAnyOrder("ai.draft.create", "ai.draft.confirm");
        assertThat(column("select action from audit_log where tenant_id = ? and entity_id = ?", studio.tenant(), visitId))
                .containsExactly("appointment.create");
        assertThat(one("select applied_entity_id from ai_draft where id = ?", draftId)).isEqualTo(visitId.toString());

        assertThat(error(call(post("/api/app/ai/drafts/" + draftId + "/confirm")), 409)).isEqualTo("DRAFT_NOT_PENDING");
        assertThat(calendar()).hasSize(1);
        assertThat(column("select action from audit_log where tenant_id = ? and entity_id = ?", studio.tenant(), draftId))
                .containsExactlyInAnyOrder("ai.draft.create", "ai.draft.confirm");
    }

    /** (b) a draft past its {@code expires_at} cannot be confirmed and is persisted as EXPIRED. */
    @Test
    void expiredDraftIsRefusedPersistedAsExpiredAndBooksNothing() throws Exception {
        Person client = client();
        JsonNode turn = draftTurn(client, slot(3, 20));
        UUID draftId = draftId(turn, 0);
        assertThat(update("update ai_draft set expires_at = created_at - interval '1 hour' where id = ?", draftId)).isEqualTo(1);

        assertThat(error(call(post("/api/app/ai/drafts/" + draftId + "/confirm")), 409)).isEqualTo("DRAFT_EXPIRED");

        assertThat(one("select status from ai_draft where id = ?", draftId)).isEqualTo("EXPIRED");
        assertThat(calendar()).isEmpty();
        assertThat(error(call(post("/api/app/ai/drafts/" + draftId + "/confirm")), 409)).isEqualTo("DRAFT_NOT_PENDING");
    }

    /** (c) the visit moved after the draft was made: DRAFT_STALE, FAILED, the visit stays where the owner put it. */
    @Test
    void staleRescheduleDraftFailsAndLeavesTheMovedVisit() throws Exception {
        Person client = client();
        AppointmentView visit = visit(client, slot(3, 20));
        Instant proposed = slot(4, 20);
        Instant movedByOwner = slot(5, 20);
        JsonNode turn = visitTurn(client, visit.serviceStart(), rescheduleDraft(proposed));
        UUID draftId = draftId(turn, 0);
        assertThat(turn.path("drafts").get(0).path("type").asText()).isEqualTo("draft_reschedule");
        assertThat(ok(call(post("/api/app/appointments/" + visit.id() + "/reschedule").param("start", movedByOwner.toString())))
                .path("id").asText()).isEqualTo(visit.id().toString());

        assertThat(error(call(post("/api/app/ai/drafts/" + draftId + "/confirm")), 409)).isEqualTo("DRAFT_STALE");

        assertThat(one("select status from ai_draft where id = ?", draftId)).isEqualTo("FAILED");
        assertThat(one("select failure_code from ai_draft where id = ?", draftId)).isEqualTo("DRAFT_STALE");
        JsonNode stored = visitById(visit.id());
        assertThat(startOf(stored)).isEqualTo(movedByOwner);
        assertThat(stored.path("status").asText()).isEqualTo(visit.status().name());
    }

    /** (c) a reschedule draft that is still current moves the visit through the engine. */
    @Test
    void rescheduleDraftConfirmMovesTheVisit() throws Exception {
        Person client = client();
        AppointmentView visit = visit(client, slot(3, 20));
        Instant proposed = slot(4, 20);
        JsonNode turn = visitTurn(client, visit.serviceStart(), rescheduleDraft(proposed));
        UUID draftId = draftId(turn, 0);
        assertThat(turn.path("drafts").get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(startOf(visitById(visit.id()))).as("not moved before the confirmation").isEqualTo(visit.serviceStart());

        JsonNode confirmed = ok(call(post("/api/app/ai/drafts/" + draftId + "/confirm")));

        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("appliedEntityId").asText()).isEqualTo(visit.id().toString());
        assertThat(startOf(visitById(visit.id()))).isEqualTo(proposed);
        assertThat(column("select action from audit_log where tenant_id = ? and entity_id = ?", studio.tenant(), draftId))
                .containsExactlyInAnyOrder("ai.draft.create", "ai.draft.confirm");
    }

    /** A master deactivated after the draft was written: the confirmation is refused by code and the visit stays. */
    @Test
    void aRescheduleDraftOfADeactivatedMasterFailsByCodeAndTheVisitStays() throws Exception {
        Person client = client();
        AppointmentView visit = visit(client, slot(3, 20));
        JsonNode turn = visitTurn(client, visit.serviceStart(), rescheduleDraft(slot(4, 20)));
        UUID draftId = draftId(turn, 0);
        as(studio.tenant(), ActorContext.system(), () -> catalog.upsertSpecialist(
                new SpecialistView(studio.master(), studio.masterName(), null, "#0f766e", false)));

        assertThat(error(call(post("/api/app/ai/drafts/" + draftId + "/confirm")), 409)).isEqualTo("SERVICE_NOT_OFFERED");

        assertThat(one("select failure_code from ai_draft where id = ?", draftId)).isEqualTo("SERVICE_NOT_OFFERED");
        assertThat(startOf(visitById(visit.id()))).isEqualTo(visit.serviceStart());
    }

    /** (c) a cancel draft that is still current cancels the visit through the engine. */
    @Test
    void cancelDraftConfirmCancelsTheVisit() throws Exception {
        Person client = client();
        AppointmentView visit = visit(client, slot(3, 20));
        JsonNode turn = visitTurn(client, visit.serviceStart(),
                tool("draft_cancel", "{\"visitRef\":\"" + VISIT + "\",\"initiatedBy\":\"client\",\"reason\":\"Feels unwell\"}"));
        UUID draftId = draftId(turn, 0);
        assertThat(turn.path("drafts").get(0).path("type").asText()).isEqualTo("draft_cancel");
        assertThat(turn.path("drafts").get(0).path("summary").path("reason").asText()).isEqualTo("Feels unwell");
        assertThat(visitById(visit.id()).path("status").asText()).isEqualTo(visit.status().name());

        JsonNode confirmed = ok(call(post("/api/app/ai/drafts/" + draftId + "/confirm")));

        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        JsonNode cancelled = visitById(visit.id());
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED_BY_CLIENT");
        assertThat(cancelled.path("note").asText()).as("the reason stays off the note masters see").doesNotContain("Feels unwell");
        assertThat(column("select action from audit_log where tenant_id = ? and entity_id = ?", studio.tenant(), draftId))
                .containsExactlyInAnyOrder("ai.draft.create", "ai.draft.confirm");
        assertThat(column("select action from audit_log where tenant_id = ? and entity_id = ?", studio.tenant(), visit.id()))
                .containsExactlyInAnyOrder("appointment.create", "appointment.transition");
    }

    /** (d) the slot is taken between the draft and its confirmation: the engine refuses, the rollback leaves one visit. */
    @Test
    void slotTakenAfterTheDraftFailsTheDraftWithSlotOccupiedAndKeepsOneVisit() throws Exception {
        Person client = client();
        Instant start = slot(3, 20);
        JsonNode turn = draftTurn(client, start);
        UUID draftId = draftId(turn, 0);
        String rival = phone();
        MvcResult taken = call(post("/api/app/appointments").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(
                Map.of("specialistId", studio.master(), "serviceId", studio.service(), "serviceStart", start.toString(),
                        "phone", rival, "displayName", "Rival Customer", "locale", "en", "marketingConsent", false, "source", "CONSOLE",
                        "skipNoticeAndHorizon", false))));
        assertThat(ok(taken).path("id").asText()).isNotBlank();

        assertThat(error(call(post("/api/app/ai/drafts/" + draftId + "/confirm")), 409)).isEqualTo("SLOT_OCCUPIED");

        assertThat(one("select status from ai_draft where id = ?", draftId)).isEqualTo("FAILED");
        assertThat(one("select failure_code from ai_draft where id = ?", draftId)).isEqualTo("SLOT_OCCUPIED");
        assertThat(one("select applied_entity_id from ai_draft where id = ?", draftId)).isNull();
        List<JsonNode> visits = calendar();
        assertThat(visits).hasSize(1);
        assertThat(startOf(visits.get(0))).isEqualTo(start);
        assertThat(visits.get(0).path("source").asText()).isEqualTo("CONSOLE");
        assertThat(column("select action from audit_log where tenant_id = ? and entity_id = ?", studio.tenant(), draftId))
                .containsExactlyInAnyOrder("ai.draft.create", "ai.draft.fail");
        assertThat(column("select count(*)::text from audit_log where tenant_id = ? and action = 'appointment.create'",
                studio.tenant())).containsExactly("1");
    }

    /** (e) the budget is used up after a turn: the next chat is 429, the calendar and an open draft still work. */
    @Test
    void budgetUsedUpRefusesChatButCalendarAndConfirmStillWork() throws Exception {
        setBudget(studio.tenant(), 1000);
        Person client = client();
        Instant start = slot(3, 20);
        stub.enqueue(
                calls(new Usage(600, 100), bookDraft(client, start)),
                text("Drafted " + CLIENT, new Usage(600, 100)));

        JsonNode turn = chat(null, "Book " + client.name());

        assertThat(turn.path("usage").path("monthTokensUsed").asLong()).isEqualTo(1400);
        assertThat(turn.path("usage").path("monthTokenBudget").asLong()).isEqualTo(1000);
        assertThat(stub.recordedRequests()).hasSize(2);
        for (int i = 0; i < 2; i++) {
            assertThat(error(chatRaw(studio.tenant(), null, "Anything else?"), 429)).isEqualTo("AI_BUDGET_EXCEEDED");
        }
        assertThat(stub.recordedRequests()).as("no model call once the budget is gone").hasSize(2);
        assertThat(column("select count(*)::text from audit_log where tenant_id = ? and action = 'ai.budget.exceeded'",
                studio.tenant())).containsExactly("1");
        assertThat(ok(call(get("/api/app/ai/settings"))).path("monthlyTokenBudget").asLong()).isEqualTo(1000);
        assertThat(calendar()).isEmpty();

        JsonNode confirmed = ok(call(post("/api/app/ai/drafts/" + draftId(turn, 0) + "/confirm")));

        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(calendar()).hasSize(1);
    }

    /** (e) the budget runs out inside the loop: 429, the draft already created stays PENDING and can be confirmed. */
    @Test
    void budgetRunningOutInsideTheLoopKeepsTheDraftsAlreadyCreated() throws Exception {
        setBudget(studio.tenant(), 1000);
        Person client = client();
        Instant start = slot(3, 20);
        stub.enqueue(
                calls(new Usage(900, 200), bookDraft(client, start)),
                text("never reached"));

        assertThat(error(chatRaw(studio.tenant(), null, "Book " + client.name()), 429)).isEqualTo("AI_BUDGET_EXCEEDED");

        assertThat(stub.recordedRequests()).hasSize(1);
        List<String> drafts = column("select id::text from ai_draft where tenant_id = ?", studio.tenant());
        assertThat(drafts).hasSize(1);
        assertThat(one("select status from ai_draft where id = ?::uuid", drafts.get(0))).isEqualTo("PENDING");
        assertThat(column("select count(*)::text from audit_log where tenant_id = ? and action = 'ai.budget.exceeded'",
                studio.tenant())).containsExactly("1");
        JsonNode pending = ok(call(get("/api/app/ai/drafts").param("status", "PENDING")));
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).path("id").asText()).isEqualTo(drafts.get(0));

        assertThat(ok(call(post("/api/app/ai/drafts/" + drafts.get(0) + "/confirm"))).path("status").asText())
                .isEqualTo("CONFIRMED");
    }

    /** (f) no name or phone of a client or a master in any stored log row or request; names come back for the owner. */
    @Test
    void noNameOrPhoneReachesLogsMessagesOrTheModelAndTheOwnerGetsNamesBack() throws Exception {
        Person client = client();
        AppointmentView existing = visit(client, slot(2, 20));
        Instant start = slot(3, 20);
        String day = existing.serviceStart().atZone(TBILISI).toLocalDate().toString();
        stub.enqueue(
                calls(tool("get_schedule", "{\"dateFrom\":\"" + day + "\",\"dateTo\":\"" + day + "\"}")),
                calls(tool("get_client_profile", "{\"clientRef\":\"" + CLIENT + "\"}")),
                calls(bookDraft(client, start)),
                text("Proposed " + CLIENT + " with " + MASTER + ", see " + VISIT + " and DRAFT_1"));

        JsonNode turn = chat(null, "Book " + client.name() + " with " + studio.masterName() + ", phone " + spaced(client.phone())
                + " or " + client.phone().substring(4) + ", for a cut");
        UUID conversation = UUID.fromString(turn.path("conversationId").asText());

        assertThat(turn.path("drafts")).hasSize(1);
        assertThat(turn.path("reply").asText()).contains(client.name()).contains(studio.masterName());
        List<String> forbidden = List.of(client.name(), words(client.name()).get(1), words(client.name()).get(0),
                studio.masterName(), words(studio.masterName()).get(1), words(studio.masterName()).get(0));
        List<String> stored = new ArrayList<>();
        stored.addAll(column("select prompt from ai_prompt_log where conversation_id = ?", conversation));
        stored.addAll(column("select response from ai_prompt_log where conversation_id = ?", conversation));
        stored.addAll(column("select arguments from ai_tool_call where conversation_id = ?", conversation));
        stored.addAll(column("select result from ai_tool_call where conversation_id = ?", conversation));
        stored.addAll(column("select content from ai_message where conversation_id = ?", conversation));
        assertThat(column("select prompt from ai_prompt_log where conversation_id = ?", conversation)).hasSize(4);
        assertThat(column("select arguments from ai_tool_call where conversation_id = ?", conversation)).hasSize(3);
        assertThat(column("select content from ai_message where conversation_id = ?", conversation)).hasSize(2);
        List<String> sent = new ArrayList<>();
        for (LlmRequest request : stub.recordedRequests()) {
            for (LlmMessage message : request.messages()) {
                switch (message) {
                    case LlmMessage.System m -> sent.add(m.content());
                    case LlmMessage.User m -> sent.add(m.content());
                    case LlmMessage.Assistant m -> {
                        sent.add(m.content());
                        m.toolCalls().forEach(c -> sent.add(c.argumentsJson()));
                    }
                    case LlmMessage.ToolResult m -> sent.add(m.contentJson());
                }
            }
        }
        assertThat(sent).isNotEmpty();
        for (String row : stored) {
            assertNoPersonalData("stored row", row, forbidden, client.phone());
        }
        for (String text : sent) {
            assertNoPersonalData("sent to the model", text, forbidden, client.phone());
        }

        JsonNode history = ok(call(get("/api/app/ai/conversations/" + conversation)));
        assertThat(history.path("messages")).hasSize(2);
        for (JsonNode message : history.path("messages")) {
            assertThat(message.path("content").asText()).contains(client.name()).contains(studio.masterName());
        }
        assertThat(history.path("drafts")).hasSize(1);

        UUID draftId = draftId(turn, 0);
        UUID other = tenantOnly();
        assertThat(error(callAs(other, get("/api/app/ai/conversations/" + conversation)), 404)).isEqualTo("CONVERSATION_NOT_FOUND");
        assertThat(ok(callAs(other, get("/api/app/ai/drafts")))).isEmpty();
        assertThat(error(callAs(other, post("/api/app/ai/drafts/" + draftId + "/confirm")), 404)).isEqualTo("DRAFT_NOT_FOUND");
        assertThat(error(callAs(other, post("/api/app/ai/drafts/" + draftId + "/reject")), 404)).isEqualTo("DRAFT_NOT_FOUND");
        assertThat(one("select status from ai_draft where id = ?", draftId)).isEqualTo("PENDING");
    }

    /** (g) client notes stay out of the prompt by default; with the toggle they go masked. */
    @Test
    void clientNotesAreWithheldByDefaultAndMaskedWhenTheOwnerAllowsThem() throws Exception {
        Person client = client();
        asOwner(() -> crm.updateNotes(client.id(), "аллергия на краску, тел. +995 555 12 34 56", null));
        String profile = "{\"clientRef\":\"" + CLIENT + "\"}";
        stub.enqueue(calls(tool("get_client_profile", profile)), text("Profile of " + CLIENT));

        JsonNode first = chat(null, "Show the profile of " + client.name());
        UUID conversation = UUID.fromString(first.path("conversationId").asText());

        String withheld = toolResultJson(stub.recordedRequests(), 1);
        assertThat(JSON.readTree(withheld).path("notes").asText()).isEqualTo(WITHHELD);
        assertThat(withheld).doesNotContain("аллерг").doesNotContain("краск").doesNotContain("555");
        assertThat(ok(call(get("/api/app/ai/settings"))).path("notesToLlm").asBoolean()).isFalse();

        MvcResult toggled = call(put("/api/app/ai/settings").contentType(MediaType.APPLICATION_JSON).content("{\"notesToLlm\":true}"));
        assertThat(ok(toggled).path("notesToLlm").asBoolean()).isTrue();
        stub.reset();
        stub.enqueue(calls(tool("get_client_profile", profile)), text("Profile of " + CLIENT));

        chat(conversation, "Show the profile of " + client.name() + " again");

        String masked = toolResultJson(stub.recordedRequests(), 1);
        assertThat(JSON.readTree(masked).path("notes").asText()).contains("[sensitive]");
        assertThat(masked).doesNotContain("аллерг").doesNotContain("краск").doesNotContain("555").doesNotContain(WITHHELD);
        assertThat(column("select count(*)::text from audit_log where tenant_id = ? and action = 'ai.settings.update'",
                studio.tenant())).containsExactly("1");
    }

    /** (h) an over-long message is refused before anything is written. */
    @Test
    void overLongMessageIsRefusedAndNothingIsStored() throws Exception {
        assertThat(error(chatRaw(studio.tenant(), null, "a".repeat(4001)), 400)).isEqualTo("AI_MESSAGE_TOO_LONG");
        assertThat(error(chatRaw(studio.tenant(), null, "   "), 400)).isEqualTo("AI_MESSAGE_EMPTY");

        assertThat(column("select count(*)::text from ai_message where tenant_id = ?", studio.tenant())).containsExactly("0");
        assertThat(column("select count(*)::text from ai_conversation where tenant_id = ?", studio.tenant())).containsExactly("0");
        assertThat(stub.recordedRequests()).isEmpty();
    }

    /** (i) two parallel confirms of one draft: one visit, one 200, one 409. */
    @Test
    void parallelDoubleConfirmBooksExactlyOnce() throws Exception {
        Person client = client();
        List<Instant> starts = List.of(slot(3, 20), slot(3, 30), slot(3, 40));
        JsonNode turn = draftTurn(client, starts.toArray(new Instant[0]));
        assertThat(turn.path("drafts")).hasSize(3);

        for (int round = 0; round < starts.size(); round++) {
            UUID draftId = draftId(turn, round);
            List<MvcResult> results = race(
                    () -> call(post("/api/app/ai/drafts/" + draftId + "/confirm")),
                    () -> call(post("/api/app/ai/drafts/" + draftId + "/confirm")));

            List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
            assertThat(statuses).as("round %d", round).containsExactly(200, 409);
            MvcResult loser = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
            assertThat(error(loser, 409)).isEqualTo("DRAFT_NOT_PENDING");
            assertThat(one("select status from ai_draft where id = ?", draftId)).isEqualTo("CONFIRMED");
            Instant start = starts.get(round);
            assertThat(calendar().stream().filter(v -> startOf(v).equals(start))).hasSize(1);
            assertThat(column("select action from audit_log where tenant_id = ? and entity_id = ?", studio.tenant(), draftId))
                    .containsExactlyInAnyOrder("ai.draft.create", "ai.draft.confirm");
            assertThat(column("select count(*)::text from audit_log where tenant_id = ? and entity_id = ?::uuid and action = 'appointment.create'",
                    studio.tenant(), one("select applied_entity_id from ai_draft where id = ?", draftId))).containsExactly("1");
        }
    }

    /** (i) confirm against reject in parallel: exactly one wins and the status, the visit and the audit agree. */
    @Test
    void parallelConfirmAndRejectHaveOneWinnerWithConsistentState() throws Exception {
        Person client = client();
        List<Instant> starts = List.of(slot(3, 20), slot(3, 30), slot(3, 40));
        JsonNode turn = draftTurn(client, starts.toArray(new Instant[0]));

        for (int round = 0; round < starts.size(); round++) {
            UUID draftId = draftId(turn, round);
            List<MvcResult> results = race(
                    () -> call(post("/api/app/ai/drafts/" + draftId + "/confirm")),
                    () -> call(post("/api/app/ai/drafts/" + draftId + "/reject")));

            MvcResult confirm = results.get(0);
            MvcResult reject = results.get(1);
            assertThat(confirm.getResponse().getStatus() + reject.getResponse().getStatus())
                    .as("round %d: one 200 and one 409", round).isEqualTo(609);
            Instant start = starts.get(round);
            long visits = calendar().stream().filter(v -> startOf(v).equals(start)).count();
            List<String> actions = column("select action from audit_log where tenant_id = ? and entity_id = ?", studio.tenant(), draftId);
            if (confirm.getResponse().getStatus() == 200) {
                assertThat(error(reject, 409)).isEqualTo("DRAFT_NOT_PENDING");
                assertThat(one("select status from ai_draft where id = ?", draftId)).isEqualTo("CONFIRMED");
                assertThat(visits).isEqualTo(1);
                assertThat(actions).containsExactlyInAnyOrder("ai.draft.create", "ai.draft.confirm");
            } else {
                assertThat(error(confirm, 409)).isEqualTo("DRAFT_NOT_PENDING");
                assertThat(one("select status from ai_draft where id = ?", draftId)).isEqualTo("REJECTED");
                assertThat(visits).isZero();
                assertThat(actions).containsExactlyInAnyOrder("ai.draft.create", "ai.draft.reject");
            }
        }
    }

    /** (j) names are restored from the stored map alone: the stub and every in-memory state are gone. */
    @Test
    void namesAreRestoredFromTheStoredMappingAfterTheStubIsReset() throws Exception {
        Person client = client();
        stub.enqueue(text(CLIENT + " is booked with " + MASTER));
        JsonNode turn = chat(null, "About " + client.name() + " and " + studio.masterName());
        UUID conversation = UUID.fromString(turn.path("conversationId").asText());
        stub.reset();

        String restored;
        try (TenantContext.Scope ignored = TenantContext.open(studio.tenant())) {
            restored = new Restorer(new PseudonymRegistry(pseudonyms)).restore(conversation, CLIENT + " / " + MASTER);
        }

        assertThat(restored).isEqualTo(client.name() + " / " + studio.masterName());
        assertThat(stub.recordedRequests()).isEmpty();
        JsonNode history = ok(call(get("/api/app/ai/conversations/" + conversation)));
        assertThat(history.path("messages").get(0).path("content").asText()).contains(client.name()).contains(studio.masterName());
        assertThat(history.path("messages").get(1).path("content").asText()).isEqualTo(client.name() + " is booked with " + studio.masterName());
        assertThat(stub.recordedRequests()).isEmpty();
    }

    /** Stage criterion 1 on a tenant with real numbers: every metric the model got equals {@code /api/app/metrics}. */
    @Test
    void metricsOfASeededTenantReachTheModelExactly() throws Exception {
        stub.enqueue(calls(tool("get_metrics", "{}")), text("Here are the numbers"));

        JsonNode turn = ok(chatRaw(DemoIds.LUMEN, null, "How is the business?"));

        JsonNode sent = toolResult(stub.recordedRequests(), 1).path("metrics");
        JsonNode served = ok(callAs(DemoIds.LUMEN, get("/api/app/metrics")));
        UUID conversation = UUID.fromString(turn.path("conversationId").asText());
        List<AiPseudonymStore.Mapping> refs = as(DemoIds.LUMEN, ActorContext.system(), () -> pseudonyms.all(conversation));
        assertThat(sent).hasSameSizeAs(served);
        int withNumbers = 0;
        int withBreakdown = 0;
        for (JsonNode metric : sent) {
            String key = metric.path("key").asText();
            JsonNode expected = byKey(served, key);
            assertThat(metric.path("insufficientData").asBoolean()).as(key).isEqualTo(expected.path("insufficientData").asBoolean());
            assertThat(metric.path("sampleSize").asInt()).as(key).isEqualTo(expected.path("sampleSize").asInt());
            assertThat(metric.path("windowStart").asText()).as(key).isEqualTo(expected.path("windowStart").asText());
            assertThat(metric.path("windowEnd").asText()).as(key).isEqualTo(expected.path("windowEnd").asText());
            assertThat(metric.path("value").isNumber()).as(key).isEqualTo(expected.path("value").isNumber());
            if (expected.path("value").isNumber()) {
                withNumbers++;
                assertThat(metric.path("value").asDouble()).as(key).isEqualTo(expected.path("value").asDouble());
            }
            for (JsonNode row : metric.path("breakdown")) {
                String master = refs.stream().filter(m -> m.token().equals(row.path("masterRef").asText())).findFirst().orElseThrow()
                        .entityId().toString();
                JsonNode servedRow = null;
                for (JsonNode candidate : expected.path("breakdownBySpecialist")) {
                    if (master.equals(candidate.path("specialistId").asText())) {
                        servedRow = candidate;
                    }
                }
                assertThat(servedRow).as(key + " " + master).isNotNull();
                assertThat(row.path("value").isNumber()).isEqualTo(servedRow.path("value").isNumber());
                if (servedRow.path("value").isNumber()) {
                    withBreakdown++;
                    assertThat(row.path("value").asDouble()).isEqualTo(servedRow.path("value").asDouble());
                }
            }
        }
        assertThat(withNumbers).as("the seeded tenant has real numbers, not empty against empty").isPositive();
        assertThat(withBreakdown).isPositive();
    }

    /** The engine wrote the visit, then the draft's own transition fails: everything rolls back and the draft stays open. */
    @Test
    void aFailureAfterTheEngineWroteRollsTheVisitBackAndLeavesTheDraftPending() throws Exception {
        Person client = client();
        UUID draftId = draftId(draftTurn(client, slot(3, 20)), 0);
        update("create function it_refuse_confirm() returns trigger language plpgsql as "
                + "$$ begin raise exception 'refused by the test'; end $$");
        update("create trigger it_refuse_confirm before update on ai_draft for each row when (new.id = '" + draftId
                + "' and new.status = 'CONFIRMED') execute function it_refuse_confirm()");
        try {
            assertThatThrownBy(() -> asOwner(() -> assistant.confirmDraft(draftId)))
                    .isNotInstanceOf(DomainException.class);
        } finally {
            update("drop trigger it_refuse_confirm on ai_draft");
            update("drop function it_refuse_confirm()");
        }

        assertThat(one("select status from ai_draft where id = ?", draftId)).isEqualTo("PENDING");
        assertThat(calendar()).as("the visit the engine wrote is gone").isEmpty();
        assertThat(column("select count(*)::text from audit_log where tenant_id = ? and action = 'appointment.create'",
                studio.tenant())).containsExactly("0");
        assertThat(ok(call(post("/api/app/ai/drafts/" + draftId + "/confirm"))).path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(calendar()).hasSize(1);
    }

    /**
     * Two drafts of one slot confirmed at once: whichever way the engine refuses (its check or the exclusion constraint under
     * the race), the loser is 409 {@code SLOT_OCCUPIED} and FAILED, and one visit stands.
     */
    @Test
    void parallelConfirmsOfOneSlotBookOnceAndFailTheOtherWithSlotOccupied() throws Exception {
        Person client = client();
        for (int round = 0; round < 3; round++) {
            Instant start = slot(3, 20 + round * 10);
            JsonNode turn = draftTurn(client, start, start);
            UUID one = draftId(turn, 0);
            UUID other = draftId(turn, 1);

            List<MvcResult> results = race(
                    () -> call(post("/api/app/ai/drafts/" + one + "/confirm")),
                    () -> call(post("/api/app/ai/drafts/" + other + "/confirm")));

            assertThat(results.stream().map(r -> r.getResponse().getStatus()).sorted().toList())
                    .as("round %d", round).containsExactly(200, 409);
            boolean firstLost = results.get(0).getResponse().getStatus() == 409;
            assertThat(error(results.get(firstLost ? 0 : 1), 409)).isEqualTo("SLOT_OCCUPIED");
            UUID loser = firstLost ? one : other;
            assertThat(one("select status from ai_draft where id = ?", loser)).isEqualTo("FAILED");
            assertThat(one("select failure_code from ai_draft where id = ?", loser)).isEqualTo("SLOT_OCCUPIED");
            assertThat(calendar().stream().filter(v -> startOf(v).equals(start))).hasSize(1);
        }
    }

    /** Inflected names are recognised: the model never sees them and gets the client's ref. */
    @Test
    void anInflectedNameNeverReachesTheModel() throws Exception {
        Person client = client("Анна Петрова");
        stub.enqueue(text("OK " + CLIENT));

        JsonNode turn = chat(null, "Перенеси Анну Петрову");

        assertThat(turn.path("reply").asText()).isEqualTo("OK " + client.name());
        String last = ((LlmMessage.User) stub.recordedRequests().get(0).messages().getLast()).content();
        assertThat(last).isEqualTo("Перенеси " + CLIENT);
        assertThat(stub.recordedRequests().toString()).doesNotContain("Анну").doesNotContain("Петрову");
        UUID conversation = UUID.fromString(turn.path("conversationId").asText());
        assertThat(column("select content from ai_message where conversation_id = ?", conversation))
                .noneMatch(text -> text.contains("Анну") || text.contains("Петрову"));
        assertThat(column("select prompt from ai_prompt_log where conversation_id = ?", conversation))
                .noneMatch(text -> text.contains("Анну") || text.contains("Петрову"));
    }

    /** A three-letter name and an apostrophe in a word: recognised when capitalised, inflected, in a full name or alone. */
    @Test
    void aShortOrApostrophisedInflectedNameNeverReachesTheModel() throws Exception {
        client("Оля Петрова");
        client("Мар'яна Коваль");
        stub.enqueue(text("OK " + CLIENT));

        JsonNode turn = chat(null, "Перенеси Олю Петрову");

        assertThat(((LlmMessage.User) stub.recordedRequests().get(0).messages().getLast()).content()).isEqualTo("Перенеси " + CLIENT);
        assertThat(turn.path("reply").asText()).isEqualTo("OK Оля Петрова");
        stub.reset();
        stub.enqueue(text("OK"));

        chat(UUID.fromString(turn.path("conversationId").asText()), "Спроси Мар'яну");

        String sent = ((LlmMessage.User) stub.recordedRequests().get(0).messages().getLast()).content();
        assertThat(sent).isEqualTo("Спроси CLIENT_2").doesNotContain("яну");
    }

    /** The typed ending begins with the stored name's last letter: the common prefix is longer than the stem. */
    @Test
    void anEndingBeginningWithTheNamesLastLetterNeverReachesTheModel() throws Exception {
        client("Тарас Шевченко");
        client("Олег Стоцкий");
        stub.enqueue(text("OK"));

        chat(null, "Перенеси Шевченком и Стоцким");

        String sent = ((LlmMessage.User) stub.recordedRequests().get(0).messages().getLast()).content();
        assertThat(sent).isEqualTo("Перенеси CLIENT_1 и CLIENT_2");
    }

    /** n1: a reason naming another client keeps its ref, so that client's retirement clears it from the card and the store. */
    @Test
    void aReasonNamingAnotherClientLosesTheNameWhenThatClientRetires() throws Exception {
        Person kept = client();
        Person named = client();
        AppointmentView visit = visit(kept, slot(2, 20));
        String day = visit.serviceStart().atZone(TBILISI).toLocalDate().toString();
        stub.enqueue(
                calls(tool("get_schedule", "{\"dateFrom\":\"" + day + "\",\"dateTo\":\"" + day + "\"}")),
                calls(tool("draft_cancel", "{\"visitRef\":\"" + VISIT + "\",\"initiatedBy\":\"business\",\"reason\":\"Given to CLIENT_1\"}")),
                text("ok"));
        JsonNode turn = chat(null, "Give the slot to " + named.name());
        UUID draft = draftId(turn, 0);
        assertThat(turn.path("drafts").get(0).path("summary").path("reason").asText()).isEqualTo("Given to " + named.name());
        assertThat(one("select payload from ai_draft where id = ?", draft)).contains("Given to CLIENT_1").doesNotContain(named.name());
        assertThat(column("select coalesce(after_state, '') from audit_log where entity_id = ?", draft))
                .allSatisfy(row -> assertThat(row).doesNotContain("Given to"));

        Person survivor = client();
        asOwner(() -> merge.merge(survivor.id(), named.id()));

        JsonNode card = ok(call(get("/api/app/ai/drafts"))).get(0);
        assertThat(card.path("status").asText()).as("the draft of the other client stays open").isEqualTo("PENDING");
        assertThat(card.path("summary").path("clientName").asText()).isEqualTo(kept.name());
        assertThat(card.path("summary").path("reason").asText()).isEqualTo("Given to anonymized");
    }

    /** n2: retired behind the use cases, the refused draft still loses the name and the reason. */
    @Test
    void aDraftRefusedForARetiredClientLosesTheNameAndReason() throws Exception {
        Person client = client();
        AppointmentView visit = visit(client, slot(2, 20));
        JsonNode turn = visitTurn(client, visit.serviceStart(),
                tool("draft_cancel", "{\"visitRef\":\"" + VISIT + "\",\"initiatedBy\":\"client\",\"reason\":\"" + CLIENT + " asked\"}"));
        UUID draft = draftId(turn, 0);
        assertThat(update("update client set anonymized_at = now() where id = ?", client.id())).isEqualTo(1);

        assertThat(error(call(post("/api/app/ai/drafts/" + draft + "/confirm")), 409)).isEqualTo("DRAFT_STALE");

        assertThat(one("select payload from ai_draft where id = ?", draft)).doesNotContain(client.name()).doesNotContain("asked");
        assertThat(one("select status from ai_draft where id = ?", draft)).isEqualTo("FAILED");
    }

    /** n4: a listener failing mid merge rolls the merge back, all modules together. */
    @Test
    void aFailingRetirementListenerRollsTheMergeBack() throws Exception {
        Person absorbed = client();
        Person survivor = client();
        AppointmentView visit = visit(absorbed, slot(2, 20));
        UUID draft = draftId(draftTurn(absorbed, slot(3, 20)), 0);
        update("create function it_refuse_scrub() returns trigger language plpgsql as "
                + "$$ begin raise exception 'refused by the test'; end $$");
        update("create trigger it_refuse_scrub before update on ai_pseudonym for each row "
                + "execute function it_refuse_scrub()");
        try {
            assertThatThrownBy(() -> asOwner(() -> merge.merge(survivor.id(), absorbed.id())))
                    .isNotInstanceOf(DomainException.class);
        } finally {
            update("drop trigger it_refuse_scrub on ai_pseudonym");
            update("drop function it_refuse_scrub()");
        }

        assertThat(one("select display_name from client where id = ?", absorbed.id())).isEqualTo(absorbed.name());
        assertThat(one("select merged_into_client_id from client where id = ?", absorbed.id())).isNull();
        assertThat(one("select client_id from appointment where id = ?", visit.id())).isEqualTo(absorbed.id().toString());
        assertThat(one("select status from ai_draft where id = ?", draft)).isEqualTo("PENDING");
        assertThat(one("select payload from ai_draft where id = ?", draft)).contains(absorbed.name());
    }

    /** n4: confirm against merge of the draft's client: no deadlock and either order leaves a consistent state. */
    @Test
    void parallelConfirmAndMergeNeverDeadlockAndStayConsistent() throws Exception {
        for (int round = 0; round < 5; round++) {
            Person absorbed = client();
            Person survivor = client();
            Instant start = slot(3, 20 + round * 10);
            UUID draft = draftId(draftTurn(absorbed, start), 0);

            List<MvcResult> results = race(
                    () -> call(post("/api/app/ai/drafts/" + draft + "/confirm")),
                    () -> call(post("/api/app/clients/" + survivor.id() + "/merge").param("absorbedId", absorbed.id().toString())));

            MvcResult confirm = results.get(0);
            assertThat(results.get(1).getResponse().getStatus()).as("merge, round %d: %s", round,
                    results.get(1).getResponse().getContentAsString()).isEqualTo(200);
            assertThat(one("select merged_into_client_id from client where id = ?", absorbed.id())).isEqualTo(survivor.id().toString());
            assertThat(one("select display_name from client where id = ?", absorbed.id())).isEqualTo("anonymized");
            assertThat(one("select payload from ai_draft where id = ?", draft)).doesNotContain(absorbed.name());
            List<JsonNode> booked = calendar().stream().filter(v -> startOf(v).equals(start)).toList();
            if (confirm.getResponse().getStatus() == 200) {
                assertThat(one("select status from ai_draft where id = ?", draft)).isEqualTo("CONFIRMED");
                assertThat(booked).hasSize(1);
                assertThat(booked.get(0).path("clientId").asText()).as("the visit moved with the merge").isEqualTo(survivor.id().toString());
            } else {
                assertThat(error(confirm, 409)).isIn("DRAFT_NOT_PENDING", "DRAFT_STALE");
                assertThat(one("select status from ai_draft where id = ?", draft)).isEqualTo("FAILED");
                assertThat(one("select failure_code from ai_draft where id = ?", draft)).isEqualTo("DRAFT_STALE");
                assertThat(booked).isEmpty();
            }
        }
    }

    /** M1, M2: anonymization erases the client from AI data; a live ref then refuses, an open draft is stale. */
    @Test
    void anonymizingAClientErasesItFromTheConversationAndDrafts() throws Exception {
        Person client = client();
        AppointmentView existing = visit(client, slot(2, 20));
        Fixture fixture = retirementFixture(client, existing);

        asOwner(() -> crm.anonymize(client.id()));

        assertThat(callAs(studio.tenant(), get("/api/app/ai/conversations/" + fixture.conversation())).getResponse().getStatus()).isEqualTo(404);
        for (String table : List.of("ai_conversation", "ai_message", "ai_pseudonym", "ai_draft", "ai_prompt_log", "ai_tool_call")) {
            String column = "ai_conversation".equals(table) ? "id" : "conversation_id";
            assertThat(one("select count(*) from " + table + " where " + column + " = ?", fixture.conversation())).as(table).isEqualTo("0");
        }
        assertThat(column("select coalesce(before_state, '') || coalesce(after_state, '') from audit_log "
                        + "where tenant_id = ? and action like 'ai.draft.%'", studio.tenant()))
                .allSatisfy(row -> assertThat(row).doesNotContain(client.name()));
    }

    /** M1, M2: a merged-away client is treated like an anonymized one; the survivor and their drafts are untouched. */
    @Test
    void mergingAClientAwayErasesItFromTheConversationAndDrafts() throws Exception {
        Person absorbed = client();
        Person survivor = client();
        AppointmentView existing = visit(absorbed, slot(2, 20));
        JsonNode survivorTurn = draftTurn(survivor, slot(5, 20));
        UUID survivorDraft = draftId(survivorTurn, 0);
        Fixture fixture = retirementFixture(absorbed, existing);

        asOwner(() -> merge.merge(survivor.id(), absorbed.id()));

        // The visit moved to the survivor with the merge, so its ref stays usable.
        assertRetired(absorbed, fixture, false);
        assertThat(one("select status from ai_draft where id = ?", survivorDraft)).isEqualTo("PENDING");
        assertThat(ok(call(get("/api/app/ai/conversations/" + survivorTurn.path("conversationId").asText()))).toString())
                .contains(survivor.name());
        assertThat(ok(call(post("/api/app/ai/drafts/" + survivorDraft + "/confirm"))).path("status").asText()).isEqualTo("CONFIRMED");
    }

    /** M2: a client retired past the use cases (or between draft and confirm): confirm is DRAFT_STALE and FAILED, no visit. */
    @Test
    void confirmingADraftOfAClientRetiredBehindItsBackIsStale() throws Exception {
        Person anonymized = client();
        UUID first = draftId(draftTurn(anonymized, slot(3, 20)), 0);
        Person merged = client();
        Person survivor = client();
        stub.reset();
        UUID second = draftId(draftTurn(merged, slot(3, 30)), 0);
        assertThat(update("update client set anonymized_at = now() where id = ?", anonymized.id())).isEqualTo(1);
        assertThat(update("update client set merged_into_client_id = ? where id = ?", survivor.id(), merged.id())).isEqualTo(1);

        for (UUID draft : List.of(first, second)) {
            assertThat(error(call(post("/api/app/ai/drafts/" + draft + "/confirm")), 409)).isEqualTo("DRAFT_STALE");
            assertThat(one("select status from ai_draft where id = ?", draft)).isEqualTo("FAILED");
            assertThat(one("select failure_code from ai_draft where id = ?", draft)).isEqualTo("DRAFT_STALE");
        }
        assertThat(calendar()).isEmpty();
    }

    private record Fixture(UUID conversation, UUID pending, UUID confirmed) {
    }

    /** One conversation about the client with a pending cancel (with a reason) and a confirmed booking; the audit stays clean. */
    private Fixture retirementFixture(Person client, AppointmentView existing) throws Exception {
        String day = existing.serviceStart().atZone(TBILISI).toLocalDate().toString();
        Instant bookingStart = slot(3, 20);
        stub.enqueue(
                calls(tool("get_schedule", "{\"dateFrom\":\"" + day + "\",\"dateTo\":\"" + day + "\"}")),
                calls(tool("draft_cancel", "{\"visitRef\":\"" + VISIT + "\",\"initiatedBy\":\"client\",\"reason\":\"" + CLIENT + " asked\"}"),
                        bookDraft(client, bookingStart)),
                text("Proposed for " + CLIENT + ", see DRAFT_1"));
        JsonNode turn = chat(null, "Change the visit of " + client.name());
        UUID conversation = UUID.fromString(turn.path("conversationId").asText());
        assertThat(turn.path("drafts").get(0).path("summary").path("reason").asText()).isEqualTo(client.name() + " asked");
        UUID pending = draftId(turn, 0);
        UUID confirmed = draftId(turn, 1);
        assertThat(ok(call(post("/api/app/ai/drafts/" + confirmed + "/confirm"))).path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(column("select coalesce(before_state, '') || coalesce(after_state, '') from audit_log "
                        + "where tenant_id = ? and action like 'ai.draft.%'", studio.tenant()))
                .hasSizeGreaterThanOrEqualTo(3)
                .allSatisfy(row -> assertThat(row).doesNotContain(client.name()).doesNotContain(studio.masterName())
                        .doesNotContain("summary").doesNotContain("asked"));
        return new Fixture(conversation, pending, confirmed);
    }

    private void assertRetired(Person client, Fixture fixture, boolean visitRefused) throws Exception {
        String history = ok(call(get("/api/app/ai/conversations/" + fixture.conversation()))).toString();
        assertThat(history).doesNotContain(client.name()).doesNotContain(words(client.name()).get(0)).contains("anonymized")
                .doesNotContain(" asked");
        String cards = ok(call(get("/api/app/ai/drafts"))).toString();
        assertThat(cards).doesNotContain(client.name()).doesNotContain(" asked");
        assertThat(column("select display from ai_pseudonym where conversation_id = ?", fixture.conversation()))
                .allSatisfy(display -> assertThat(display).doesNotContain(client.name()));
        assertThat(column("select payload from ai_draft where conversation_id = ?", fixture.conversation()))
                .hasSize(2)
                .allSatisfy(payload -> assertThat(payload).doesNotContain(client.name()).doesNotContain(" asked"));
        assertThat(column("select coalesce(before_state, '') || coalesce(after_state, '') from audit_log "
                        + "where tenant_id = ? and action like 'ai.draft.%'", studio.tenant()))
                .allSatisfy(row -> assertThat(row).doesNotContain(client.name()));

        assertThat(one("select status from ai_draft where id = ?", fixture.pending())).isEqualTo("FAILED");
        assertThat(one("select failure_code from ai_draft where id = ?", fixture.pending())).isEqualTo("DRAFT_STALE");
        assertThat(one("select status from ai_draft where id = ?", fixture.confirmed())).isEqualTo("CONFIRMED");
        assertThat(error(call(post("/api/app/ai/drafts/" + fixture.pending() + "/confirm")), 409)).isEqualTo("DRAFT_NOT_PENDING");

        // The refs of this conversation still exist, and every tool refuses them.
        stub.reset();
        stub.enqueue(
                calls(tool("get_client_profile", "{\"clientRef\":\"" + CLIENT + "\"}"),
                        bookDraft(client, slot(6, 20)),
                        rescheduleDraft(slot(6, 40))),
                text("Nothing to do"));
        JsonNode after = chat(fixture.conversation(), "Try again");
        assertThat(after.path("drafts")).hasSize(visitRefused ? 0 : 1);
        List<LlmMessage> sent = stub.recordedRequests().get(1).messages();
        for (int fromEnd = 2; fromEnd <= 3; fromEnd++) {
            String result = ((LlmMessage.ToolResult) sent.get(sent.size() - fromEnd)).contentJson();
            assertThat(JSON.readTree(result).path("error").path("code").asText()).as(result).isEqualTo("UNKNOWN_REF");
        }
        String visitResult = ((LlmMessage.ToolResult) sent.getLast()).contentJson();
        assertThat(JSON.readTree(visitResult).path("error").path("code").asText()).isEqualTo(visitRefused ? "UNKNOWN_REF" : "");
    }

    /** The assistant is owner-only end to end. */
    @Test
    void masterTokenIsForbiddenOnChat() throws Exception {
        MvcResult result = mvc.perform(post("/api/app/ai/chat").with(master(studio.tenant(), studio.master()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}")).andReturn();

        assertThat(error(result, 403)).isEqualTo("FORBIDDEN_RESOURCE");
        assertThat(stub.recordedRequests()).isEmpty();
    }

    // ---------------------------------------------------------------- scripts

    private static LlmResponse calls(Usage usage, ToolCall... calls) {
        return new LlmResponse(new LlmMessage.Assistant(null, List.of(calls)), FinishReason.TOOL_CALLS, usage);
    }

    private static LlmResponse calls(ToolCall... calls) {
        return calls(new Usage(100, 10), calls);
    }

    private static LlmResponse text(String reply, Usage usage) {
        return new LlmResponse(new LlmMessage.Assistant(reply, List.of()), FinishReason.STOP, usage);
    }

    private static LlmResponse text(String reply) {
        return text(reply, new Usage(100, 10));
    }

    private static ToolCall tool(String name, String args) {
        return new ToolCall("call-" + CALL_IDS.incrementAndGet(), name, args);
    }

    private ToolCall bookDraft(Person client, Instant start) {
        return tool("draft_booking", "{\"clientRef\":\"" + CLIENT + "\",\"masterRef\":\"" + MASTER + "\",\"serviceRef\":\""
                + SERVICE + "\",\"start\":\"" + LOCAL.format(start.atZone(TBILISI)) + "\"}");
    }

    private ToolCall rescheduleDraft(Instant newStart) {
        return tool("draft_reschedule", "{\"visitRef\":\"" + VISIT + "\",\"newStart\":\"" + LOCAL.format(newStart.atZone(TBILISI)) + "\"}");
    }

    /** One turn that drafts a booking for each start, in one tool-call message; returns the chat response. */
    private JsonNode draftTurn(Person client, Instant... starts) throws Exception {
        ToolCall[] drafts = new ToolCall[starts.length];
        for (int i = 0; i < starts.length; i++) {
            drafts[i] = bookDraft(client, starts[i]);
        }
        stub.enqueue(calls(drafts), text("Proposed " + CLIENT));
        return chat(null, "Book " + client.name() + " for a cut");
    }

    /** One turn that reads the schedule of the visit's day (the visit gets {@code VISIT_1}) and then makes {@code draft}. */
    private JsonNode visitTurn(Person client, Instant visitStart, ToolCall draft) throws Exception {
        String day = visitStart.atZone(TBILISI).toLocalDate().toString();
        stub.enqueue(
                calls(tool("get_schedule", "{\"dateFrom\":\"" + day + "\",\"dateTo\":\"" + day + "\"}")),
                calls(draft),
                text("Proposed for " + CLIENT));
        return chat(null, "Change the visit of " + client.name());
    }

    private static UUID draftId(JsonNode turn, int index) {
        return UUID.fromString(turn.path("drafts").get(index).path("id").asText());
    }

    // ---------------------------------------------------------------- http

    private MvcResult callAs(UUID tenant, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(owner(tenant))).andReturn();
    }

    private MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return callAs(studio.tenant(), request);
    }

    private MvcResult chatRaw(UUID tenant, UUID conversation, String message) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        if (conversation != null) {
            body.put("conversationId", conversation.toString());
        }
        body.put("locale", "en");
        body.put("message", message);
        return callAs(tenant, post("/api/app/ai/chat").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private JsonNode chat(UUID conversation, String message) throws Exception {
        return ok(chatRaw(studio.tenant(), conversation, message));
    }

    /** The body of a 200 response. */
    private static JsonNode ok(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    /** The error {@code code} of a response with the expected status. */
    private static String error(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return JSON.readTree(result.getResponse().getContentAsByteArray()).path("code").asText();
    }

    private List<JsonNode> calendar() throws Exception {
        LocalDate today = LocalDate.now(TBILISI);
        JsonNode visits = ok(call(get("/api/app/calendar")
                .param("from", today.minusDays(2).atStartOfDay(TBILISI).toInstant().toString())
                .param("to", today.plusDays(40).atStartOfDay(TBILISI).toInstant().toString())));
        List<JsonNode> out = new ArrayList<>();
        visits.forEach(out::add);
        return out;
    }

    private JsonNode visitById(UUID id) throws Exception {
        return calendar().stream().filter(v -> id.toString().equals(v.path("id").asText())).findFirst().orElseThrow();
    }

    private static Instant startOf(JsonNode visit) {
        return Instant.parse(visit.path("serviceStart").asText());
    }

    private static JsonNode byKey(JsonNode metrics, String key) {
        for (JsonNode metric : metrics) {
            if (key.equals(metric.path("key").asText())) {
                return metric;
            }
        }
        throw new AssertionError("metric " + key + " is missing from /api/app/metrics");
    }

    /** The two calls start together; results come back in the order of the calls. */
    private static List<MvcResult> race(Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Callable<MvcResult> call : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return call.call();
                }));
            }
            List<MvcResult> out = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                out.add(future.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- stub requests

    private static String toolResultJson(List<LlmRequest> requests, int requestIndex) {
        return ((LlmMessage.ToolResult) requests.get(requestIndex).messages().getLast()).contentJson();
    }

    /** The tool result the stub received as the last message of request {@code requestIndex}. */
    private static JsonNode toolResult(List<LlmRequest> requests, int requestIndex) throws Exception {
        return JSON.readTree(toolResultJson(requests, requestIndex));
    }

    private static void assertNoPersonalData(String where, String text, List<String> names, String phone) {
        if (text == null) {
            return;
        }
        for (String name : names) {
            assertThat(text.toLowerCase()).as("%s must not show '%s': %s", where, name, text).doesNotContain(name.toLowerCase());
        }
        assertThat(PhoneShape.showsPhone(text, phone)).as("%s must not show a phone: %s", where, text).isFalse();
    }

    // ---------------------------------------------------------------- fixtures

    private <T> T as(UUID tenant, ActorContext.Actor actor, Supplier<T> call) {
        try (TenantContext.Scope ignored = TenantContext.open(tenant)) {
            ActorContext.set(actor);
            return call.get();
        } finally {
            ActorContext.clear();
        }
    }

    private <T> T asOwner(Supplier<T> call) {
        return as(studio.tenant(), new ActorContext.Actor("owner-it", ActorContext.Role.OWNER, null), call);
    }

    /** A tenant with one master (a unique name), one 30 minute service and a master who works around the clock. */
    private Studio newStudio() {
        UUID tenant = tenantOnly();
        String masterName = "Tamar Gelashvili" + letters();
        return as(tenant, ActorContext.system(), () -> {
            catalog.saveRules(new BookingRulesView(15, 60, 30, true, 2, false, false, 1, true));
            UUID service = catalog.upsertService(new ServiceView(null, "Cut", "Cut", 30, 5000, 0, 0, "#0369a1", true, true)).id();
            UUID master = catalog.upsertSpecialist(new SpecialistView(null, masterName, null, "#0f766e", true)).id();
            catalog.upsertMatrix(new MasterServiceView(UUID.randomUUID(), master, service, true, null, null, null, null));
            catalog.replaceWeeklySchedule(master, IntStream.rangeClosed(1, 7)
                    .mapToObj(day -> new WeeklyInterval(UUID.randomUUID(), master, day, 0, 1440))
                    .toList());
            return new Studio(tenant, master, masterName, service);
        });
    }

    private UUID tenantOnly() {
        UUID tenant = UUID.randomUUID();
        tenants.upsert(tenant, "ai-it-" + tenant, "AI IT Studio", "Asia/Tbilisi", "GEL", "GE");
        return tenant;
    }

    private Person client() {
        return client("Zurab Kvaratskhelia" + letters());
    }

    private Person client(String name) {
        String phone = phone();
        ClientView client = asOwner(() -> crm.findOrCreate(phone, name, "en", false, null));
        return new Person(client.id(), name, phone);
    }

    private AppointmentView visit(Person client, Instant start) {
        return asOwner(() -> booking.create(new CreateBookingCommand(studio.master(), studio.service(), start, client.id(),
                null, null, null, false, null, null, "CONSOLE", null, false)));
    }

    /** The {@code index}-th free start of the studio's master {@code daysAhead} days from today (tenant zone). */
    private Instant slot(int daysAhead, int index) {
        LocalDate day = LocalDate.now(TBILISI).plusDays(daysAhead);
        return asOwner(() -> booking.freeSlots(studio.master(), studio.service(), day, day)).get(index);
    }

    private static String letters() {
        return Long.toString(ThreadLocalRandom.current().nextLong(1L << 40), 26).replaceAll("[0-9]", "x");
    }

    private static String phone() {
        return "+995591" + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    /** {@code +995 591 xx xx xx}: the shape of a valid Georgian mobile number (the engine validates phones). */
    private static String spaced(String e164) {
        return "+995 " + e164.substring(4, 7) + " " + e164.substring(7, 9) + " " + e164.substring(9, 11) + " " + e164.substring(11);
    }

    private static List<String> words(String name) {
        return List.of(name.split(" "));
    }

    private static void setBudget(UUID tenant, long tokens) throws SQLException {
        update("insert into ai_tenant_settings (tenant_id, notes_to_llm, monthly_token_budget, updated_at) values (?, false, ?, now()) "
                + "on conflict (tenant_id) do update set monthly_token_budget = excluded.monthly_token_budget", tenant, tokens);
    }

    // ---------------------------------------------------------------- owner jdbc

    private static List<String> column(String sql, Object... args) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        }
    }

    /** The first column of the only row. */
    private static String one(String sql, Object... args) throws SQLException {
        List<String> rows = column(sql, args);
        assertThat(rows).as(sql).hasSize(1);
        return rows.get(0);
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

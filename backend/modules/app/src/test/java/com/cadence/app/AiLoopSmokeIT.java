package com.cadence.app;

import com.cadence.ai.api.AiAssistantService;
import com.cadence.ai.api.ChatResponse;
import com.cadence.ai.internal.llm.FinishReason;
import com.cadence.ai.internal.llm.LlmMessage;
import com.cadence.ai.internal.llm.LlmRequest;
import com.cadence.ai.internal.llm.LlmResponse;
import com.cadence.ai.internal.llm.StubLlmProvider;
import com.cadence.ai.internal.llm.ToolCall;
import com.cadence.ai.internal.llm.Usage;
import com.cadence.app.seed.DemoIds;
import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.BookingService;
import com.cadence.catalog.api.CatalogService;
import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.growth.api.GrowthService;
import com.cadence.growth.api.MetricView;
import com.cadence.platform.ai.AiDraftStore;
import com.cadence.platform.ai.AiLogStore;
import com.cadence.platform.ai.AiMessageStore;
import com.cadence.platform.ai.AiPseudonymStore;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.phone.PhoneShape;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR 0002: the tool loop against the real database as the RLS role: get_metrics, then draft_booking, then text, then confirm
 * creates the visit. Proves the wiring, the short transactions in strict mode ({@code app.persistence.require-transaction})
 * and RLS end to end; the scenario suite is {@code AiAssistantIT}.
 */
@SpringBootTest
class AiLoopSmokeIT extends AbstractTimescaleIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm");
    private static final ZoneId TBILISI = ZoneId.of("Asia/Tbilisi");

    @Autowired
    AiAssistantService assistant;
    @Autowired
    StubLlmProvider stub;
    @Autowired
    BookingService booking;
    @Autowired
    CrmService crm;
    @Autowired
    GrowthService growth;
    @Autowired
    AiPseudonymStore pseudonyms;
    @Autowired
    AiDraftStore drafts;
    @Autowired
    AiLogStore logs;
    @Autowired
    AiMessageStore messages;
    @Autowired
    CatalogService catalog;

    private TenantContext.Scope tenant;

    @BeforeEach
    void ownerOfLumen() {
        stub.reset();
        tenant = TenantContext.open(DemoIds.LUMEN);
        ActorContext.set(new ActorContext.Actor("owner-smoke", ActorContext.Role.OWNER, null));
    }

    @AfterEach
    void clear() {
        ActorContext.clear();
        tenant.close();
        stub.reset();
    }

    private static LlmResponse call(String name, String args) {
        return new LlmResponse(new LlmMessage.Assistant(null, List.of(new ToolCall("call-" + name, name, args))),
                FinishReason.TOOL_CALLS, new Usage(100, 10));
    }

    private static LlmResponse text(String reply) {
        return new LlmResponse(new LlmMessage.Assistant(reply, List.of()), FinishReason.STOP, new Usage(100, 10));
    }

    private String tokenOf(UUID conversation, UUID entity) {
        return pseudonyms.all(conversation).stream()
                .filter(m -> entity.equals(m.entityId()))
                .map(AiPseudonymStore.Mapping::token)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void metricsThenADraftThenTextThenConfirmCreatesTheVisit() throws Exception {
        String clientName = "Zurab Kvaratskhelia" + Long.toString(System.nanoTime(), 36).replaceAll("[0-9]", "x");
        String phone = "+99559" + String.format("%07d", (int) (System.nanoTime() % 10_000_000));
        ClientView client = crm.findOrCreate(phone, clientName, "en", false, null);
        LocalDate day = Instant.now().atZone(TBILISI).toLocalDate().plusDays(3);
        Instant slot = booking.freeSlots(DemoIds.NINO, DemoIds.HAIRCUT, day, day.plusDays(6)).get(0);

        // Turn 1: the refs get made; the refs of this conversation are known from the store only after it.
        stub.enqueue(text("Hello"));
        UUID conversation = assistant.chat(null, "en", "About " + clientName).conversationId();

        stub.reset();
        String args = "{\"clientRef\":\"" + tokenOf(conversation, client.id()) + "\",\"masterRef\":\""
                + tokenOf(conversation, DemoIds.NINO) + "\",\"serviceRef\":\"" + tokenOf(conversation, DemoIds.HAIRCUT)
                + "\",\"start\":\"" + LOCAL.format(slot.atZone(TBILISI)) + "\"}";
        String clientRef = tokenOf(conversation, client.id());
        stub.enqueue(call("get_metrics", "{}"), call("draft_booking", args), text("Proposed a visit for " + clientRef));

        ChatResponse turn = assistant.chat(conversation, "en", "Book " + clientName);

        assertThat(turn.reply()).isEqualTo("Proposed a visit for " + clientName);
        assertThat(turn.usage().monthTokensUsed()).isPositive();

        // get_metrics numbers are the ones of the port
        List<LlmRequest> requests = stub.recordedRequests();
        assertThat(requests).hasSize(3);
        String metricsJson = ((LlmMessage.ToolResult) requests.get(1).messages().getLast()).contentJson();
        JsonNode sent = JSON.readTree(metricsJson).path("metrics");
        List<MetricView> metrics = growth.currentMetrics(null);
        assertThat(sent).hasSize(metrics.size());
        for (int i = 0; i < metrics.size(); i++) {
            assertThat(sent.get(i).path("key").asText()).isEqualTo(metrics.get(i).key());
            if (metrics.get(i).value() != null) {
                assertThat(sent.get(i).path("value").asDouble()).isEqualTo(metrics.get(i).value());
            }
        }

        // the draft: PENDING, nothing booked
        assertThat(turn.drafts()).hasSize(1);
        ChatResponse.DraftAction draft = turn.drafts().get(0);
        assertThat(draft.status()).isEqualTo("PENDING");
        assertThat(draft.summary().clientName()).isEqualTo(clientName);
        assertThat(booking.clientHistory(client.id())).isEmpty();

        // nothing personal in what the model got or in what is stored, read under RLS
        String masterName = catalog.findSpecialist(DemoIds.NINO).orElseThrow().displayName();
        List<String> forbidden = new ArrayList<>(List.of(clientName, phone, phone.substring(4), masterName));
        forbidden.addAll(List.of(masterName.split(" ")));
        List<AiMessageStore.MessageRecord> stored = messages.list(conversation);
        assertThat(stored).hasSize(4);
        for (AiMessageStore.MessageRecord message : stored) {
            assertThat(message.content()).doesNotContain(forbidden);
            assertThat(PhoneShape.showsPhone(message.content(), phone)).isFalse();
        }
        assertThat(requests.toString()).doesNotContain(forbidden);
        List<AiLogStore.LlmCallRecord> llmCalls = logs.listLlmCalls(conversation);
        assertThat(llmCalls).hasSize(4);
        assertThat(llmCalls).allSatisfy(r -> {
            assertThat(r.call().prompt()).doesNotContain(forbidden);
            assertThat(r.call().finishReason()).isNotNull();
        });
        assertThat(logs.listToolCalls(conversation)).hasSize(2)
                .allSatisfy(r -> {
                    assertThat(r.call().resultJson()).doesNotContain(forbidden);
                    assertThat(r.call().argumentsJson()).doesNotContain(forbidden);
                });

        // confirm goes through the engine
        ChatResponse.DraftAction confirmed = assistant.confirmDraft(draft.id());

        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        AppointmentView visit = booking.find(confirmed.appliedEntityId()).orElseThrow();
        assertThat(visit.clientId()).isEqualTo(client.id());
        assertThat(visit.serviceStart()).isEqualTo(slot);
        assertThat(visit.source()).isEqualTo("AI");
        assertThatThrownBy(() -> assistant.confirmDraft(draft.id()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("DRAFT_NOT_PENDING"));
        assertThat(drafts.require(draft.id()).appliedEntityId()).isEqualTo(visit.id());

        // the names come back for the owner
        assertThat(assistant.conversation(conversation).messages().getLast().content())
                .isEqualTo("Proposed a visit for " + clientName);
    }
}

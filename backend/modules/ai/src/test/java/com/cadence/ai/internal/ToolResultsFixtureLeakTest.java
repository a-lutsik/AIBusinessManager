package com.cadence.ai.internal;

import com.cadence.ai.api.ChatResponse;
import com.cadence.ai.internal.llm.FinishReason;
import com.cadence.ai.internal.llm.LlmMessage;
import com.cadence.ai.internal.llm.LlmRequest;
import com.cadence.ai.internal.llm.LlmResponse;
import com.cadence.ai.internal.llm.ToolCall;
import com.cadence.ai.internal.llm.Usage;
import com.cadence.growth.api.MetricView;
import com.cadence.platform.ai.AiLogStore;
import com.cadence.platform.ai.AiMessageStore;
import com.cadence.platform.ai.AiSettingsStore;
import com.cadence.platform.phone.PhoneShape;
import com.cadence.platform.tenancy.ActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.cadence.ai.internal.ChatHarness.CLIENT_ID;
import static com.cadence.ai.internal.ChatHarness.CLIENT_NAME;
import static com.cadence.ai.internal.ChatHarness.CLIENT_PHONE;
import static com.cadence.ai.internal.ChatHarness.MASTER_ID;
import static com.cadence.ai.internal.ChatHarness.MASTER_NAME;
import static com.cadence.ai.internal.ChatHarness.SERVICE_ID;
import static com.cadence.ai.internal.ChatHarness.SLOT_LOCAL;
import static com.cadence.ai.internal.ChatHarness.TELEGRAM;
import static com.cadence.ai.internal.ChatHarness.VISIT_ID;
import static com.cadence.ai.internal.ChatHarness.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

/**
 * ADR 0002 §2.3, by value: all seven tools run over fixtures with every personal field filled in, and neither the requests to
 * the provider nor the logs and messages hold a name, phone, telegram id, typed name, entity id or {@link PhoneShape} match.
 */
class ToolResultsFixtureLeakTest {

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nothingPersonalCrossesWithNotesOffOrOn(boolean notesToLlm) {
        ChatHarness h = new ChatHarness();
        h.settings.current = new AiSettingsStore.Settings(notesToLlm, null);
        when(h.growth.currentMetrics(null)).thenReturn(List.of(new MetricView("NO_SHOW_RATE", 0.1, 40, false, "5 no-shows of 40 visits",
                null, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02"), "RATIO", null,
                List.of(new MetricView.SpecialistBreakdown(MASTER_ID, MASTER_NAME, 0.2, false)))));
        h.stub.enqueue(new LlmResponse(new LlmMessage.Assistant(null, List.of(
                new ToolCall("1", "get_schedule", "{\"dateFrom\":\"2026-11-03\",\"dateTo\":\"2026-11-04\"}"),
                new ToolCall("2", "find_free_slots", "{\"masterRef\":\"MASTER_1\",\"serviceRef\":\"SERVICE_1\","
                        + "\"dateFrom\":\"2026-11-03\",\"dateTo\":\"2026-11-03\"}"),
                new ToolCall("3", "get_client_profile", "{\"clientRef\":\"CLIENT_1\"}"),
                new ToolCall("4", "get_metrics", "{}"),
                new ToolCall("5", "draft_booking", "{\"clientRef\":\"CLIENT_1\",\"masterRef\":\"MASTER_1\","
                        + "\"serviceRef\":\"SERVICE_1\",\"start\":\"" + SLOT_LOCAL + "\"}"),
                new ToolCall("6", "draft_reschedule", "{\"visitRef\":\"VISIT_1\",\"newStart\":\"" + SLOT_LOCAL + "\"}"),
                new ToolCall("7", "draft_cancel", "{\"visitRef\":\"VISIT_1\",\"initiatedBy\":\"business\"}"))),
                FinishReason.TOOL_CALLS, Usage.ZERO), text("Done for CLIENT_1"));

        ChatResponse response = h.service.chat(null, "en", "Look at Maria Sokolova, her phone is " + CLIENT_PHONE);

        // Three drafts prove that all the tools really ran, not just failed before touching the fixtures.
        assertEquals(3, response.drafts().size());
        assertEquals(List.of(AiLogStore.ToolStatus.OK, AiLogStore.ToolStatus.OK, AiLogStore.ToolStatus.OK, AiLogStore.ToolStatus.OK,
                        AiLogStore.ToolStatus.OK, AiLogStore.ToolStatus.OK, AiLogStore.ToolStatus.OK),
                h.logs.toolCalls.stream().map(AiLogStore.ToolCall::status).toList());

        List<String> texts = new ArrayList<>();
        for (LlmRequest request : h.stub.recordedRequests()) {
            for (LlmMessage message : request.messages()) {
                texts.add(message.toString());
            }
        }
        for (AiLogStore.LlmCall call : h.logs.llmCalls) {
            texts.add(call.prompt());
            texts.add(String.valueOf(call.response()));
            texts.add(String.valueOf(call.toolCallsJson()));
        }
        for (AiLogStore.ToolCall call : h.logs.toolCalls) {
            texts.add(call.argumentsJson());
            texts.add(call.resultJson());
        }
        for (AiMessageStore.MessageRecord message : h.messages.rows) {
            texts.add(message.content());
        }
        List<String> secrets = List.of("Maria", "Sokolova", "Nino", "Beridze", "Masha", CLIENT_PHONE, TELEGRAM,
                "note about", "Allergy", CLIENT_ID.toString(), MASTER_ID.toString(), SERVICE_ID.toString(), VISIT_ID.toString(),
                "Owner trusts", "override");
        for (String text : texts) {
            for (String secret : secrets) {
                assertFalse(text.contains(secret), "'" + secret + "' in: " + text);
            }
            assertFalse(PhoneShape.showsPhone(text, CLIENT_PHONE), "phone shape in: " + text);
        }
        // The owner's cards do carry the names: they are what the owner confirms.
        assertEquals(CLIENT_NAME, response.drafts().get(0).summary().clientName());
    }
}

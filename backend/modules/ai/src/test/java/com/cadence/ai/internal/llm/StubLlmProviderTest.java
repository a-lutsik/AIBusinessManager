package com.cadence.ai.internal.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StubLlmProviderTest {

    private StubLlmProvider stub;

    @BeforeEach
    void setUp() {
        stub = new StubLlmProvider();
    }

    @Test
    void identifiesAsStub() {
        assertEquals("stub", stub.provider());
        assertEquals("stub", stub.model());
    }

    @Test
    void returnsScriptedResponsesInEnqueueOrderThenFallsBackToDefault() {
        LlmResponse first = new LlmResponse(
                new LlmMessage.Assistant(null, List.of(new ToolCall("c1", "get_metrics", "{}"))),
                FinishReason.TOOL_CALLS,
                new Usage(11, 2)
        );
        LlmResponse second = new LlmResponse(
                new LlmMessage.Assistant("done", List.of()), FinishReason.STOP, new Usage(30, 4)
        );
        LlmResponse third = new LlmResponse(
                new LlmMessage.Assistant("later", List.of()), FinishReason.STOP, new Usage(1, 1)
        );
        stub.enqueue(first, second);
        stub.enqueue(third);

        assertEquals(first, stub.chat(request("hi")));
        assertEquals(second, stub.chat(request("hi")));
        assertEquals(third, stub.chat(request("hi")));
        LlmResponse unscripted = stub.chat(request("hi"));
        assertEquals(FinishReason.STOP, unscripted.finishReason());
        assertTrue(unscripted.message().content().contains("hi"));
    }

    @Test
    void recordsEveryRequestInOrderAndReturnsASnapshot() {
        LlmRequest a = request("a");
        LlmRequest b = request("b");
        stub.chat(a);
        List<LlmRequest> snapshot = stub.recordedRequests();
        stub.chat(b);

        assertEquals(List.of(a), snapshot);
        assertEquals(List.of(a, b), stub.recordedRequests());
    }

    @Test
    void resetClearsScriptAndRecordedRequests() {
        stub.enqueue(new LlmResponse(new LlmMessage.Assistant("scripted", List.of()), FinishReason.STOP, new Usage(1, 1)));
        stub.chat(request("x"));
        stub.enqueue(new LlmResponse(new LlmMessage.Assistant("scripted", List.of()), FinishReason.STOP, new Usage(1, 1)));

        stub.reset();

        assertTrue(stub.recordedRequests().isEmpty());
        assertNotEquals("scripted", stub.chat(request("x")).message().content());
        assertEquals(1, stub.recordedRequests().size());
    }

    @Test
    void defaultReplyIsDeterministicEchoWithCharsOverFourUsage() {
        // 8 + 12 = 20 request chars -> 5 prompt tokens
        LlmRequest req = new LlmRequest(
                List.of(new LlmMessage.System("12345678"), new LlmMessage.User("How is load?")),
                List.of(), ToolChoice.AUTO, 100
        );

        LlmResponse r1 = stub.chat(req);
        LlmResponse r2 = stub.chat(req);

        assertEquals(r1, r2);
        assertEquals(FinishReason.STOP, r1.finishReason());
        assertTrue(r1.message().toolCalls().isEmpty());
        assertTrue(r1.message().content().endsWith("User said: How is load?"), r1.message().content());
        assertEquals(5, r1.usage().promptTokens());
        assertEquals((r1.message().content().length() + 3) / 4, r1.usage().completionTokens());
    }

    @Test
    void directiveToolReturnsToolCallThenSummarisesToolResult() {
        LlmRequest step1 = new LlmRequest(
                List.of(
                        new LlmMessage.System("sys"),
                        new LlmMessage.User("earlier"),
                        new LlmMessage.Assistant("earlier answer", List.of()),
                        new LlmMessage.User(">>tool get_schedule {\"dateFrom\":\"2026-10-26\",\"dateTo\":\"2026-10-27\"}")
                ),
                List.of(), ToolChoice.AUTO, 100
        );

        LlmResponse call = stub.chat(step1);

        assertEquals(FinishReason.TOOL_CALLS, call.finishReason());
        assertEquals(1, call.message().toolCalls().size());
        ToolCall tc = call.message().toolCalls().getFirst();
        assertEquals("get_schedule", tc.name());
        assertEquals("{\"dateFrom\":\"2026-10-26\",\"dateTo\":\"2026-10-27\"}", tc.argumentsJson());
        assertNotNull(tc.id());
        assertFalse(tc.id().isBlank());
        assertTrue(call.usage().promptTokens() > 0);

        List<LlmMessage> next = new ArrayList<>(step1.messages());
        next.add(call.message());
        next.add(new LlmMessage.ToolResult(tc.id(), tc.name(), "{\"visits\":[{\"visitRef\":\"VISIT_1\"}]}"));
        LlmResponse summary = stub.chat(new LlmRequest(next, List.of(), ToolChoice.AUTO, 100));

        assertEquals(FinishReason.STOP, summary.finishReason());
        assertTrue(summary.message().toolCalls().isEmpty());
        assertTrue(summary.message().content().contains("get_schedule"), summary.message().content());
        assertTrue(summary.message().content().contains("VISIT_1"), summary.message().content());
    }

    @Test
    void directiveToolWithoutArgumentsUsesEmptyObjectAndIdsAreUnique() {
        LlmResponse a = stub.chat(request(">>tool get_metrics"));
        LlmResponse b = stub.chat(request(">>tool get_metrics"));

        assertEquals("{}", a.message().toolCalls().getFirst().argumentsJson());
        assertNotEquals(a.message().toolCalls().getFirst().id(), b.message().toolCalls().getFirst().id());
    }

    @Test
    void directiveToolIsNotReturnedWhenToolChoiceIsNone() {
        LlmResponse r = stub.chat(new LlmRequest(
                List.of(new LlmMessage.User(">>tool get_metrics {}")), List.of(), ToolChoice.NONE, 100
        ));

        assertEquals(FinishReason.STOP, r.finishReason());
        assertTrue(r.message().toolCalls().isEmpty());
        assertNotNull(r.message().content());
    }

    @Test
    void directiveTextReturnsThatText() {
        LlmResponse r = stub.chat(request(">>text Load is 61% (window 28 days, n=40)."));

        assertEquals(FinishReason.STOP, r.finishReason());
        assertEquals("Load is 61% (window 28 days, n=40).", r.message().content());
        assertTrue(r.message().toolCalls().isEmpty());
    }

    @Test
    void scriptTakesPrecedenceOverDirective() {
        LlmResponse scripted = new LlmResponse(new LlmMessage.Assistant("scripted", List.of()), FinishReason.STOP, new Usage(1, 1));
        stub.enqueue(scripted);

        assertEquals(scripted, stub.chat(request(">>tool get_metrics {}")));
    }

    @Test
    void concurrentCallsConsumeEachScriptedResponseExactlyOnce() throws Exception {
        int n = 200;
        for (int i = 0; i < n; i++) {
            stub.enqueue(new LlmResponse(new LlmMessage.Assistant("r" + i, List.of()), FinishReason.STOP, new Usage(i, 0)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return stub.chat(request("q")).message().content();
            }));
        }
        start.countDown();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Future<String> f : futures) {
            seen.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertEquals(n, seen.size());
        assertTrue(seen.stream().allMatch(s -> s.startsWith("r")));
        assertEquals(n, stub.recordedRequests().size());
    }

    private static LlmRequest request(String userText) {
        return new LlmRequest(List.of(new LlmMessage.User(userText)), List.of(), ToolChoice.AUTO, 100);
    }
}

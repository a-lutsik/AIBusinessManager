package com.cadence.ai.internal.llm;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic provider for tests, E2E and keyless boots. Thread-safe.
 * <ol>
 *     <li>Scripted: responses queued with {@link #enqueue} are returned first, in order, one per call.</li>
 *     <li>Directive (for Playwright): when the last user message is {@code >>tool <name> <json>} and nothing has
 *     answered it yet, the stub calls that tool; once the request ends with tool results it replies with a short
 *     text quoting them. {@code >>text <reply>} replies with that text.</li>
 *     <li>Otherwise a fixed echo reply.</li>
 * </ol>
 * Unscripted responses estimate usage as characters / 4. Every request is recorded for assertions.
 */
@Component
public class StubLlmProvider implements LlmProvider {

    static final String TOOL_DIRECTIVE = ">>tool";
    static final String TEXT_DIRECTIVE = ">>text";
    private static final int SUMMARY_JSON_CHARS = 300;

    private final Queue<LlmResponse> script = new ConcurrentLinkedQueue<>();
    private final Queue<LlmRequest> recorded = new ConcurrentLinkedQueue<>();
    private final AtomicLong callIds = new AtomicLong();

    @Override
    public String provider() {
        return "stub";
    }

    @Override
    public String model() {
        return "stub";
    }

    /** Queues responses returned by the next calls, in order, before any directive or default behaviour. */
    public void enqueue(LlmResponse... responses) {
        for (LlmResponse response : responses) {
            script.add(Objects.requireNonNull(response, "response"));
        }
    }

    /** Snapshot of every request received since the last {@link #reset()}, in arrival order. */
    public List<LlmRequest> recordedRequests() {
        return List.copyOf(recorded);
    }

    /** Drops queued responses and recorded requests. */
    public void reset() {
        script.clear();
        recorded.clear();
        callIds.set(0);
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        recorded.add(request);
        LlmResponse scripted = script.poll();
        if (scripted != null) {
            return scripted;
        }
        return unscripted(request);
    }

    private LlmResponse unscripted(LlmRequest request) {
        List<LlmMessage> messages = request.messages();
        List<LlmMessage.ToolResult> trailingResults = trailingToolResults(messages);
        if (!trailingResults.isEmpty()) {
            return text(request, summarise(trailingResults));
        }
        int lastUser = lastUserIndex(messages);
        String userText = lastUser < 0 ? "" : ((LlmMessage.User) messages.get(lastUser)).content();
        String trimmed = userText.strip();
        if (request.toolChoice() == ToolChoice.AUTO
                && isDirective(trimmed, TOOL_DIRECTIVE)
                && !hasToolResultAfter(messages, lastUser)) {
            ToolCall call = parseToolDirective(trimmed);
            if (call != null) {
                LlmMessage.Assistant message = new LlmMessage.Assistant(null, List.of(call));
                return new LlmResponse(message, FinishReason.TOOL_CALLS,
                        new Usage(estimate(request), estimate(call.name().length() + call.argumentsJson().length())));
            }
        }
        if (isDirective(trimmed, TEXT_DIRECTIVE)) {
            String reply = trimmed.substring(TEXT_DIRECTIVE.length()).strip();
            if (!reply.isEmpty()) {
                return text(request, reply);
            }
        }
        return text(request, "I reviewed the latest metrics and schedule (stub model). "
                + "Confirm a draft below if you want me to change anything. Prompt locale is honored. "
                + "User said: " + userText);
    }

    private ToolCall parseToolDirective(String directive) {
        String rest = directive.substring(TOOL_DIRECTIVE.length()).strip();
        if (rest.isEmpty()) {
            return null;
        }
        int space = indexOfWhitespace(rest);
        String name = space < 0 ? rest : rest.substring(0, space);
        String args = space < 0 ? "{}" : rest.substring(space).strip();
        return new ToolCall("stub_call_" + callIds.incrementAndGet(), name, args.isEmpty() ? "{}" : args);
    }

    private static String summarise(List<LlmMessage.ToolResult> results) {
        StringBuilder sb = new StringBuilder("Tool results (stub model):");
        for (LlmMessage.ToolResult result : results) {
            String json = result.contentJson();
            if (json.length() > SUMMARY_JSON_CHARS) {
                json = json.substring(0, SUMMARY_JSON_CHARS) + "...";
            }
            sb.append(' ').append(result.toolName()).append(" -> ").append(json).append('.');
        }
        return sb.toString();
    }

    /** Tool results at the end of the conversation, i.e. the ones the model has not answered yet. */
    private static List<LlmMessage.ToolResult> trailingToolResults(List<LlmMessage> messages) {
        List<LlmMessage.ToolResult> results = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0 && messages.get(i) instanceof LlmMessage.ToolResult result; i--) {
            results.addFirst(result);
        }
        return results;
    }

    private static int lastUserIndex(List<LlmMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof LlmMessage.User) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasToolResultAfter(List<LlmMessage> messages, int index) {
        for (int i = index + 1; i < messages.size(); i++) {
            if (messages.get(i) instanceof LlmMessage.ToolResult) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDirective(String text, String directive) {
        return text.startsWith(directive)
                && (text.length() == directive.length() || Character.isWhitespace(text.charAt(directive.length())));
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static LlmResponse text(LlmRequest request, String reply) {
        return new LlmResponse(new LlmMessage.Assistant(reply, List.of()), FinishReason.STOP,
                new Usage(estimate(request), estimate(reply.length())));
    }

    private static int estimate(LlmRequest request) {
        long chars = 0;
        for (LlmMessage message : request.messages()) {
            chars += switch (message) {
                case LlmMessage.System m -> m.content().length();
                case LlmMessage.User m -> m.content().length();
                case LlmMessage.Assistant m -> (m.content() == null ? 0 : m.content().length())
                        + m.toolCalls().stream().mapToInt(c -> c.name().length() + c.argumentsJson().length()).sum();
                case LlmMessage.ToolResult m -> m.contentJson().length();
            };
        }
        return estimate(chars);
    }

    private static int estimate(long chars) {
        return (int) Math.min(Integer.MAX_VALUE, (chars + 3) / 4);
    }
}

package com.cadence.platform.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code ai_prompt_log} and {@code ai_tool_call} (ADR 0002 §6): only masked content, exactly what went to and came
 * from the provider. Every text longer than its column (prompt, response, tool calls, tool name, arguments, result,
 * error code, finish reason, provider, model) is cut with {@link AiText#TRUNCATION_MARKER}. Both tables are
 * hypertables with a 30-day retention policy.
 */
public interface AiLogStore {

    int MAX_PROMPT_CHARS = 65536;
    int MAX_RESPONSE_CHARS = 65536;
    int MAX_TOOL_CALLS_JSON_CHARS = 8192;
    int MAX_TOOL_ARGUMENTS_CHARS = 4096;
    int MAX_TOOL_RESULT_CHARS = 16384;
    /** Tool names come from the LLM (a hallucinated name is logged as asked, cut to the column). */
    int MAX_TOOL_NAME_CHARS = 64;
    int MAX_CODE_CHARS = 64;
    int MAX_FINISH_REASON_CHARS = 32;
    int MAX_PROVIDER_CHARS = 64;
    int MAX_MODEL_CHARS = 128;

    enum ToolStatus {
        OK,
        ERROR
    }

    /** One provider call. Token counts, finish reason, latency and tool calls may be null. */
    record LlmCall(
            UUID conversationId,
            UUID messageId,
            String provider,
            String model,
            String prompt,
            String response,
            Integer promptTokens,
            Integer completionTokens,
            String finishReason,
            Integer latencyMs,
            String toolCallsJson
    ) {
    }

    record LlmCallRecord(UUID id, Instant createdAt, LlmCall call) {
    }

    /** One tool execution; {@code llmCallId} is the {@link #logLlmCall} id that asked for it. */
    record ToolCall(
            UUID conversationId,
            UUID llmCallId,
            String toolName,
            String argumentsJson,
            String resultJson,
            ToolStatus status,
            String errorCode,
            int durationMs
    ) {
    }

    record ToolCallRecord(UUID id, Instant createdAt, ToolCall call) {
    }

    UUID logLlmCall(LlmCall call);

    UUID logToolCall(ToolCall call);

    /** Oldest first, at most 500. */
    List<LlmCallRecord> listLlmCalls(UUID conversationId);

    /** Oldest first, at most 500. */
    List<ToolCallRecord> listToolCalls(UUID conversationId);
}

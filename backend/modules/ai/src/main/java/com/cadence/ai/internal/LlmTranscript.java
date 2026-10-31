package com.cadence.ai.internal;

import com.cadence.ai.internal.llm.LlmMessage;
import com.cadence.ai.internal.llm.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** The masked messages and tool calls of an LLM call as JSON, for {@code ai_prompt_log} (ADR 0002 §6). */
final class LlmTranscript {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmTranscript() {
    }

    static String messages(List<LlmMessage> messages) {
        ArrayNode out = MAPPER.createArrayNode();
        for (LlmMessage message : messages) {
            ObjectNode node = out.addObject();
            switch (message) {
                case LlmMessage.System m -> node.put("role", "system").put("content", m.content());
                case LlmMessage.User m -> node.put("role", "user").put("content", m.content());
                case LlmMessage.Assistant m -> {
                    node.put("role", "assistant").put("content", m.content());
                    if (m.hasToolCalls()) {
                        node.set("tool_calls", toolCallNodes(m.toolCalls()));
                    }
                }
                case LlmMessage.ToolResult m -> node.put("role", "tool")
                        .put("tool_call_id", m.toolCallId())
                        .put("name", m.toolName())
                        .put("content", m.contentJson());
            }
        }
        return out.toString();
    }

    /** Null when the model called no tools. */
    static String toolCalls(List<ToolCall> calls) {
        return calls.isEmpty() ? null : toolCallNodes(calls).toString();
    }

    private static ArrayNode toolCallNodes(List<ToolCall> calls) {
        ArrayNode out = MAPPER.createArrayNode();
        for (ToolCall call : calls) {
            out.addObject().put("id", call.id()).put("name", call.name()).put("arguments", call.argumentsJson());
        }
        return out;
    }
}

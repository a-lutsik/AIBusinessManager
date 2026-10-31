package com.cadence.ai.internal.llm;

import java.util.List;
import java.util.Objects;

/** A chat message by role. Contents are always already masked (refs instead of names, ids and phones). */
public sealed interface LlmMessage
        permits LlmMessage.System, LlmMessage.User, LlmMessage.Assistant, LlmMessage.ToolResult {

    record System(String content) implements LlmMessage {
        public System {
            Objects.requireNonNull(content, "content");
        }
    }

    record User(String content) implements LlmMessage {
        public User {
            Objects.requireNonNull(content, "content");
        }
    }

    /**
     * Model output. {@code content} is null when the model only called tools; {@code toolCalls} is empty for a plain
     * text reply.
     */
    record Assistant(String content, List<ToolCall> toolCalls) implements LlmMessage {
        public Assistant {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }

    /** Result of executing {@code toolCallId}; {@code contentJson} is the JSON the model will read. */
    record ToolResult(String toolCallId, String toolName, String contentJson) implements LlmMessage {
        public ToolResult {
            Objects.requireNonNull(toolCallId, "toolCallId");
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(contentJson, "contentJson");
        }
    }
}

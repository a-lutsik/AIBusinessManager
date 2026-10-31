package com.cadence.ai.internal.llm;

import java.util.List;
import java.util.Objects;

/**
 * One request to the model: the full message list as it is sent, the tools the model may call, whether it may call
 * them, and the completion token cap.
 */
public record LlmRequest(
        List<LlmMessage> messages,
        List<ToolSpec> tools,
        ToolChoice toolChoice,
        int maxCompletionTokens
) {
    public LlmRequest {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        toolChoice = toolChoice == null ? ToolChoice.AUTO : toolChoice;
        if (maxCompletionTokens <= 0) {
            throw new IllegalArgumentException("maxCompletionTokens must be positive");
        }
    }
}

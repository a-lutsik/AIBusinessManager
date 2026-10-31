package com.cadence.ai.internal.llm;

import java.util.Objects;

/** One model turn: the assistant message (text or tool calls), why it stopped and what it cost. */
public record LlmResponse(LlmMessage.Assistant message, FinishReason finishReason, Usage usage) {
    public LlmResponse {
        Objects.requireNonNull(message, "message");
        finishReason = finishReason == null ? FinishReason.OTHER : finishReason;
        usage = usage == null ? Usage.ZERO : usage;
    }
}

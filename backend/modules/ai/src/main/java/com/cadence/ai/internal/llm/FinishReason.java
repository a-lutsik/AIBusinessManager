package com.cadence.ai.internal.llm;

/** Why the model stopped; anything the provider reports beyond these is {@code OTHER}. */
public enum FinishReason {
    STOP,
    TOOL_CALLS,
    LENGTH,
    OTHER
}

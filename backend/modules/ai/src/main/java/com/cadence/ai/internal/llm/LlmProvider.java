package com.cadence.ai.internal.llm;

/**
 * Strategy port for a chat-completion model with tool calling (ADR 0002 §2.1). Implementations never log message
 * contents or credentials.
 */
public interface LlmProvider {

    /** Stable provider id, e.g. "stub" or "deepseek". */
    String provider();

    /** The concrete model this provider is configured to call. */
    String model();

    /**
     * One model call: sends the whole (masked) message list plus the tool definitions and returns the assistant
     * message, which carries text, tool calls, or both.
     *
     * @throws com.cadence.platform.error.DomainException {@code LLM_UNAUTHORIZED} or {@code LLM_UNAVAILABLE} (502)
     */
    LlmResponse chat(LlmRequest request);

    /** Invoked by the registry to fail fast on misconfiguration (e.g. missing API key). */
    default void validate() {
    }
}

package com.aibusinessmanager.ai.internal;

public interface LlmProvider {

    /** Stable provider id, e.g. "stub" or "deepseek". */
    String provider();

    /** The concrete model this provider is configured to call. */
    String model();

    String complete(String systemPrompt, String userPrompt);

    /** Invoked by the registry to fail fast on misconfiguration (e.g. missing API key). */
    default void validate() {
    }
}

package com.cadence.ai.internal.llm;

import java.util.Objects;

/** A function call requested by the model; {@code argumentsJson} is the raw JSON string and is not trusted. */
public record ToolCall(String id, String name, String argumentsJson) {
    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
    }
}

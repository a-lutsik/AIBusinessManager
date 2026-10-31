package com.cadence.ai.internal.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** A tool definition offered to the model; {@code parametersSchema} is a JSON Schema object (null = no parameters). */
public record ToolSpec(String name, String description, JsonNode parametersSchema) {
    public ToolSpec {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
    }
}

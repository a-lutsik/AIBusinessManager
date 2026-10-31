package com.cadence.ai.internal.tools;

import com.cadence.ai.internal.llm.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;

/** One function the LLM may call. Arguments are untrusted; a refusal is a {@link ToolException} or the port's own code. */
public interface AiTool {

    ToolSpec spec();

    ToolResults.Result execute(ToolContext context, JsonNode args);
}

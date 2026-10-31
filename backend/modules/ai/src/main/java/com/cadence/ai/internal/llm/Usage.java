package com.cadence.ai.internal.llm;

/** Token usage of one model call; zeros when the provider did not report it. */
public record Usage(int promptTokens, int completionTokens) {

    public static final Usage ZERO = new Usage(0, 0);

    public Usage {
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
    }

    public int total() {
        return promptTokens + completionTokens;
    }
}

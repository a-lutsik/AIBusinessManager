package com.cadence.ai.internal.tools;

/** A refusal the LLM reads ({@code {"error":{code,message}}}); the turn goes on. The message never holds personal data. */
public class ToolException extends RuntimeException {

    public static final String UNKNOWN_REF = "UNKNOWN_REF";
    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    public static final String RANGE_TOO_LARGE = "RANGE_TOO_LARGE";
    public static final String DRAFT_LIMIT = "DRAFT_LIMIT";
    public static final String NOT_DRAFTABLE = "NOT_DRAFTABLE";

    private final String code;

    public ToolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

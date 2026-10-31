package com.cadence.ai.internal.llm;

/** Whether the model may call the offered tools ({@code AUTO}) or must answer with text ({@code NONE}). */
public enum ToolChoice {
    AUTO,
    NONE
}

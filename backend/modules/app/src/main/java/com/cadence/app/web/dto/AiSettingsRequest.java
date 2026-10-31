package com.cadence.app.web.dto;

/** Body of {@code PUT /api/app/ai/settings}; {@code notesToLlm} is required (a boxed type to tell "missing" from false). */
public record AiSettingsRequest(Boolean notesToLlm) {
}

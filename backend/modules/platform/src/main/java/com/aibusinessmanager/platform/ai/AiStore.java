package com.aibusinessmanager.platform.ai;

import java.util.UUID;

public interface AiStore {

    record DraftRecord(UUID id, UUID conversationId, String toolName, String payload, String status) {
    }

    void logPrompt(UUID conversationId, String provider, String model, String prompt, String response);

    UUID saveDraft(UUID conversationId, String toolName, String payload);

    DraftRecord requireDraft(UUID draftId);

    void markConfirmed(UUID draftId);
}

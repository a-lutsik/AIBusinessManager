package com.cadence.platform.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AiStore {

    record DraftRecord(UUID id, UUID conversationId, String toolName, String payload, String status, Instant createdAt) {
    }

    record PromptRecord(
            UUID id,
            UUID conversationId,
            String provider,
            String model,
            String prompt,
            String response,
            Instant createdAt
    ) {
    }

    void logPrompt(UUID conversationId, String provider, String model, String prompt, String response);

    UUID saveDraft(UUID conversationId, String toolName, String payload);

    DraftRecord requireDraft(UUID draftId);

    void markConfirmed(UUID draftId);

    void markRejected(UUID draftId);

    List<DraftRecord> listDrafts(UUID conversationId, String status);

    List<PromptRecord> listPrompts(UUID conversationId);
}

package com.cadence.ai.api;

import java.util.List;
import java.util.UUID;

public record ChatResponse(
        String reply,
        List<DraftAction> drafts,
        UUID conversationId
) {
    public record DraftAction(UUID id, String toolName, String summary, String payloadJson, String status) {
    }
}

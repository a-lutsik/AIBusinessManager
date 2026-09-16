package com.aibusinessmanager.ai.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationHistoryView(
        UUID conversationId,
        List<MessageView> messages,
        List<ChatResponse.DraftAction> drafts
) {
    public record MessageView(
            Instant at,
            String role,
            String content,
            String provider,
            String model
    ) {
    }
}

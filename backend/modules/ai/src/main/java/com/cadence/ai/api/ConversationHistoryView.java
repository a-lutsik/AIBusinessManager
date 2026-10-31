package com.cadence.ai.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationHistoryView(
        UUID conversationId,
        List<MessageView> messages,
        List<ChatResponse.DraftAction> drafts
) {
    /** {@code role} is {@code user} or {@code assistant}; {@code drafts} are those created in the turn of a user message. */
    public record MessageView(
            UUID id,
            Instant at,
            String role,
            String content,
            String provider,
            String model,
            List<ChatResponse.DraftAction> drafts
    ) {
    }
}

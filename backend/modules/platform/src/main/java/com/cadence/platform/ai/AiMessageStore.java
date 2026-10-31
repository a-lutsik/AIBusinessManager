package com.cadence.platform.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code ai_message}: the final, masked user and assistant texts of a conversation (ADR 0002 §7). */
public interface AiMessageStore {

    enum Role {
        USER,
        ASSISTANT
    }

    record MessageRecord(
            UUID id,
            UUID conversationId,
            Role role,
            String content,
            String provider,
            String model,
            Instant createdAt
    ) {
    }

    /** Max stored content length; longer content is cut with {@link AiText#TRUNCATION_MARKER}. */
    int MAX_CONTENT_CHARS = 16384;

    /**
     * Appends a message. Its time is the clock, but at least 1 µs after the previous message of the conversation, so
     * {@code created_at} is strictly increasing in append order. Concurrent appends are serialised by a
     * {@code FOR NO KEY UPDATE} lock on the conversation row, held until the transaction ends (the method runs in its
     * own transaction or joins the caller's). 404 {@code CONVERSATION_NOT_FOUND} for an invisible conversation.
     * {@code provider}/{@code model} may be null (user messages).
     */
    UUID append(UUID conversationId, Role role, String maskedContent, String provider, String model);

    /** The last {@code n} messages, oldest first. */
    List<MessageRecord> lastN(UUID conversationId, int n);

    /** All messages, oldest first. */
    List<MessageRecord> list(UUID conversationId);
}

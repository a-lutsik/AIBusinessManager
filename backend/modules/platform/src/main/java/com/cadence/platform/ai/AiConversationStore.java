package com.cadence.platform.ai;

import java.time.Instant;
import java.util.UUID;

/** {@code ai_conversation} of the current tenant (RLS). ADR 0002 §7. */
public interface AiConversationStore {

    record ConversationRecord(UUID id, String locale, Instant createdAt, Instant lastMessageAt) {
    }

    /** A new conversation; created and last-message time are the clock. */
    UUID create(String locale);

    /** 404 {@code CONVERSATION_NOT_FOUND} when the conversation does not exist or belongs to another tenant. */
    ConversationRecord require(UUID id);

    /**
     * {@code SELECT ... FOR NO KEY UPDATE} of the conversation row; the lock lasts until the caller's transaction ends,
     * so a transaction must be active. It serialises callers that take the same lock ({@link AiPseudonymStore#refFor},
     * {@link AiMessageStore#append}) but does not block the {@code FOR KEY SHARE} of FK checks on child inserts, so a
     * holder may still insert drafts, messages and pseudonyms of the conversation without deadlocking another holder.
     * 404 {@code CONVERSATION_NOT_FOUND} as {@link #require}.
     */
    ConversationRecord lockForUpdate(UUID id);

    /** Moves {@code last_message_at} forward to {@code at} (never back). */
    void touch(UUID id, Instant at);

    /**
     * Deletes the conversations whose last message is older than {@code cutoff}, with their messages, pseudonyms
     * and drafts (FK cascade). Returns the number of conversations deleted.
     */
    int deleteOlderThan(Instant cutoff);

    /**
     * Deletes the conversations with a pseudonym of this entity: messages, pseudonyms and drafts (FK cascade) and the prompt and
     * tool logs, which have no FK. For erasing personal data. Returns the number of conversations deleted.
     */
    int deleteNaming(AiPseudonymStore.Kind kind, UUID entityId);
}

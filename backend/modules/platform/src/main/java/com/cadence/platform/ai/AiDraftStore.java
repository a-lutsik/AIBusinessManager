package com.cadence.platform.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code ai_draft} (ADR 0002 §4). Status transitions are conditional: they change the row only while it is
 * {@code PENDING} and report whether exactly that one row changed.
 */
public interface AiDraftStore {

    enum Status {
        PENDING,
        CONFIRMED,
        REJECTED,
        FAILED,
        EXPIRED
    }

    record DraftRecord(
            UUID id,
            UUID conversationId,
            UUID messageId,
            String toolName,
            String payload,
            Status status,
            Instant createdAt,
            Instant expiresAt,
            Instant confirmedAt,
            Instant resolvedAt,
            UUID appliedEntityId,
            String failureCode,
            String failureMessage
    ) {
    }

    int MAX_PAYLOAD_CHARS = 8192;
    int MAX_FAILURE_MESSAGE_CHARS = 1024;
    int MAX_FAILURE_CODE_CHARS = 64;

    /**
     * A new {@code PENDING} draft of a conversation of the current tenant (FK). The payload must fit
     * {@link #MAX_PAYLOAD_CHARS} (it is parsed back, so it is never cut): {@link IllegalArgumentException} otherwise.
     */
    UUID save(UUID conversationId, UUID messageId, String toolName, String payloadJson, Instant expiresAt);

    /** 404 {@code DRAFT_NOT_FOUND} when invisible. */
    DraftRecord require(UUID id);

    /** {@link #require} with {@code SELECT ... FOR UPDATE}; the caller's transaction must be active. */
    DraftRecord requireForUpdate(UUID id);

    /**
     * The drafts of a client of any status with {@code SELECT ... FOR UPDATE} in id order; the caller's transaction must be
     * active. Found by the {@code clientId} of the payload.
     */
    List<DraftRecord> lockByClient(UUID clientId);

    /** How many PENDING drafts sit in the conversations with a pseudonym of this entity. */
    int countPendingInConversationsNaming(AiPseudonymStore.Kind kind, UUID entityId);

    /** Rewrites the payload whatever the status; only for erasing personal data, see {@link #MAX_PAYLOAD_CHARS}. */
    void replacePayload(UUID id, String payloadJson);

    boolean markConfirmed(UUID id, UUID appliedEntityId);

    boolean markRejected(UUID id);

    /** {@code code} and {@code message} are cut to {@link #MAX_FAILURE_CODE_CHARS} / {@link #MAX_FAILURE_MESSAGE_CHARS}. */
    boolean markFailed(UUID id, String code, String message);

    boolean markExpired(UUID id);

    /** Every {@code PENDING} draft of the tenant with {@code expires_at < now} becomes {@code EXPIRED}; returns the count. */
    int expireOverdue(Instant now);

    /** Newest first, at most 100; both filters optional. */
    List<DraftRecord> list(UUID conversationId, Status status);

    /** Drafts created in the turn of {@code messageId}, oldest first. */
    List<DraftRecord> listByMessage(UUID messageId);
}

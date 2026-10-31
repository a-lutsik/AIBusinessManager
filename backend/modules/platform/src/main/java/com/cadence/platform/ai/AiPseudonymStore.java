package com.cadence.platform.ai;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code ai_pseudonym}: the per-conversation map of opaque refs {@code KIND_n} to entity ids and display names
 * (ADR 0002 §3.1). Numbering is per conversation and kind, starting at 1, in order of first appearance.
 */
public interface AiPseudonymStore {

    enum Kind {
        CLIENT,
        MASTER,
        VISIT,
        SERVICE,
        DRAFT
    }

    record Ref(Kind kind, UUID entityId) {
    }

    record Mapping(Kind kind, int tokenNo, String token, UUID entityId, String display) {
    }

    /** Max stored display length; longer names are cut with {@link AiText#TRUNCATION_MARKER}. */
    int MAX_DISPLAY_CHARS = 512;

    /**
     * The token of {@code entityId} in the conversation, created on first use. The same entity always gets the same
     * token (the display of the first call is kept). Locks the conversation row ({@code FOR NO KEY UPDATE}) to serialise the
     * numbering; 404 {@code CONVERSATION_NOT_FOUND} for an invisible conversation.
     */
    String refFor(UUID conversationId, Kind kind, UUID entityId, String display);

    /**
     * Replaces the display of an entity in every conversation of the tenant, without the conversation lock (only for erasing
     * personal data). Returns the rows changed.
     */
    int replaceDisplay(Kind kind, UUID entityId, String display);

    /** Kind and entity of a token of this conversation; empty for an unknown token. */
    Optional<Ref> resolve(UUID conversationId, String token);

    Optional<String> displayOf(UUID conversationId, String token);

    /** Every mapping of the conversation, by kind and number. */
    List<Mapping> all(UUID conversationId);
}

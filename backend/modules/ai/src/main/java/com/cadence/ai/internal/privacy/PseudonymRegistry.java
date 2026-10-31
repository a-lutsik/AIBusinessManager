package com.cadence.ai.internal.privacy;

import com.cadence.platform.ai.AiPseudonymStore;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The conversation's map of opaque refs ({@code CLIENT_n}, {@code MASTER_n}, ...) over {@code ai_pseudonym}
 * (ADR 0002 §3.1). Tokens are numbered per conversation and kind on first appearance and stay stable for the whole
 * conversation, across JVM restarts; different conversations get independent numbers.
 *
 * <p>Transactions: every method runs in the store's own short transaction when the caller has none.
 * {@link #refFor} locks the {@code ai_conversation} row ({@code FOR NO KEY UPDATE}) to serialise numbering, and when it is
 * called inside a caller's transaction that lock lasts until the caller's transaction ends. So call it outside a
 * transaction or inside a short one, never inside a transaction that spans an LLM call.
 */
@Component
public class PseudonymRegistry {

    /** The token shape {@code KIND_n} the store writes; anything else is not looked up. */
    static final Pattern TOKEN = Pattern.compile("(CLIENT|MASTER|VISIT|SERVICE|DRAFT)_[1-9][0-9]{0,8}");

    private final AiPseudonymStore store;

    public PseudonymRegistry(AiPseudonymStore store) {
        this.store = store;
    }

    /**
     * The token of {@code entityId} in the conversation, created on first use; the same entity always gets the same
     * token (the first display is kept). 404 {@code CONVERSATION_NOT_FOUND} when the conversation does not exist.
     */
    public String refFor(UUID conversationId, RefKind kind, UUID entityId, String display) {
        return store.refFor(conversationId, kind.storeKind(), entityId, display == null ? "" : display);
    }

    /** The entity behind {@code token}; empty for an unknown token, a malformed one, or one of another kind. */
    public Optional<UUID> resolve(UUID conversationId, String token, RefKind expectedKind) {
        if (token == null || expectedKind == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        return store.resolve(conversationId, token)
                .filter(ref -> ref.kind() == expectedKind.storeKind())
                .map(AiPseudonymStore.Ref::entityId);
    }

    public Optional<String> displayOf(UUID conversationId, String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        return store.displayOf(conversationId, token);
    }

    /** Every token of the conversation with its display, in kind and number order (one read). */
    public Map<String, String> displays(UUID conversationId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (AiPseudonymStore.Mapping mapping : store.all(conversationId)) {
            out.put(mapping.token(), mapping.display());
        }
        return out;
    }

    /** The client and master names already known in the conversation, for {@link FreeTextMasker}. */
    public NameIndex nameIndex(UUID conversationId) {
        Map<String, String> names = new LinkedHashMap<>();
        for (AiPseudonymStore.Mapping mapping : store.all(conversationId)) {
            RefKind kind = RefKind.of(mapping.kind());
            if (kind == RefKind.CLIENT || kind == RefKind.MASTER) {
                names.put(mapping.token(), mapping.display());
            }
        }
        return NameIndex.of(names);
    }
}

package com.cadence.ai.internal.privacy;

import com.cadence.platform.ai.AiPseudonymStore;

/** Kinds of opaque refs {@code KIND_n} the LLM sees instead of ids and names (ADR 0002 §3.1). */
public enum RefKind {
    CLIENT,
    MASTER,
    VISIT,
    SERVICE,
    DRAFT;

    AiPseudonymStore.Kind storeKind() {
        return AiPseudonymStore.Kind.valueOf(name());
    }

    static RefKind of(AiPseudonymStore.Kind kind) {
        return valueOf(kind.name());
    }
}

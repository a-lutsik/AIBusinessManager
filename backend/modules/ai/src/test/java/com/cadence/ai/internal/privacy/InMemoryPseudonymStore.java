package com.cadence.ai.internal.privacy;

import com.cadence.platform.ai.AiPseudonymStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Same contract as {@code JooqAiPseudonymStore} (numbering per conversation and kind, same entity same token). */
public final class InMemoryPseudonymStore implements AiPseudonymStore {

    private record Row(UUID conversationId, Mapping mapping) {
    }

    private final List<Row> rows = new ArrayList<>();
    int resolveCalls;

    @Override
    public synchronized String refFor(UUID conversationId, Kind kind, UUID entityId, String display) {
        if (conversationId == null || kind == null || entityId == null || display == null) {
            throw new IllegalArgumentException("conversation, kind, entityId and display are required");
        }
        for (Row row : rows) {
            if (row.conversationId().equals(conversationId) && row.mapping().kind() == kind
                    && row.mapping().entityId().equals(entityId)) {
                return row.mapping().token();
            }
        }
        int next = 1 + (int) rows.stream()
                .filter(r -> r.conversationId().equals(conversationId) && r.mapping().kind() == kind)
                .count();
        String token = kind.name() + "_" + next;
        rows.add(new Row(conversationId, new Mapping(kind, next, token, entityId, display)));
        return token;
    }

    @Override
    public synchronized int replaceDisplay(Kind kind, UUID entityId, String display) {
        int changed = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            Mapping m = row.mapping();
            if (m.kind() == kind && m.entityId().equals(entityId)) {
                rows.set(i, new Row(row.conversationId(), new Mapping(kind, m.tokenNo(), m.token(), entityId, display)));
                changed++;
            }
        }
        return changed;
    }

    @Override
    public synchronized Optional<Ref> resolve(UUID conversationId, String token) {
        resolveCalls++;
        return find(conversationId, token).map(m -> new Ref(m.kind(), m.entityId()));
    }

    @Override
    public synchronized Optional<String> displayOf(UUID conversationId, String token) {
        return find(conversationId, token).map(Mapping::display);
    }

    @Override
    public synchronized List<Mapping> all(UUID conversationId) {
        return rows.stream()
                .filter(r -> r.conversationId().equals(conversationId))
                .map(Row::mapping)
                .sorted(Comparator.comparing((Mapping m) -> m.kind().name()).thenComparingInt(Mapping::tokenNo))
                .toList();
    }

    private Optional<Mapping> find(UUID conversationId, String token) {
        return rows.stream()
                .filter(r -> r.conversationId().equals(conversationId) && r.mapping().token().equals(token))
                .map(Row::mapping)
                .findFirst();
    }
}

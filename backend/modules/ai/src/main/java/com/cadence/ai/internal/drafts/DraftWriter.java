package com.cadence.ai.internal.drafts;

import com.cadence.ai.internal.AiProperties;
import com.cadence.ai.internal.privacy.RefKind;
import com.cadence.ai.internal.tools.ToolContext;
import com.cadence.ai.internal.tools.ToolResults;
import com.cadence.platform.ai.AiDraftStore;
import com.cadence.platform.ai.AiText;
import com.cadence.platform.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/**
 * Stores a checked draft and its {@code ai.draft.create} audit in one short transaction (ADR 0002 §4.2). The draft's ref
 * comes after that commit: {@code refFor} takes the conversation lock, which must stay out of draft transactions.
 */
@Component
public class DraftWriter {

    private static final Logger log = LoggerFactory.getLogger(DraftWriter.class);
    private static final int MAX_ACTOR_ID_CHARS = 128;

    private final AiDraftStore drafts;
    private final AuditLogger audit;
    private final TransactionTemplate tx;
    private final AiProperties properties;
    private final Clock clock;

    public DraftWriter(AiDraftStore drafts, AuditLogger audit, TransactionTemplate tx, AiProperties properties, Clock clock) {
        this.drafts = drafts;
        this.audit = audit;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    public ToolResults.DraftCreated save(
            ToolContext context,
            DraftPayloads.Payload payload,
            String display,
            ToolResults.DraftRefs refs
    ) {
        String json = DraftPayloads.write(payload);
        UUID conversationId = context.conversationId();
        UUID draftId = tx.execute(status -> {
            UUID id = drafts.save(conversationId, context.userMessageId(), payload.type(), json,
                    clock.instant().plus(properties.draftTtl()));
            audit.record(AiText.truncate(context.actorId(), MAX_ACTOR_ID_CHARS), "AI", "ai.draft.create", "ai_draft", id,
                    null, DraftPayloads.forAudit(json), "ai-chat", conversationId.toString());
            return id;
        });
        context.draftCreated();
        log.info("AI draft created: tenant={}, conversation={}, draft={}, type={}",
                context.tenant().id(), conversationId, draftId, payload.type());
        return new ToolResults.DraftCreated(draftRef(context, draftId, display), payload.type(),
                AiDraftStore.Status.PENDING.name(), refs);
    }

    /** The draft is committed and on the owner's card; a failed ref must not read as "no draft" and invite a duplicate. */
    private static String draftRef(ToolContext context, UUID draftId, String display) {
        try {
            return context.ref(RefKind.DRAFT, draftId, display);
        } catch (RuntimeException e) {
            log.warn("AI draft has no ref: conversation={}, draft={}", context.conversationId(), draftId, e);
            return null;
        }
    }
}

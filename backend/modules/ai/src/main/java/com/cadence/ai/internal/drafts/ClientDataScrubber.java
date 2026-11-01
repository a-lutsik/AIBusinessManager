package com.cadence.ai.internal.drafts;

import com.cadence.crm.api.ClientRetirementListener;
import com.cadence.platform.ai.AiConversationStore;
import com.cadence.platform.ai.AiDraftStore;
import com.cadence.platform.ai.AiPseudonymStore;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.tenancy.ActorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Erases a retired client from AI data in the transaction that anonymizes or merges them away: the name of the
 * pseudonym map, and the name and reason on their drafts; a {@code PENDING} draft becomes {@code FAILED}/{@code DRAFT_STALE}.
 * A merge is treated as an anonymization: the CRM erases the absorbed profile's name, so AI data keeps no copy of it, and
 * history shows {@code anonymized} rather than the survivor's name. Messages and logs hold refs for names, but free text about
 * the person (notes sent to the LLM, reasons, what the owner typed) is in them unmasked, so an erasure deletes every conversation
 * that names the client, with its messages, drafts and logs ({@link #clientErasing}); a merge keeps them. Draft rows are locked
 * before the CRM touches the client row, the order {@link DraftApplier} uses, so a confirmation either finished before this
 * or finds the draft already failed. No conversation lock is taken before that.
 */
@Component
public class ClientDataScrubber implements ClientRetirementListener {

    private static final Logger log = LoggerFactory.getLogger(ClientDataScrubber.class);

    private final AiConversationStore conversations;
    private final AiDraftStore drafts;
    private final AiPseudonymStore pseudonyms;
    private final AuditLogger audit;

    public ClientDataScrubber(AiConversationStore conversations, AiDraftStore drafts, AiPseudonymStore pseudonyms, AuditLogger audit) {
        this.conversations = conversations;
        this.drafts = drafts;
        this.pseudonyms = pseudonyms;
        this.audit = audit;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void clientRetiring(UUID clientId) {
        List<AiDraftStore.DraftRecord> clientDrafts = drafts.lockByClient(clientId);
        int failed = 0;
        for (AiDraftStore.DraftRecord draft : clientDrafts) {
            drafts.replacePayload(draft.id(), DraftPayloads.scrubbed(draft.payload()));
            if (drafts.markFailed(draft.id(), "DRAFT_STALE", "The client was anonymized")) {
                failed++;
                audit.record(ActorContext.current().map(ActorContext.Actor::actorId).orElse("system"),
                        ActorContext.current().map(actor -> actor.role().name()).orElse("SYSTEM"),
                        "ai.draft.fail", "ai_draft", draft.id(), null,
                        "{\"status\":\"FAILED\",\"failureCode\":\"DRAFT_STALE\"}", "client-retirement", draft.id().toString());
            }
        }
        int refs = pseudonyms.replaceDisplay(AiPseudonymStore.Kind.CLIENT, clientId, DraftPayloads.ANONYMIZED);
        log.info("AI data of a retired client erased: client={}, drafts={}, failedDrafts={}, refs={}",
                clientId, clientDrafts.size(), failed, refs);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void clientErasing(UUID clientId) {
        clientRetiring(clientId);
        // The client's own drafts have just failed: whatever is still pending there is about somebody else and goes too (TD-79).
        int otherPending = drafts.countPendingInConversationsNaming(AiPseudonymStore.Kind.CLIENT, clientId);
        int deleted = conversations.deleteNaming(AiPseudonymStore.Kind.CLIENT, clientId);
        log.info("AI conversations of an erased client deleted: client={}, conversations={}, otherPendingDrafts={}",
                clientId, deleted, otherPending);
    }
}

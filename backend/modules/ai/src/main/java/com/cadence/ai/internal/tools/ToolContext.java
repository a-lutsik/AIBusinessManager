package com.cadence.ai.internal.tools;

import com.cadence.ai.internal.privacy.FreeTextMasker;
import com.cadence.ai.internal.privacy.NameIndex;
import com.cadence.ai.internal.privacy.PseudonymRegistry;
import com.cadence.ai.internal.privacy.RefKind;
import com.cadence.booking.api.AppointmentView;
import com.cadence.catalog.api.BookingRulesView;
import com.cadence.platform.tenant.TenantInfo;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What one chat turn knows: read once, shared by the system prompt and the tools, used by one thread. Refs reach the
 * LLM only through here, and a ref from the LLM resolves only within this conversation's registry.
 */
public final class ToolContext {

    private final UUID conversationId;
    private final UUID userMessageId;
    private final TenantInfo tenant;
    private final BookingRulesView rules;
    private final boolean notesToLlm;
    private final Map<UUID, String> masterNames;
    private final int maxDrafts;
    private final PseudonymRegistry registry;
    private final TransactionTemplate tx;
    private final Map<String, String> refs = new HashMap<>();
    private NameIndex labelNames;
    private String actorId = "ai:unknown";
    private int draftsCreated;

    public ToolContext(
            UUID conversationId,
            UUID userMessageId,
            TenantInfo tenant,
            BookingRulesView rules,
            boolean notesToLlm,
            Map<UUID, String> masterNames,
            int maxDrafts,
            PseudonymRegistry registry,
            TransactionTemplate tx
    ) {
        this.conversationId = conversationId;
        this.userMessageId = userMessageId;
        this.tenant = tenant;
        this.rules = rules;
        this.notesToLlm = notesToLlm;
        this.masterNames = masterNames;
        this.maxDrafts = maxDrafts;
        this.registry = registry;
        this.tx = tx;
    }

    public UUID conversationId() {
        return conversationId;
    }

    public UUID userMessageId() {
        return userMessageId;
    }

    public TenantInfo tenant() {
        return tenant;
    }

    public ZoneId zone() {
        return tenant.zone();
    }

    public BookingRulesView rules() {
        return rules;
    }

    public boolean notesToLlm() {
        return notesToLlm;
    }

    public String actorId() {
        return actorId;
    }

    /** The provider that answered last, for the audit of drafts it asks for ({@code ai:<provider>/<model>}). */
    public void respondedBy(String provider, String model) {
        actorId = "ai:" + provider + "/" + model;
    }

    /** A master's name, null when the tenant has no such master. */
    public String masterName(UUID masterId) {
        return masterNames.get(masterId);
    }

    /** The token of an entity, created on first use; one store call per entity and turn. */
    public String ref(RefKind kind, UUID entityId, String display) {
        return refs.computeIfAbsent(kind + ":" + entityId, key -> registry.refFor(conversationId, kind, entityId, display));
    }

    public String masterRef(UUID masterId, String fallbackDisplay) {
        String name = masterNames.get(masterId);
        return ref(RefKind.MASTER, masterId, name != null ? name : fallbackDisplay);
    }

    public String visitRef(AppointmentView visit) {
        return ref(RefKind.VISIT, visit.id(),
                visit.serviceNameSnapshot() + ", " + ToolArgs.local(visit.serviceStart(), zone()));
    }

    /**
     * Many {@link #ref} calls in one short transaction instead of one each. Its conversation lock lasts until it ends, so
     * only ref work belongs inside. Refs handed out by a transaction that rolled back are forgotten.
     */
    public <T> T inOneTransaction(Supplier<T> work) {
        try {
            return tx.execute(status -> work.get());
        } catch (RuntimeException e) {
            refs.clear();
            throw e;
        }
    }

    /**
     * An owner-typed label ("Стрижка у Нино") as the LLM may see it: {@link FreeTextMasker#maskLabel}, then names known in the
     * conversation. The names are read once per turn, after the system prompt has registered every master.
     */
    public String label(FreeTextMasker masker, String label) {
        if (labelNames == null) {
            labelNames = registry.nameIndex(conversationId);
        }
        return labelNames.apply(masker.maskLabel(label));
    }

    /** The entity of the ref argument {@code name}: {@code UNKNOWN_REF} when it is not of this conversation and kind. */
    public UUID resolve(JsonNode args, String name, RefKind kind) {
        return resolve(ToolArgs.text(args, name), name, kind);
    }

    /** {@link #resolve} of an optional argument: null when it is absent. */
    public UUID resolveOptional(JsonNode args, String name, RefKind kind) {
        String token = ToolArgs.optionalText(args, name);
        return token == null ? null : resolve(token, name, kind);
    }

    private UUID resolve(String token, String name, RefKind kind) {
        return registry.resolve(conversationId, token, kind)
                .orElseThrow(() -> new ToolException(ToolException.UNKNOWN_REF,
                        name + " is not a known " + kind + " reference of this conversation"));
    }

    /** {@code DRAFT_LIMIT} once this turn created its maximum; checked before any preflight work. */
    public void assertDraftAllowed() {
        if (draftsCreated >= maxDrafts) {
            throw new ToolException(ToolException.DRAFT_LIMIT, "At most " + maxDrafts + " drafts per turn");
        }
    }

    public void draftCreated() {
        draftsCreated++;
    }
}

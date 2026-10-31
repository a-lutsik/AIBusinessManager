package com.cadence.ai.internal.drafts;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.BookingService;
import com.cadence.booking.api.CreateBookingCommand;
import com.cadence.crm.api.CrmService;
import com.cadence.platform.ai.AiDraftStore;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.tenancy.ActorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Applies a draft (ADR 0002 §4.3). Claim, engine call, conditional transition and audit share one transaction, so an engine
 * refusal rolls back whatever it did; only after that rollback a separate transaction records {@code FAILED} or
 * {@code EXPIRED}. Only a {@link DomainException} is a decided refusal: anything else leaves the draft {@code PENDING} and
 * propagates. Never takes the conversation lock. Lock order is draft row, then client (the FK of a new visit), the same as
 * {@link ClientDataScrubber}. The staleness read is outside the engine's row lock (accepted): the engine still validates.
 */
@Component
public class DraftApplier {

    private static final Logger log = LoggerFactory.getLogger(DraftApplier.class);
    private static final String DRAFT_NOT_FOUND = "DRAFT_NOT_FOUND";
    private static final String DRAFT_NOT_PENDING = "DRAFT_NOT_PENDING";
    private static final String DRAFT_EXPIRED = "DRAFT_EXPIRED";
    private static final String DRAFT_STALE = "DRAFT_STALE";

    private final AiDraftStore drafts;
    private final BookingService booking;
    private final CrmService crm;
    private final AuditLogger audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DraftApplier(
            AiDraftStore drafts,
            BookingService booking,
            CrmService crm,
            AuditLogger audit,
            TransactionTemplate tx,
            Clock clock
    ) {
        this.drafts = drafts;
        this.booking = booking;
        this.crm = crm;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /** Must run outside a transaction: the failure record has to commit after the rollback. */
    public AiDraftStore.DraftRecord confirm(UUID draftId) {
        try {
            UUID applied = tx.execute(status -> claimAndApply(draftId));
            log.info("AI draft confirmed: draft={}, appliedEntity={}", draftId, applied);
        } catch (DomainException e) {
            log.info("AI draft not applied: draft={}, code={}", draftId, e.code());
            recordRefusal(draftId, e);
            throw e;
        }
        return drafts.require(draftId);
    }

    private UUID claimAndApply(UUID draftId) {
        AiDraftStore.DraftRecord draft = drafts.requireForUpdate(draftId);
        if (draft.status() != AiDraftStore.Status.PENDING) {
            throw DomainException.conflict(DRAFT_NOT_PENDING, "Draft already handled");
        }
        if (draft.expiresAt() != null && draft.expiresAt().isBefore(clock.instant())) {
            throw DomainException.conflict(DRAFT_EXPIRED, "Draft has expired");
        }
        DraftPayloads.Payload payload = DraftPayloads.parse(draft.toolName(), draft.payload());
        if (crm.findActiveById(payload.clientId()).isEmpty()) {
            throw new ClientRetired();
        }
        UUID applied = apply(payload);
        if (!drafts.markConfirmed(draftId, applied)) {
            throw new IllegalStateException("Draft " + draftId + " left PENDING while its row was locked");
        }
        audit.record(actorId(), "OWNER", "ai.draft.confirm", "ai_draft", draftId,
                DraftPayloads.forAudit(draft.payload()),
                "{\"status\":\"CONFIRMED\",\"appliedEntityId\":\"" + applied + "\"}", "ai-draft", draftId.toString());
        return applied;
    }

    private UUID apply(DraftPayloads.Payload payload) {
        return switch (payload) {
            case DraftPayloads.Booking b -> booking.create(new CreateBookingCommand(
                    b.specialistId(), b.serviceId(), Instant.parse(b.serviceStart()), b.clientId(),
                    null, null, null, false, null, null, "AI", null, false)).id();
            case DraftPayloads.Reschedule r -> {
                assertNotStale(r.appointmentId(), r.expected());
                yield booking.reschedule(r.appointmentId(), Instant.parse(r.newStart())).id();
            }
            case DraftPayloads.Cancel c -> {
                assertNotStale(c.appointmentId(), c.expected());
                // The reason stays on the owner's card: the visit's note is visible to masters and must keep what it says.
                yield booking.transition(c.appointmentId(), c.target(), null).id();
            }
        };
    }

    private void assertNotStale(UUID appointmentId, DraftPayloads.Expected expected) {
        Instant start = Instant.parse(expected.serviceStart());
        AppointmentStatus status = expected.status();
        boolean same = booking.find(appointmentId)
                .map((AppointmentView visit) -> start.equals(visit.serviceStart()) && status == visit.status())
                .orElse(false);
        if (!same) {
            throw stale("The visit changed after this draft was made");
        }
    }

    /** Stale because the client is gone: their name leaves the draft as well, whatever path retired them. */
    private static final class ClientRetired extends DomainException {
        ClientRetired() {
            super(DRAFT_STALE, 409, "The client was anonymized or merged after this draft was made");
        }
    }

    private static DomainException stale(String message) {
        return DomainException.conflict(DRAFT_STALE, message);
    }

    private void recordRefusal(UUID draftId, DomainException refusal) {
        String code = refusal.code();
        if (DRAFT_NOT_FOUND.equals(code) || DRAFT_NOT_PENDING.equals(code)) {
            return;
        }
        try {
            tx.executeWithoutResult(status -> {
                if (DRAFT_EXPIRED.equals(code)) {
                    drafts.markExpired(draftId);
                    return;
                }
                if (refusal instanceof ClientRetired) {
                    drafts.replacePayload(draftId, DraftPayloads.scrubbed(drafts.require(draftId).payload()));
                }
                if (drafts.markFailed(draftId, code, refusal.getMessage())) {
                    audit.record(actorId(), "OWNER", "ai.draft.fail", "ai_draft", draftId, null,
                            "{\"status\":\"FAILED\",\"failureCode\":\"" + code + "\"}", "ai-draft", draftId.toString());
                }
            });
        } catch (RuntimeException e) {
            log.error("Could not record the refusal of AI draft: draft={}, code={}", draftId, code, e);
            refusal.addSuppressed(e);
        }
    }

    private static String actorId() {
        return ActorContext.current().map(ActorContext.Actor::actorId).orElse("owner");
    }
}

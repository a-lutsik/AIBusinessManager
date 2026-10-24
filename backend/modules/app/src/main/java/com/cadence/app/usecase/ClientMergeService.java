package com.cadence.app.usecase;

import com.cadence.booking.api.BookingService;
import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.notification.api.OwnerTaskService;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.retention.api.RetentionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Owner use-case "merge duplicate clients". Lives in the app module because it spans four modules, none of which may
 * call another's tables: each module moves its own rows through its public port, and everything commits or rolls
 * back as one transaction.
 *
 * <p>Order: lock and validate both profiles, booking visits, retention packages and trust, owner tasks, then the CRM absorb (which also re-validates
 * the pair and rejects an already merged profile with 409, rolling the moves back).
 */
@Service
public class ClientMergeService {

    private final CrmService crm;
    private final BookingService booking;
    private final RetentionService retention;
    private final OwnerTaskService ownerTasks;
    private final AuditLogger auditLogger;

    public ClientMergeService(
            CrmService crm,
            BookingService booking,
            RetentionService retention,
            OwnerTaskService ownerTasks,
            AuditLogger auditLogger
    ) {
        this.crm = crm;
        this.booking = booking;
        this.retention = retention;
        this.ownerTasks = ownerTasks;
        this.auditLogger = auditLogger;
    }

    /**
     * Folds {@code absorbedId} into {@code survivorId} and returns the updated survivor.
     *
     * @throws DomainException {@code FORBIDDEN_RESOURCE} (403) for a non-owner, {@code INVALID_MERGE} (400) for a
     *     self-merge, {@code CLIENT_NOT_FOUND} (404) for a missing client (including one of another tenant),
     *     {@code CLIENT_ALREADY_MERGED} (409) when the absorbed client was already anonymized or merged
     */
    @Transactional
    public ClientView merge(UUID survivorId, UUID absorbedId) {
        ActorContext.Actor actor = ActorContext.current().orElse(null);
        if (actor == null || !actor.isOwner()) {
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "Only the owner can merge clients");
        }
        if (survivorId.equals(absorbedId)) {
            throw DomainException.badRequest("INVALID_MERGE", "Cannot merge a client with itself");
        }
        // Row locks on both profiles first: concurrent merges of overlapping pairs are serialised from here on.
        crm.lockForMerge(survivorId, absorbedId);
        ClientView before = crm.findById(survivorId)
                .orElseThrow(() -> DomainException.notFound("CLIENT_NOT_FOUND", "Client not found"));
        crm.findById(absorbedId)
                .orElseThrow(() -> DomainException.notFound("CLIENT_NOT_FOUND", "Client to merge not found"));

        int appointments = booking.reassignClient(absorbedId, survivorId);
        int packages = retention.reassignClient(absorbedId, survivorId);
        int tasks = ownerTasks.reassignClient(absorbedId, survivorId);
        ClientView after = crm.absorb(survivorId, absorbedId);

        // Aggregates and ids only: no names or phone numbers in the audit trail.
        auditLogger.record(
                actor.actorId(),
                actor.role().name(),
                "client.merge",
                "client",
                survivorId,
                "{\"absorbedId\":\"" + absorbedId + "\"," + aggregates(before) + "}",
                "{\"absorbedId\":\"" + absorbedId + "\"," + aggregates(after)
                        + ",\"moved\":{\"appointments\":" + appointments
                        + ",\"packages\":" + packages
                        + ",\"tasks\":" + tasks + "}}",
                "console",
                null
        );
        return after;
    }

    private static String aggregates(ClientView c) {
        return "\"completedVisitCount\":" + c.completedVisitCount()
                + ",\"noShowCount\":" + c.noShowCount()
                + ",\"lateCancelCount\":" + c.lateCancelCount()
                + ",\"completedValueMinor\":" + c.completedValueMinor();
    }
}

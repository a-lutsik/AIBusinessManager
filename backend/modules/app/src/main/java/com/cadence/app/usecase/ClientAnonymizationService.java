package com.cadence.app.usecase;

import com.cadence.booking.api.BookingService;
import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.notification.api.OwnerTaskService;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.retention.api.RetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Owner use-case "erase a client on request" (PRODUCT_PLAN §10, README "Erasing a client on request"). Lives in the app
 * module because it spans four modules, none of which may touch another's tables; everything commits or rolls back as one
 * transaction, AI data included ({@code ClientRetirementListener} runs inside {@link CrmService#anonymize}).
 *
 * <p>Order: the CRM first, because it takes the row lock that new bookings wait for; only then the visits, whose refusal of an
 * upcoming visit therefore sees every booking that got in before the lock. Irreversible. What stays are the visit facts, the
 * journal, the counters and the package balances, so metrics of past periods do not move.
 *
 * <p>A repeat is refused ({@code CLIENT_ALREADY_ANONYMIZED}) rather than answered 200: exactly one audit record per client, and a
 * profile merged away cannot get a "done" while its data lives on in the survivor.
 */
@Service
public class ClientAnonymizationService {

    private static final Logger log = LoggerFactory.getLogger(ClientAnonymizationService.class);

    private final CrmService crm;
    private final BookingService booking;
    private final RetentionService retention;
    private final OwnerTaskService ownerTasks;
    private final AuditLogger auditLogger;

    public ClientAnonymizationService(
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
     * Erases the client and returns the emptied profile.
     *
     * @throws DomainException {@code FORBIDDEN_RESOURCE} (403) for a non-owner, {@code CLIENT_NOT_FOUND} (404) for a missing
     *     client (including one of another tenant), {@code CLIENT_HAS_UPCOMING_VISITS} (409) while a pending or confirmed visit has
     *     not ended, {@code CLIENT_ALREADY_ANONYMIZED} (409) for a profile already anonymized or merged away
     */
    @Transactional
    public ClientView anonymize(UUID clientId) {
        ActorContext.Actor actor = ActorContext.current().orElse(null);
        if (actor == null || !actor.isOwner()) {
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "Only the owner can anonymize a client");
        }
        long startedAt = System.nanoTime();
        log.info("Client anonymization started: client={}, actor={}", clientId, actor.actorId());

        ClientView anonymized = crm.anonymize(clientId);
        int visits = booking.eraseClientVisits(clientId);
        int packages = retention.eraseClient(clientId);
        int tasks = ownerTasks.eraseForClient(clientId);

        // Ids and counters only: no names or phone numbers in the audit trail.
        auditLogger.record(
                actor.actorId(),
                actor.role().name(),
                "client.anonymize",
                "client",
                clientId,
                null,
                "{\"visits\":" + visits + ",\"packages\":" + packages + ",\"tasks\":" + tasks + "}",
                "console",
                null
        );
        log.info("Client anonymized: client={}, visits={}, packages={}, tasks={}, tookMs={}",
                clientId, visits, packages, tasks, (System.nanoTime() - startedAt) / 1_000_000);
        return anonymized;
    }
}

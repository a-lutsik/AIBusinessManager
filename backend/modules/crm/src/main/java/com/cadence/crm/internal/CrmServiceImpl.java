package com.cadence.crm.internal;

import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.persistence.SqlStates;
import com.cadence.platform.phone.PhoneShape;
import com.cadence.platform.tenancy.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class CrmServiceImpl implements CrmService {

    private static final int NOTES_MAX = 4096;
    private static final int TAGS_MAX = 1024;
    /** {@code client.consent_version} is VARCHAR(32). */
    private static final int CONSENT_VERSION_MAX = 32;
    /** A master's query with this many digits or more is a phone query and finds nothing (TD-18). */
    static final int MAX_MASTER_QUERY_DIGITS = 3;

    /** Unique index on (tenant_id, normalized_phone), see V3. */
    static final String PHONE_CONSTRAINT = "client_phone_uq";

    private final ClientRepository repository;
    private final TransactionTemplate savepoint;
    private final AuditLogger auditLogger;

    public CrmServiceImpl(ClientRepository repository, PlatformTransactionManager transactionManager, AuditLogger auditLogger) {
        this.repository = repository;
        this.auditLogger = auditLogger;
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Override
    public String moduleName() {
        return "crm";
    }

    @Override
    @Transactional
    public ClientView findOrCreate(
            String e164Phone,
            String displayName,
            String locale,
            boolean marketingConsent,
            String consentVersion
    ) {
        Optional<ClientView> existing = repository.findByPhone(e164Phone);
        if (existing.isPresent()) {
            return refresh(existing.get(), locale, marketingConsent, consentVersion);
        }
        ClientView created = new ClientView(
                UUID.randomUUID(),
                e164Phone,
                displayName,
                null,
                null,
                locale,
                null,
                marketingConsent,
                null,
                null,
                0,
                0,
                0,
                0
        );
        if (!insertUnlessPhoneTaken(created)) {
            // Lost the race for this phone: the winner committed its row before our insert failed, so a fresh
            // statement sees it (READ COMMITTED) and we continue exactly as for a returning client.
            ClientView winner = repository.findByPhone(e164Phone).orElseThrow(() -> DomainException.conflict(
                    "CLIENT_CONFLICT", "Client with this phone is being created, retry"));
            return refresh(winner, locale, marketingConsent, consentVersion);
        }
        repository.updateProfile(created.id(), displayName, null, null, locale, marketingConsent, consentVersion);
        return repository.findById(created.id()).orElse(created);
    }

    /**
     * Decision D (stage 03): a booking that matches this client by phone only adds to the profile. Consent {@code true}
     * is recorded with the booking's consent version and time; {@code false} never revokes a consent and leaves the
     * stored one untouched. The locale is set only when the client has none; the stored name is kept.
     */
    private ClientView refresh(ClientView row, String locale, boolean marketingConsent, String consentVersion) {
        String newLocale = row.preferredLocale() == null ? locale : null;
        Boolean consent = marketingConsent ? Boolean.TRUE : null;
        repository.updateProfile(row.id(), row.displayName(), null, null, newLocale, consent,
                marketingConsent ? consentVersion : null);
        return repository.findById(row.id()).orElse(row);
    }

    /**
     * Inserts the client inside a savepoint (nested transaction) and reports whether the row was written.
     *
     * <p>Two first bookings with the same phone can both miss {@code findByPhone} and then collide on
     * {@code client_phone_uq} (SQLSTATE 23505). PostgreSQL marks the whole transaction aborted after any error, so
     * catching the failure and re-reading in the same transaction would fail with "current transaction is aborted".
     * Rolling back only to a savepoint keeps the outer transaction (the booking being created) usable. The JDBC
     * transaction manager supports {@code PROPAGATION_NESTED} through JDBC savepoints; the RLS tenant setting is
     * re-applied before every statement, so the rollback does not lose it.
     *
     * @return {@code false} when the phone already exists (unique violation), {@code true} when inserted
     */
    private boolean insertUnlessPhoneTaken(ClientView created) {
        try {
            savepoint.executeWithoutResult(status -> repository.insert(created));
            return true;
        } catch (RuntimeException ex) {
            if (SqlStates.isUniqueViolation(ex, PHONE_CONSTRAINT)) {
                return false;
            }
            throw ex;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ClientView> findById(UUID clientId) {
        return repository.findById(clientId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ClientView> findByPhone(String e164Phone) {
        return repository.findByPhone(e164Phone);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClientView> search(String query) {
        return repository.search(query);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClientView> searchAmong(String query, Collection<UUID> clientIds) {
        if (clientIds.isEmpty() || digitCount(query) >= MAX_MASTER_QUERY_DIGITS) {
            return List.of();
        }
        // Names only: the phone is hidden from masters, so it must not be searchable as an oracle either (TD-18).
        // The whole scope is fetched (it bounds the rows), clients named by a phone are dropped, then the limit applies.
        return repository.search(query, clientIds, false, clientIds.size()).stream()
                .filter(c -> !PhoneShape.showsPhone(c.displayName(), c.normalizedPhone()))
                .limit(ClientRepository.SEARCH_LIMIT)
                .toList();
    }

    private static int digitCount(String query) {
        return query == null ? 0 : (int) query.chars().filter(ch -> ch >= '0' && ch <= '9').count();
    }

    @Override
    @Transactional
    public ClientView updateNotes(UUID clientId, String notes, String tags) {
        ClientView existing = repository.findById(clientId)
                .orElseThrow(() -> DomainException.notFound("CLIENT_NOT_FOUND", "Client not found"));
        repository.updateProfile(clientId, existing.displayName(), notes, tags, null, null, null);
        return repository.findById(clientId).orElse(existing);
    }

    @Override
    @Transactional
    public ClientView withdrawMarketingConsent(UUID clientId, String source) {
        ClientView existing = repository.findById(clientId)
                .orElseThrow(() -> DomainException.notFound("CLIENT_NOT_FOUND", "Client not found"));
        String version = "withdrawn:" + source;
        repository.updateProfile(clientId, existing.displayName(), null, null, null, false,
                version.length() > CONSENT_VERSION_MAX ? version.substring(0, CONSENT_VERSION_MAX) : version);
        auditLogger.record(
                ActorContext.current().map(ActorContext.Actor::actorId).orElse("public"),
                ActorContext.current().map(a -> a.role().name()).orElse("PUBLIC"),
                "client.consent.withdraw",
                "client",
                clientId,
                "{\"marketingConsent\":" + existing.marketingConsent() + "}",
                "{\"marketingConsent\":false}",
                source,
                null
        );
        return repository.findById(clientId).orElse(existing);
    }

    @Override
    @Transactional
    public void recordCompletedVisit(UUID clientId, Instant when, int valueMinor) {
        repository.bumpCompleted(clientId, when, valueMinor);
    }

    @Override
    @Transactional
    public void recordNoShow(UUID clientId) {
        repository.bumpNoShow(clientId);
    }

    @Override
    @Transactional
    public void recordLateCancel(UUID clientId) {
        repository.bumpLateCancel(clientId);
    }

    @Override
    @Transactional
    public ClientView anonymize(UUID clientId) {
        repository.anonymize(clientId);
        return repository.findById(clientId)
                .orElseThrow(() -> DomainException.notFound("CLIENT_NOT_FOUND", "Client not found"));
    }

    @Override
    @Transactional
    public void revertNoShow(UUID clientId) {
        repository.revertNoShow(clientId);
    }

    @Override
    @Transactional
    public void revertCompletedVisit(UUID clientId, int valueMinor, Instant firstCompletedAt, Instant lastCompletedAt) {
        repository.revertCompleted(clientId, valueMinor, firstCompletedAt, lastCompletedAt);
    }

    @Override
    @Transactional
    public void lockForMerge(UUID survivorId, UUID absorbedId) {
        lockAndLoadPair(survivorId, absorbedId);
    }

    /** Self-merge check, row locks on both profiles, then existence and retired-state checks on the locked rows. */
    private MergePair lockAndLoadPair(UUID survivorId, UUID absorbedId) {
        if (survivorId.equals(absorbedId)) {
            throw DomainException.badRequest("INVALID_MERGE", "Cannot merge a client with itself");
        }
        repository.lockForUpdate(survivorId, absorbedId);
        ClientRepository.ClientState survivor = repository.findState(survivorId)
                .orElseThrow(() -> DomainException.notFound("CLIENT_NOT_FOUND", "Client not found"));
        ClientRepository.ClientState absorbed = repository.findState(absorbedId)
                .orElseThrow(() -> DomainException.notFound("CLIENT_NOT_FOUND", "Client to merge not found"));
        if (absorbed.retired()) {
            throw DomainException.conflict("CLIENT_ALREADY_MERGED", "Client is already anonymized or merged");
        }
        if (survivor.retired()) {
            throw DomainException.conflict("CLIENT_ALREADY_MERGED", "Target client is already anonymized or merged");
        }
        return new MergePair(survivor, absorbed);
    }

    private record MergePair(ClientRepository.ClientState survivor, ClientRepository.ClientState absorbed) {
    }

    @Override
    @Transactional
    public ClientView absorb(UUID survivorId, UUID absorbedId) {
        MergePair pair = lockAndLoadPair(survivorId, absorbedId);
        ClientRepository.ClientState survivor = pair.survivor();
        ClientRepository.ClientState absorbed = pair.absorbed();
        ClientView keep = survivor.view();
        ClientView gone = absorbed.view();
        repository.applyMerge(
                survivorId,
                keep.completedVisitCount() + gone.completedVisitCount(),
                keep.noShowCount() + gone.noShowCount(),
                keep.lateCancelCount() + gone.lateCancelCount(),
                keep.completedValueMinor() + gone.completedValueMinor(),
                earliest(keep.firstVisitAt(), gone.firstVisitAt()),
                latest(keep.lastVisitAt(), gone.lastVisitAt()),
                mergeNotes(keep.notes(), gone.notes(), gone.displayName()),
                mergeTags(keep.tags(), gone.tags())
        );
        repository.markMerged(absorbedId, survivorId);
        return repository.findById(survivorId)
                .orElseThrow(() -> DomainException.notFound("CLIENT_NOT_FOUND", "Client not found"));
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    /** Appends the absorbed notes under a separator naming the absorbed profile; capped to the column length. */
    static String mergeNotes(String survivorNotes, String absorbedNotes, String absorbedName) {
        if (absorbedNotes == null || absorbedNotes.isBlank()) {
            return survivorNotes;
        }
        String block = "--- merged from " + absorbedName + " ---\n" + absorbedNotes;
        String merged = survivorNotes == null || survivorNotes.isBlank() ? block : survivorNotes + "\n" + block;
        return merged.length() > NOTES_MAX ? merged.substring(0, NOTES_MAX) : merged;
    }

    /** Union of the comma-separated tag lists, order of first appearance, case-insensitive duplicates dropped. */
    static String mergeTags(String survivorTags, String absorbedTags) {
        Map<String, String> union = new LinkedHashMap<>();
        for (String list : new String[]{survivorTags, absorbedTags}) {
            if (list == null) {
                continue;
            }
            for (String tag : list.split(",")) {
                String trimmed = tag.trim();
                if (!trimmed.isEmpty()) {
                    union.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
                }
            }
        }
        if (union.isEmpty()) {
            return survivorTags;
        }
        String merged = String.join(",", union.values());
        return merged.length() > TAGS_MAX ? merged.substring(0, TAGS_MAX) : merged;
    }
}

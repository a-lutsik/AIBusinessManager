package com.cadence.crm.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public CRM port — clients, history aggregates, packages. */
public interface CrmService {

    String moduleName();

    ClientView findOrCreate(String e164Phone, String displayName, String locale, boolean marketingConsent, String consentVersion);

    Optional<ClientView> findById(UUID clientId);

    Optional<ClientView> findByPhone(String e164Phone);

    List<ClientView> search(String query);

    /** {@link #search(String)} limited to the given clients (a master's own clients); empty ids give an empty list. */
    List<ClientView> searchAmong(String query, Collection<UUID> clientIds);

    ClientView updateNotes(UUID clientId, String notes, String tags);

    void recordCompletedVisit(UUID clientId, Instant when, int valueMinor);

    void recordNoShow(UUID clientId);

    void recordLateCancel(UUID clientId);

    ClientView anonymize(UUID clientId);

    /**
     * Reverts one recorded no-show (owner corrected NO_SHOW to COMPLETED). The counter never goes below zero.
     */
    void revertNoShow(UUID clientId);

    /**
     * Reverts one recorded completed visit (owner corrected COMPLETED to NO_SHOW): decrements the visit count and the
     * completed value (both floored at zero) and replaces the first/last visit timestamps with the values computed by
     * the caller from the client's remaining completed visits (nullable when there are none).
     */
    void revertCompletedVisit(UUID clientId, int valueMinor, Instant firstCompletedAt, Instant lastCompletedAt);

    /**
     * First step of a client merge: locks both profiles (row locks, taken in a stable order so concurrent merges of
     * the same pair cannot deadlock) and only then validates them, so two merges of overlapping pairs are serialised
     * and the loser sees the state the winner left behind. Holds until the surrounding transaction ends.
     *
     * @throws com.cadence.platform.error.DomainException {@code INVALID_MERGE} (400) for a self-merge,
     *     {@code CLIENT_NOT_FOUND} (404) for a missing profile, {@code CLIENT_ALREADY_MERGED} (409) when either
     *     profile is already anonymized or merged
     */
    void lockForMerge(UUID survivorId, UUID absorbedId);

    /**
     * CRM half of a client merge: folds the absorbed profile's aggregates, notes and tags into the survivor,
     * anonymizes the absorbed profile and marks it as merged into the survivor. The survivor's consent is never
     * changed. The caller (the app-level merge use-case) moves visits, packages and tasks through the other modules'
     * ports in the same transaction.
     *
     * @throws com.cadence.platform.error.DomainException {@code INVALID_MERGE} (400) for a self-merge,
     *     {@code CLIENT_NOT_FOUND} (404) for a missing profile, {@code CLIENT_ALREADY_MERGED} (409) when the
     *     absorbed profile is already anonymized or merged
     * @return the updated survivor
     */
    ClientView absorb(UUID survivorId, UUID absorbedId);
}

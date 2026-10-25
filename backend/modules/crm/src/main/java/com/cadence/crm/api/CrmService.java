package com.cadence.crm.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public CRM port — clients, history aggregates, packages. */
public interface CrmService {

    String moduleName();

    /**
     * The client with this phone, created with the given name, locale and consent when there is none. For an existing
     * client the booking only adds: marketing consent {@code true} is recorded with {@code consentVersion}, {@code false}
     * never revokes it; the locale is set only when the client has none; the stored name is kept.
     */
    ClientView findOrCreate(String e164Phone, String displayName, String locale, boolean marketingConsent, String consentVersion);

    Optional<ClientView> findById(UUID clientId);

    Optional<ClientView> findByPhone(String e164Phone);

    List<ClientView> search(String query);

    /**
     * Search limited to the given clients (a master's own clients) that matches the display name only, never the
     * phone (a master does not see phones, TD-18); empty ids give an empty list. It is no phone oracle either: a query
     * with three or more digits finds nothing, and clients whose display name shows a phone (a client created without
     * a name is stored with the phone as its name) are left out.
     */
    List<ClientView> searchAmong(String query, Collection<UUID> clientIds);

    ClientView updateNotes(UUID clientId, String notes, String tags);

    /**
     * Withdraws marketing consent (PRODUCT_PLAN §10: by the booking link, or on request through the owner): sets it
     * {@code false} with the time and {@code consentVersion = "withdrawn:" + source}, and audits
     * {@code client.consent.withdraw} with the source. Idempotent. No marketing messages are sent yet, so for now it
     * only records the client's choice that later sends must honour.
     *
     * @throws com.cadence.platform.error.DomainException {@code CLIENT_NOT_FOUND} (404)
     */
    ClientView withdrawMarketingConsent(UUID clientId, String source);

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

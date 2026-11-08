package com.cadence.crm.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Public CRM port — clients, history aggregates, packages. */
public interface CrmService {

    /** Row cap of {@link #findByNameWords}: generous for one chat message, bounded for any tenant. */
    int NAME_WORD_MATCH_LIMIT = 2000;

    String moduleName();

    /**
     * The client with this phone, created with the given name, locale and consent when there is none. For an existing
     * client the booking only adds: marketing consent {@code true} is recorded with {@code consentVersion}, {@code false}
     * never revokes it; the locale is set only when the client has none; the stored name is kept. A client anonymized
     * while this waited for the row is left alone: the phone is free again and a new client is created.
     */
    ClientView findOrCreate(String e164Phone, String displayName, String locale, boolean marketingConsent, String consentVersion);

    Optional<ClientView> findById(UUID clientId);

    /** {@link #findById} of a client that is neither anonymized nor merged away; empty otherwise. */
    Optional<ClientView> findActiveById(UUID clientId);

    /** When the client was anonymized and which profile absorbed them; both null for an active client. {@code CLIENT_NOT_FOUND} (404). */
    ClientRetirement retirementOf(UUID clientId);

    /**
     * The client, who must not be anonymized or merged away, with a key-share row lock until the transaction ends: a write
     * that must not land on a retired client calls it first, and an anonymization ({@link #anonymize}) either waits for that
     * write and sees it, or wins and this answers 409.
     *
     * @throws com.cadence.platform.error.DomainException {@code CLIENT_NOT_FOUND} (404), {@code CLIENT_ANONYMIZED} (409)
     */
    ClientView requireActive(UUID clientId);

    Optional<ClientView> findByPhone(String e164Phone);

    List<ClientView> search(String query);

    /**
     * Search limited to the given clients (a master's own clients) that matches the display name only, never the
     * phone (a master does not see phones, TD-18); empty ids give an empty list. It is no phone oracle either: a query
     * with three or more digits finds nothing, and clients whose display name shows a phone (a client created without
     * a name is stored with the phone as its name) are left out.
     */
    List<ClientView> searchAmong(String query, Collection<UUID> clientIds);

    /**
     * A prefilter in one statement: clients (not anonymized) whose display name may have a word matching one of {@code words} by
     * {@link NameWords#matches}. Never narrower than that rule, in either case of the first letter, and possibly wider, so the
     * caller decides with it. At most {@link #NAME_WORD_MATCH_LIMIT} rows; a result of exactly the limit may hide further clients,
     * so a caller that depends on completeness must treat it as truncated.
     */
    List<ClientName> findByNameWords(Set<String> words);

    ClientView updateNotes(UUID clientId, String notes, String tags);

    /**
     * Withdraws marketing consent (PRODUCT_PLAN §10: by the booking link, or on request through the owner): sets it
     * {@code false} with the time and {@code consentVersion = "withdrawn:" + source}, and audits
     * {@code client.consent.withdraw} with the source. Idempotent. No marketing messages are sent yet, so for now it
     * only records the client's choice that later sends must honour.
     *
     * @throws com.cadence.platform.error.DomainException {@code CLIENT_NOT_FOUND} (404), {@code CLIENT_ANONYMIZED} (409), nothing audited
     */
    ClientView withdrawMarketingConsent(UUID clientId, String source);

    /**
     * Stores the Telegram chat the client agreed to receive service messages in (the client opened the bot through a one-time
     * link); replaces a previous chat. Audited as {@code client.telegram.link} with the source, never with the chat id.
     *
     * @throws com.cadence.platform.error.DomainException {@code CLIENT_NOT_FOUND} (404), {@code CLIENT_ANONYMIZED} (409)
     */
    ClientView linkTelegram(UUID clientId, String chatId, String source);

    /** Forgets the Telegram chat (the client blocked the bot); audited as {@code client.telegram.unlink}. Idempotent, silent for a retired client. */
    void unlinkTelegram(UUID clientId, String source);

    void recordCompletedVisit(UUID clientId, Instant when, int valueMinor);

    void recordNoShow(UUID clientId);

    void recordLateCancel(UUID clientId);

    /**
     * CRM half of erasing a client (PRODUCT_PLAN §10): tells the {@link ClientRetirementListener}s ({@code clientErasing}, also for every profile merged into this one, along a chain too), locks the profile against
     * bookings, and empties it: name and phone become placeholders, notes, tags, locale, Telegram chat and consent go; the
     * counters and first/last visit stay. The phone is free for a new client from the commit on. The caller
     * ({@code ClientAnonymizationService}) erases what the other modules hold in the same transaction.
     *
     * @throws com.cadence.platform.error.DomainException {@code CLIENT_NOT_FOUND} (404), {@code CLIENT_ALREADY_ANONYMIZED}
     *     (409) for a profile already anonymized or merged away
     */
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

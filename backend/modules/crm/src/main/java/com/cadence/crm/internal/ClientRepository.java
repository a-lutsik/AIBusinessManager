package com.cadence.crm.internal;

import com.cadence.crm.api.ClientName;
import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.crm.api.NameWords;
import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.CLIENT;

@Repository
class ClientRepository {

    /** {@link NameWords#key} of {@code {0}} in SQL; {@link NameMatchingSelfCheck} proves the two agree at startup. */
    static final String KEY_SQL = "regexp_replace(normalize(lower(translate({0}, 'İıΣς''’ʼ', 'iiσσ')), NFD), '["
            + NameWords.MARK_RANGES + "]', '', 'g')";

    private final DSLContext dsl;

    ClientRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    Optional<ClientView> findById(UUID id) {
        return dsl.selectFrom(CLIENT)
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)))
                .fetchOptional(this::toView);
    }

    /** The profiles merged directly into this one. */
    List<UUID> absorbedBy(UUID id) {
        return dsl.select(CLIENT.ID)
                .from(CLIENT)
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.MERGED_INTO_CLIENT_ID.eq(id)))
                .fetch(CLIENT.ID);
    }

    /** Merge-relevant state of a profile: the view plus whether it was anonymized or absorbed. */
    record ClientState(ClientView view, Instant anonymizedAt, UUID mergedIntoClientId) {
        boolean retired() {
            return anonymizedAt != null || mergedIntoClientId != null;
        }
    }

    Optional<ClientState> findState(UUID id) {
        return dsl.selectFrom(CLIENT)
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)))
                .fetchOptional(r -> new ClientState(
                        toView(r),
                        r.get(CLIENT.ANONYMIZED_AT) == null ? null : Utc.toInstant(r.get(CLIENT.ANONYMIZED_AT)),
                        r.get(CLIENT.MERGED_INTO_CLIENT_ID)
                ));
    }

    /**
     * {@link #findState} with a key-share lock until the transaction ends. It conflicts only with {@link #lockForUpdate}, so
     * it waits for an anonymization and reads the row as that left it, and blocks none of the ordinary profile updates.
     */
    Optional<ClientState> findStateForKeyShare(UUID id) {
        return dsl.selectFrom(CLIENT)
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)))
                .forKeyShare()
                .fetchOptional(r -> new ClientState(
                        toView(r),
                        r.get(CLIENT.ANONYMIZED_AT) == null ? null : Utc.toInstant(r.get(CLIENT.ANONYMIZED_AT)),
                        r.get(CLIENT.MERGED_INTO_CLIENT_ID)
                ));
    }

    /**
     * Locks both profiles until the transaction ends. The rows are locked in {@code ORDER BY id} order (the sort runs
     * below the lock step), so two merges touching the same pair in opposite directions cannot deadlock. Missing rows
     * are simply not locked; the caller validates existence afterwards.
     */
    void lockForUpdate(UUID one, UUID other) {
        dsl.select(CLIENT.ID)
                .from(CLIENT)
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.in(one, other)))
                .orderBy(CLIENT.ID.asc())
                .forUpdate()
                .fetch();
    }

    Optional<ClientView> findByPhone(String e164) {
        return dsl.selectFrom(CLIENT)
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.NORMALIZED_PHONE.eq(e164)))
                .fetchOptional(this::toView);
    }

    /** Owner search: names and phones, at most {@link #SEARCH_LIMIT} rows. */
    List<ClientView> search(String query) {
        return search(query, null, true, SEARCH_LIMIT);
    }

    static final int SEARCH_LIMIT = 100;

    /**
     * {@code clientIds}, when not null, limits the search to those clients before the result limit applies;
     * {@code matchPhone} also matches the query against the phone, otherwise only the display name is matched.
     */
    List<ClientView> search(String query, Collection<UUID> clientIds, boolean matchPhone, int limit) {
        var condition = TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ANONYMIZED_AT.isNull());
        if (clientIds != null) {
            condition = condition.and(CLIENT.ID.in(clientIds));
        }
        if (query != null && !query.isBlank()) {
            String like = "%" + query.trim() + "%";
            var match = CLIENT.DISPLAY_NAME.likeIgnoreCase(like);
            condition = condition.and(matchPhone ? match.or(CLIENT.NORMALIZED_PHONE.like(like)) : match);
        }
        return dsl.selectFrom(CLIENT)
                .where(condition)
                .orderBy(CLIENT.LAST_VISIT_AT.desc().nullsLast(), CLIENT.DISPLAY_NAME.asc())
                .limit(limit)
                .fetch(this::toView);
    }

    /**
     * The prefilter of {@link NameWords#stems}: a word of the display name equals a stem of a typed word, alone or plus an ending,
     * in one statement. Never narrower than {@link NameWords#matches}; the caller decides. No index: one read per chat message over
     * the tenant's clients, a table small per tenant.
     */
    List<ClientName> findByNameWords(Collection<String> normalisedWords) {
        Set<String> stems = new LinkedHashSet<>();
        normalisedWords.forEach(word -> stems.addAll(NameWords.stems(word)));
        return dsl.select(CLIENT.ID, CLIENT.DISPLAY_NAME)
                .from(CLIENT)
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID)
                        .and(CLIENT.ANONYMIZED_AT.isNull())
                        .and(DSL.condition(
                                "exists (select 1 from unnest(regexp_split_to_array(" + KEY_SQL + ", '[^[:alpha:]]+')) as n(word)"
                                        + " where n.word = any(cast({1} as text[]))"
                                        + " or exists (select 1 from unnest(cast({2} as text[])) as e(tail)"
                                        + " where right(n.word, char_length(e.tail)) = e.tail"
                                        + " and left(n.word, -char_length(e.tail)) = any(cast({1} as text[]))))",
                                CLIENT.DISPLAY_NAME,
                                DSL.val(stems.toArray(new String[0])),
                                DSL.val(NameWords.storedEndings().toArray(new String[0])))))
                .orderBy(CLIENT.LAST_VISIT_AT.desc().nullsLast(), CLIENT.DISPLAY_NAME.asc())
                .limit(CrmService.NAME_WORD_MATCH_LIMIT)
                .fetch(r -> new ClientName(r.get(CLIENT.ID), r.get(CLIENT.DISPLAY_NAME)));
    }

    ClientView insert(ClientView client) {
        UUID tenantId = TenantContext.require();
        dsl.insertInto(CLIENT)
                .set(CLIENT.ID, client.id())
                .set(CLIENT.TENANT_ID, tenantId)
                .set(CLIENT.NORMALIZED_PHONE, client.normalizedPhone())
                .set(CLIENT.DISPLAY_NAME, client.displayName())
                .set(CLIENT.NOTES, client.notes())
                .set(CLIENT.TAGS, client.tags())
                .set(CLIENT.PREFERRED_LOCALE, client.preferredLocale())
                .set(CLIENT.TELEGRAM_CHAT_ID, client.telegramChatId())
                .set(CLIENT.MARKETING_CONSENT, client.marketingConsent())
                .set(CLIENT.CREATED_AT, Utc.toLocal(Instant.now()))
                .set(CLIENT.COMPLETED_VISIT_COUNT, client.completedVisitCount())
                .set(CLIENT.NO_SHOW_COUNT, client.noShowCount())
                .set(CLIENT.LATE_CANCEL_COUNT, client.lateCancelCount())
                .set(CLIENT.COMPLETED_VALUE_MINOR, client.completedValueMinor())
                .execute();
        return client;
    }

    /**
     * Returns the rows written: 0 for an anonymized profile. The condition is checked again after waiting for the row lock, so a
     * caller that read the profile before an anonymization committed cannot write the name back.
     */
    int updateProfile(UUID id, String name, String notes, String tags, String locale, Boolean consent, String consentVersion) {
        var update = dsl.update(CLIENT)
                .set(CLIENT.DISPLAY_NAME, name);
        if (notes != null) {
            update = update.set(CLIENT.NOTES, notes);
        }
        if (tags != null) {
            update = update.set(CLIENT.TAGS, tags);
        }
        if (locale != null) {
            update = update.set(CLIENT.PREFERRED_LOCALE, locale);
        }
        if (consent != null) {
            update = update.set(CLIENT.MARKETING_CONSENT, consent)
                    .set(CLIENT.CONSENT_VERSION, consentVersion)
                    .set(CLIENT.CONSENT_AT, Utc.toLocal(Instant.now()));
        }
        return update.where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID)
                        .and(CLIENT.ID.eq(id))
                        .and(CLIENT.ANONYMIZED_AT.isNull()))
                .execute();
    }

    /**
     * Records a completed visit. First/last visit are the earliest/latest completed service start, so completing an
     * older visit later (owner status correction) never moves them the wrong way.
     */
    void bumpCompleted(UUID id, Instant when, int valueMinor) {
        var at = Utc.toLocal(when);
        dsl.update(CLIENT)
                .set(CLIENT.COMPLETED_VISIT_COUNT, CLIENT.COMPLETED_VISIT_COUNT.plus(1))
                .set(CLIENT.COMPLETED_VALUE_MINOR, CLIENT.COMPLETED_VALUE_MINOR.plus(valueMinor))
                .set(CLIENT.LAST_VISIT_AT, DSL.greatest(DSL.coalesce(CLIENT.LAST_VISIT_AT, DSL.val(at)), DSL.val(at)))
                .set(CLIENT.FIRST_VISIT_AT, DSL.least(DSL.coalesce(CLIENT.FIRST_VISIT_AT, DSL.val(at)), DSL.val(at)))
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)))
                .execute();
    }

    void bumpNoShow(UUID id) {
        dsl.update(CLIENT)
                .set(CLIENT.NO_SHOW_COUNT, CLIENT.NO_SHOW_COUNT.plus(1))
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)))
                .execute();
    }

    void bumpLateCancel(UUID id) {
        dsl.update(CLIENT)
                .set(CLIENT.LATE_CANCEL_COUNT, CLIENT.LATE_CANCEL_COUNT.plus(1))
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)))
                .execute();
    }

    /** Owner correction NO_SHOW to COMPLETED: one recorded no-show less, never below zero. */
    void revertNoShow(UUID id) {
        dsl.update(CLIENT)
                .set(CLIENT.NO_SHOW_COUNT, DSL.greatest(CLIENT.NO_SHOW_COUNT.minus(1), DSL.inline(0)))
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)))
                .execute();
    }

    /**
     * Owner correction COMPLETED to NO_SHOW: one completed visit and its value less (floored at zero); the first/last
     * visit timestamps become the values recomputed from the remaining completed visits.
     */
    void revertCompleted(UUID id, int valueMinor, Instant firstCompletedAt, Instant lastCompletedAt) {
        dsl.update(CLIENT)
                .set(CLIENT.COMPLETED_VISIT_COUNT, DSL.greatest(CLIENT.COMPLETED_VISIT_COUNT.minus(1), DSL.inline(0)))
                .set(CLIENT.COMPLETED_VALUE_MINOR, DSL.greatest(CLIENT.COMPLETED_VALUE_MINOR.minus(valueMinor), DSL.inline(0)))
                .set(CLIENT.FIRST_VISIT_AT, firstCompletedAt == null ? null : Utc.toLocal(firstCompletedAt))
                .set(CLIENT.LAST_VISIT_AT, lastCompletedAt == null ? null : Utc.toLocal(lastCompletedAt))
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)))
                .execute();
    }

    /** Overwrites the survivor's aggregates, notes and tags with the already merged values. Consent is untouched. */
    void applyMerge(
            UUID id,
            int completedVisitCount,
            int noShowCount,
            int lateCancelCount,
            int completedValueMinor,
            Instant firstVisitAt,
            Instant lastVisitAt,
            String notes,
            String tags
    ) {
        dsl.update(CLIENT)
                .set(CLIENT.COMPLETED_VISIT_COUNT, completedVisitCount)
                .set(CLIENT.NO_SHOW_COUNT, noShowCount)
                .set(CLIENT.LATE_CANCEL_COUNT, lateCancelCount)
                .set(CLIENT.COMPLETED_VALUE_MINOR, completedValueMinor)
                .set(CLIENT.FIRST_VISIT_AT, firstVisitAt == null ? null : Utc.toLocal(firstVisitAt))
                .set(CLIENT.LAST_VISIT_AT, lastVisitAt == null ? null : Utc.toLocal(lastVisitAt))
                .set(CLIENT.NOTES, notes)
                .set(CLIENT.TAGS, tags)
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)))
                .execute();
    }

    /**
     * Retires the absorbed profile: anonymized, pointing at the survivor, with its aggregates and tags cleared so the
     * folded-in numbers are not counted twice.
     */
    void markMerged(UUID absorbedId, UUID survivorId) {
        anonymize(absorbedId);
        dsl.update(CLIENT)
                .set(CLIENT.MERGED_INTO_CLIENT_ID, survivorId)
                .set(CLIENT.COMPLETED_VISIT_COUNT, 0)
                .set(CLIENT.NO_SHOW_COUNT, 0)
                .set(CLIENT.LATE_CANCEL_COUNT, 0)
                .set(CLIENT.COMPLETED_VALUE_MINOR, 0)
                .set(CLIENT.FIRST_VISIT_AT, (LocalDateTime) null)
                .set(CLIENT.LAST_VISIT_AT, (LocalDateTime) null)
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(absorbedId)))
                .execute();
    }

    void anonymize(UUID id) {
        dsl.update(CLIENT)
                .set(CLIENT.DISPLAY_NAME, "anonymized")
                .set(CLIENT.NORMALIZED_PHONE, AnonymizedPhone.forClient(id))
                .set(CLIENT.NOTES, (String) null)
                .set(CLIENT.TAGS, (String) null)
                .set(CLIENT.PREFERRED_LOCALE, (String) null)
                .set(CLIENT.TELEGRAM_CHAT_ID, (String) null)
                .set(CLIENT.MARKETING_CONSENT, false)
                .set(CLIENT.CONSENT_VERSION, (String) null)
                .set(CLIENT.CONSENT_AT, (LocalDateTime) null)
                .set(CLIENT.ANONYMIZED_AT, Utc.toLocal(Instant.now()))
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)))
                .execute();
    }

    private ClientView toView(Record r) {
        return new ClientView(
                r.get(CLIENT.ID),
                r.get(CLIENT.ANONYMIZED_AT) == null ? r.get(CLIENT.NORMALIZED_PHONE) : null,
                r.get(CLIENT.DISPLAY_NAME),
                r.get(CLIENT.NOTES),
                r.get(CLIENT.TAGS),
                r.get(CLIENT.PREFERRED_LOCALE),
                r.get(CLIENT.TELEGRAM_CHAT_ID),
                Boolean.TRUE.equals(r.get(CLIENT.MARKETING_CONSENT)),
                r.get(CLIENT.FIRST_VISIT_AT) == null ? null : Utc.toInstant(r.get(CLIENT.FIRST_VISIT_AT)),
                r.get(CLIENT.LAST_VISIT_AT) == null ? null : Utc.toInstant(r.get(CLIENT.LAST_VISIT_AT)),
                r.get(CLIENT.COMPLETED_VISIT_COUNT),
                r.get(CLIENT.NO_SHOW_COUNT),
                r.get(CLIENT.LATE_CANCEL_COUNT),
                r.get(CLIENT.COMPLETED_VALUE_MINOR)
        );
    }
}

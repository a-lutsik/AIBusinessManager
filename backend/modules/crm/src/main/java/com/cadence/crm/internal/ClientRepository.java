package com.cadence.crm.internal;

import com.cadence.crm.api.ClientView;
import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.CLIENT;

@Repository
class ClientRepository {

    private final DSLContext dsl;

    ClientRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    Optional<ClientView> findById(UUID id) {
        return dsl.selectFrom(CLIENT)
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)))
                .fetchOptional(this::toView);
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

    List<ClientView> search(String query) {
        return search(query, null);
    }

    /** {@code clientIds}, when not null, limits the search to those clients before the result limit applies. */
    List<ClientView> search(String query, Collection<UUID> clientIds) {
        var condition = TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ANONYMIZED_AT.isNull());
        if (clientIds != null) {
            condition = condition.and(CLIENT.ID.in(clientIds));
        }
        if (query != null && !query.isBlank()) {
            String like = "%" + query.trim() + "%";
            condition = condition.and(CLIENT.DISPLAY_NAME.likeIgnoreCase(like)
                    .or(CLIENT.NORMALIZED_PHONE.like("%" + query.trim() + "%")));
        }
        return dsl.selectFrom(CLIENT)
                .where(condition)
                .orderBy(CLIENT.LAST_VISIT_AT.desc().nullsLast(), CLIENT.DISPLAY_NAME.asc())
                .limit(100)
                .fetch(this::toView);
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

    void updateProfile(UUID id, String name, String notes, String tags, String locale, Boolean consent, String consentVersion) {
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
        update.where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id))).execute();
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
                .set(CLIENT.TAGS, (String) null)
                .set(CLIENT.COMPLETED_VISIT_COUNT, 0)
                .set(CLIENT.NO_SHOW_COUNT, 0)
                .set(CLIENT.LATE_CANCEL_COUNT, 0)
                .set(CLIENT.COMPLETED_VALUE_MINOR, 0)
                .set(CLIENT.FIRST_VISIT_AT, (java.time.LocalDateTime) null)
                .set(CLIENT.LAST_VISIT_AT, (java.time.LocalDateTime) null)
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(absorbedId)))
                .execute();
    }

    void anonymize(UUID id) {
        dsl.update(CLIENT)
                .set(CLIENT.DISPLAY_NAME, "anonymized")
                .set(CLIENT.NORMALIZED_PHONE, AnonymizedPhone.forClient(id))
                .set(CLIENT.NOTES, (String) null)
                .set(CLIENT.TELEGRAM_CHAT_ID, (String) null)
                .set(CLIENT.ANONYMIZED_AT, Utc.toLocal(Instant.now()))
                .where(TenantAwareDsl.tenantEquals(CLIENT.TENANT_ID).and(CLIENT.ID.eq(id)))
                .execute();
    }

    private ClientView toView(Record r) {
        return new ClientView(
                r.get(CLIENT.ID),
                r.get(CLIENT.NORMALIZED_PHONE),
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

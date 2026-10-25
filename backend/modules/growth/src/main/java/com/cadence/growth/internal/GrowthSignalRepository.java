package com.cadence.growth.internal;

import com.cadence.growth.api.GrowthSignalView;
import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.GROWTH_SIGNAL;

/**
 * {@code growth_signal} (plain table since V13): one row per {@code (tenant_id, dedupe_key)}. A recalculation that
 * finds the same signal again updates evidence, severity, action payload and {@code updated_at} in place, re-opens it
 * ({@code resolved_at = NULL}) and never touches {@code dismissed_at}, so a dismissed signal stays dismissed for its
 * period. A signal of the period whose rule no longer fires is resolved (V14, review m4).
 */
@Repository
class GrowthSignalRepository {

    /** A signal as a rule produced it; {@code dedupeKey = signalKey:(tenant|specialistId):YYYY-MM}. */
    record Draft(String dedupeKey, String signalKey, UUID specialistId, String period, String title, String evidence,
                 String suggestedAction, String severity, String actionType, String actionPayload) {
    }

    private final DSLContext dsl;

    GrowthSignalRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    void upsert(Draft draft, Instant at) {
        var now = Utc.toLocal(at);
        String evidence = MetricSnapshotRepository.truncate(draft.evidence(), 1024);
        dsl.insertInto(GROWTH_SIGNAL)
                .set(GROWTH_SIGNAL.ID, UUID.randomUUID())
                .set(GROWTH_SIGNAL.TENANT_ID, TenantContext.require())
                .set(GROWTH_SIGNAL.DEDUPE_KEY, draft.dedupeKey())
                .set(GROWTH_SIGNAL.SIGNAL_KEY, draft.signalKey())
                .set(GROWTH_SIGNAL.SPECIALIST_ID, draft.specialistId())
                .set(GROWTH_SIGNAL.PERIOD, draft.period())
                .set(GROWTH_SIGNAL.TITLE, draft.title())
                .set(GROWTH_SIGNAL.EVIDENCE, evidence)
                .set(GROWTH_SIGNAL.SUGGESTED_ACTION, draft.suggestedAction())
                .set(GROWTH_SIGNAL.SEVERITY, draft.severity())
                .set(GROWTH_SIGNAL.ACTION_TYPE, draft.actionType())
                .set(GROWTH_SIGNAL.ACTION_PAYLOAD, draft.actionPayload())
                .set(GROWTH_SIGNAL.CREATED_AT, now)
                .set(GROWTH_SIGNAL.UPDATED_AT, now)
                .onConflict(GROWTH_SIGNAL.TENANT_ID, GROWTH_SIGNAL.DEDUPE_KEY)
                .doUpdate()
                .set(GROWTH_SIGNAL.EVIDENCE, evidence)
                .set(GROWTH_SIGNAL.SEVERITY, draft.severity())
                .set(GROWTH_SIGNAL.ACTION_PAYLOAD, draft.actionPayload())
                .set(GROWTH_SIGNAL.RESOLVED_AT, (java.time.LocalDateTime) null)
                .set(GROWTH_SIGNAL.UPDATED_AT, now)
                .execute();
    }

    /** Resolves the tenant-level signals of {@code period} whose key is not in {@code firing}; returns how many. */
    int resolveAllBut(String period, java.util.Set<String> firing, Instant at) {
        var now = Utc.toLocal(at);
        return dsl.update(GROWTH_SIGNAL)
                .set(GROWTH_SIGNAL.RESOLVED_AT, now)
                .set(GROWTH_SIGNAL.UPDATED_AT, now)
                .where(TenantAwareDsl.tenantEquals(GROWTH_SIGNAL.TENANT_ID)
                        .and(GROWTH_SIGNAL.PERIOD.eq(period))
                        .and(GROWTH_SIGNAL.SPECIALIST_ID.isNull())
                        .and(GROWTH_SIGNAL.RESOLVED_AT.isNull())
                        .and(firing.isEmpty() ? org.jooq.impl.DSL.trueCondition() : GROWTH_SIGNAL.SIGNAL_KEY.notIn(firing)))
                .execute();
    }

    /** Signals of {@code period} that are neither resolved nor dismissed. */
    List<GrowthSignalView> open(String period, int limit) {
        return dsl.selectFrom(GROWTH_SIGNAL)
                .where(TenantAwareDsl.tenantEquals(GROWTH_SIGNAL.TENANT_ID)
                        .and(GROWTH_SIGNAL.PERIOD.eq(period))
                        .and(GROWTH_SIGNAL.RESOLVED_AT.isNull())
                        .and(GROWTH_SIGNAL.DISMISSED_AT.isNull()))
                .orderBy(GROWTH_SIGNAL.CREATED_AT.desc(), GROWTH_SIGNAL.ID)
                .limit(limit)
                .fetch(GrowthSignalRepository::toView);
    }

    Optional<GrowthSignalView> find(UUID id) {
        return dsl.selectFrom(GROWTH_SIGNAL)
                .where(TenantAwareDsl.tenantEquals(GROWTH_SIGNAL.TENANT_ID).and(GROWTH_SIGNAL.ID.eq(id)))
                .fetchOptional(GrowthSignalRepository::toView);
    }

    void dismiss(UUID id, Instant at) {
        dsl.update(GROWTH_SIGNAL)
                .set(GROWTH_SIGNAL.DISMISSED_AT, Utc.toLocal(at))
                .where(TenantAwareDsl.tenantEquals(GROWTH_SIGNAL.TENANT_ID).and(GROWTH_SIGNAL.ID.eq(id)))
                .execute();
    }

    private static GrowthSignalView toView(Record r) {
        Instant dismissed = r.get(GROWTH_SIGNAL.DISMISSED_AT) == null ? null : Utc.toInstant(r.get(GROWTH_SIGNAL.DISMISSED_AT));
        return new GrowthSignalView(
                r.get(GROWTH_SIGNAL.ID),
                r.get(GROWTH_SIGNAL.SIGNAL_KEY),
                r.get(GROWTH_SIGNAL.TITLE),
                r.get(GROWTH_SIGNAL.EVIDENCE),
                r.get(GROWTH_SIGNAL.SUGGESTED_ACTION),
                r.get(GROWTH_SIGNAL.SEVERITY) == null ? "INFO" : r.get(GROWTH_SIGNAL.SEVERITY),
                r.get(GROWTH_SIGNAL.ACTION_TYPE),
                r.get(GROWTH_SIGNAL.ACTION_PAYLOAD),
                Utc.toInstant(r.get(GROWTH_SIGNAL.CREATED_AT)),
                dismissed
        );
    }
}

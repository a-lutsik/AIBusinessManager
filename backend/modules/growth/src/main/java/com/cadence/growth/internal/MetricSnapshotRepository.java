package com.cadence.growth.internal;

import com.cadence.growth.api.MetricView;
import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.time.Utc;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.METRIC_SNAPSHOT;

/** {@code metric_snapshot}: one row per metric, window and slice of a recalculation; {@code created_at} = its as-of T. */
@Repository
class MetricSnapshotRepository {

    private final DSLContext dsl;

    MetricSnapshotRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    void insert(List<MetricView> metrics, LocalDate windowStart, LocalDate windowEnd, Instant asOf) {
        for (MetricView metric : metrics) {
            dsl.insertInto(METRIC_SNAPSHOT)
                    .set(METRIC_SNAPSHOT.ID, UUID.randomUUID())
                    .set(METRIC_SNAPSHOT.TENANT_ID, TenantContext.require())
                    .set(METRIC_SNAPSHOT.SPECIALIST_ID, metric.specialistId())
                    .set(METRIC_SNAPSHOT.WINDOW_START, windowStart)
                    .set(METRIC_SNAPSHOT.WINDOW_END, windowEnd)
                    .set(METRIC_SNAPSHOT.METRIC_KEY, metric.key())
                    .set(METRIC_SNAPSHOT.VALUE_NUMERIC, metric.value())
                    .set(METRIC_SNAPSHOT.SAMPLE_SIZE, metric.sampleSize())
                    .set(METRIC_SNAPSHOT.INSUFFICIENT_DATA, metric.insufficientData())
                    .set(METRIC_SNAPSHOT.EXPLANATION, truncate(metric.explanation(), 1024))
                    .set(METRIC_SNAPSHOT.CREATED_AT, Utc.toLocal(asOf))
                    .execute();
        }
    }

    private static Condition slice(UUID specialistId) {
        return specialistId == null ? METRIC_SNAPSHOT.SPECIALIST_ID.isNull() : METRIC_SNAPSHOT.SPECIALIST_ID.eq(specialistId);
    }

    /** Per metric key, the row of the most recent window start, latest recalculation first. */
    Map<String, Record> latestOfCurrentWindow(UUID specialistId) {
        var rows = dsl.selectFrom(METRIC_SNAPSHOT)
                .where(TenantAwareDsl.tenantEquals(METRIC_SNAPSHOT.TENANT_ID).and(slice(specialistId)))
                .orderBy(METRIC_SNAPSHOT.WINDOW_START.desc(), METRIC_SNAPSHOT.CREATED_AT.desc())
                .limit(200)
                .fetch();
        Map<String, Record> latest = new LinkedHashMap<>();
        for (Record r : rows) {
            latest.putIfAbsent(r.get(METRIC_SNAPSHOT.METRIC_KEY), r);
        }
        return latest;
    }

    /** Latest sufficient value of {@code key} for exactly the window {@code start}..{@code end}, or null. */
    Double latestSufficientValue(String key, UUID specialistId, LocalDate start, LocalDate end) {
        return dsl.select(METRIC_SNAPSHOT.VALUE_NUMERIC)
                .from(METRIC_SNAPSHOT)
                .where(TenantAwareDsl.tenantEquals(METRIC_SNAPSHOT.TENANT_ID)
                        .and(METRIC_SNAPSHOT.METRIC_KEY.eq(key))
                        .and(slice(specialistId))
                        .and(METRIC_SNAPSHOT.WINDOW_START.eq(start))
                        .and(METRIC_SNAPSHOT.WINDOW_END.eq(end))
                        .and(METRIC_SNAPSHOT.INSUFFICIENT_DATA.isFalse())
                        .and(METRIC_SNAPSHOT.VALUE_NUMERIC.isNotNull()))
                .orderBy(METRIC_SNAPSHOT.CREATED_AT.desc())
                .limit(1)
                .fetchOne(METRIC_SNAPSHOT.VALUE_NUMERIC);
    }

    /** Per specialist, the latest row of {@code key} for the window {@code start}..{@code end}. */
    Map<UUID, Record> latestBySpecialist(String key, LocalDate start, LocalDate end) {
        var rows = dsl.selectFrom(METRIC_SNAPSHOT)
                .where(TenantAwareDsl.tenantEquals(METRIC_SNAPSHOT.TENANT_ID)
                        .and(METRIC_SNAPSHOT.METRIC_KEY.eq(key))
                        .and(METRIC_SNAPSHOT.SPECIALIST_ID.isNotNull())
                        .and(METRIC_SNAPSHOT.WINDOW_START.eq(start))
                        .and(METRIC_SNAPSHOT.WINDOW_END.eq(end)))
                .orderBy(METRIC_SNAPSHOT.CREATED_AT.desc())
                .fetch();
        Map<UUID, Record> latest = new LinkedHashMap<>();
        for (Record r : rows) {
            latest.putIfAbsent(r.get(METRIC_SNAPSHOT.SPECIALIST_ID), r);
        }
        return latest;
    }

    /** T of the tenant's latest recalculation, if any. */
    java.util.Optional<Instant> newestAsOf() {
        var max = org.jooq.impl.DSL.max(METRIC_SNAPSHOT.CREATED_AT);
        var at = dsl.select(max).from(METRIC_SNAPSHOT)
                .where(TenantAwareDsl.tenantEquals(METRIC_SNAPSHOT.TENANT_ID))
                .fetchOne(max);
        return java.util.Optional.ofNullable(at).map(Utc::toInstant);
    }

    static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}

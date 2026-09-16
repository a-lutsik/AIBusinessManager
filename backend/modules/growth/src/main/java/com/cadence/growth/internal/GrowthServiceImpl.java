package com.cadence.growth.internal;

import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.BookingService;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.catalog.api.WeeklyInterval;
import com.cadence.growth.api.GrowthService;
import com.cadence.growth.api.GrowthSignalView;
import com.cadence.growth.api.MetricDefinitionView;
import com.cadence.growth.api.MetricDefinitions;
import com.cadence.growth.api.MetricView;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.persistence.TenantAwareDsl;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.cadence.platform.time.Utc;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.cadence.platform.jooq.Tables.GROWTH_SIGNAL;
import static com.cadence.platform.jooq.Tables.METRIC_SNAPSHOT;

@Service
public class GrowthServiceImpl implements GrowthService {

    private final BookingService bookingService;
    private final CatalogService catalogService;
    private final TenantDirectory tenants;
    private final DSLContext dsl;
    private final MetricsCalculator metricsCalculator;

    public GrowthServiceImpl(
            BookingService bookingService,
            CatalogService catalogService,
            TenantDirectory tenants,
            DSLContext dsl,
            MetricsCalculator metricsCalculator
    ) {
        this.bookingService = bookingService;
        this.catalogService = catalogService;
        this.tenants = tenants;
        this.dsl = dsl;
        this.metricsCalculator = metricsCalculator;
    }

    @Override
    public String moduleName() {
        return "growth";
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricView> currentMetrics(UUID specialistId) {
        var rows = dsl.selectFrom(METRIC_SNAPSHOT)
                .where(TenantAwareDsl.tenantEquals(METRIC_SNAPSHOT.TENANT_ID)
                        .and(specialistId == null
                                ? METRIC_SNAPSHOT.SPECIALIST_ID.isNull()
                                : METRIC_SNAPSHOT.SPECIALIST_ID.eq(specialistId)))
                .orderBy(METRIC_SNAPSHOT.CREATED_AT.desc())
                .limit(200)
                .fetch();
        if (rows.isEmpty()) {
            return placeholders(specialistId);
        }
        Map<String, org.jooq.Record> latestByKey = new LinkedHashMap<>();
        for (org.jooq.Record r : rows) {
            String key = r.get(METRIC_SNAPSHOT.METRIC_KEY);
            latestByKey.putIfAbsent(key, r);
        }
        List<MetricView> result = new ArrayList<>();
        for (org.jooq.Record r : latestByKey.values()) {
            LocalDate windowStart = r.get(METRIC_SNAPSHOT.WINDOW_START);
            LocalDate windowEnd = r.get(METRIC_SNAPSHOT.WINDOW_END);
            String key = r.get(METRIC_SNAPSHOT.METRIC_KEY);
            Double value = r.get(METRIC_SNAPSHOT.VALUE_NUMERIC);
            Double delta = deltaMoM(key, specialistId, windowStart, windowEnd, value);
            List<MetricView.SpecialistBreakdown> breakdown = specialistId == null
                    ? breakdownFor(key, windowStart, windowEnd)
                    : List.of();
            result.add(new MetricView(
                    key,
                    value,
                    r.get(METRIC_SNAPSHOT.SAMPLE_SIZE),
                    Boolean.TRUE.equals(r.get(METRIC_SNAPSHOT.INSUFFICIENT_DATA)),
                    r.get(METRIC_SNAPSHOT.EXPLANATION),
                    r.get(METRIC_SNAPSHOT.SPECIALIST_ID),
                    windowStart,
                    windowEnd,
                    MetricDefinitions.unitFor(key),
                    delta,
                    breakdown
            ));
        }
        return result;
    }

    private List<MetricView> placeholders(UUID specialistId) {
        return MetricDefinitions.all().stream()
                .map(d -> MetricView.basic(d.key(), null, 0, true, "Will appear after Metrics Core recalculation", specialistId))
                .toList();
    }

    private Double deltaMoM(
            String key,
            UUID specialistId,
            LocalDate windowStart,
            LocalDate windowEnd,
            Double currentValue
    ) {
        if (windowStart == null || windowEnd == null || currentValue == null) {
            return null;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(windowStart, windowEnd) + 1;
        LocalDate priorEnd = windowStart.minusDays(1);
        LocalDate priorStart = priorEnd.minusDays(days - 1);
        Double prior = dsl.select(METRIC_SNAPSHOT.VALUE_NUMERIC)
                .from(METRIC_SNAPSHOT)
                .where(TenantAwareDsl.tenantEquals(METRIC_SNAPSHOT.TENANT_ID)
                        .and(METRIC_SNAPSHOT.METRIC_KEY.eq(key))
                        .and(specialistId == null
                                ? METRIC_SNAPSHOT.SPECIALIST_ID.isNull()
                                : METRIC_SNAPSHOT.SPECIALIST_ID.eq(specialistId))
                        .and(METRIC_SNAPSHOT.WINDOW_START.eq(priorStart))
                        .and(METRIC_SNAPSHOT.WINDOW_END.eq(priorEnd))
                        .and(METRIC_SNAPSHOT.INSUFFICIENT_DATA.isFalse())
                        .and(METRIC_SNAPSHOT.VALUE_NUMERIC.isNotNull()))
                .orderBy(METRIC_SNAPSHOT.CREATED_AT.desc())
                .limit(1)
                .fetchOne(METRIC_SNAPSHOT.VALUE_NUMERIC);
        if (prior == null || prior == 0.0) {
            prior = dsl.select(METRIC_SNAPSHOT.VALUE_NUMERIC)
                    .from(METRIC_SNAPSHOT)
                    .where(TenantAwareDsl.tenantEquals(METRIC_SNAPSHOT.TENANT_ID)
                            .and(METRIC_SNAPSHOT.METRIC_KEY.eq(key))
                            .and(specialistId == null
                                    ? METRIC_SNAPSHOT.SPECIALIST_ID.isNull()
                                    : METRIC_SNAPSHOT.SPECIALIST_ID.eq(specialistId))
                            .and(METRIC_SNAPSHOT.WINDOW_END.lt(windowStart))
                            .and(METRIC_SNAPSHOT.INSUFFICIENT_DATA.isFalse())
                            .and(METRIC_SNAPSHOT.VALUE_NUMERIC.isNotNull()))
                    .orderBy(METRIC_SNAPSHOT.WINDOW_END.desc(), METRIC_SNAPSHOT.CREATED_AT.desc())
                    .limit(1)
                    .fetchOne(METRIC_SNAPSHOT.VALUE_NUMERIC);
        }
        if (prior == null || prior == 0.0) {
            return null;
        }
        return (currentValue - prior) / prior;
    }

    private List<MetricView.SpecialistBreakdown> breakdownFor(
            String key,
            LocalDate windowStart,
            LocalDate windowEnd
    ) {
        Map<UUID, SpecialistView> specialists = catalogService.listSpecialists(false).stream()
                .collect(Collectors.toMap(SpecialistView::id, s -> s, (a, b) -> a));
        var rows = dsl.selectFrom(METRIC_SNAPSHOT)
                .where(TenantAwareDsl.tenantEquals(METRIC_SNAPSHOT.TENANT_ID)
                        .and(METRIC_SNAPSHOT.METRIC_KEY.eq(key))
                        .and(METRIC_SNAPSHOT.SPECIALIST_ID.isNotNull())
                        .and(windowStart == null ? METRIC_SNAPSHOT.WINDOW_START.isNotNull() : METRIC_SNAPSHOT.WINDOW_START.eq(windowStart))
                        .and(windowEnd == null ? METRIC_SNAPSHOT.WINDOW_END.isNotNull() : METRIC_SNAPSHOT.WINDOW_END.eq(windowEnd)))
                .orderBy(METRIC_SNAPSHOT.CREATED_AT.desc())
                .fetch();
        Map<UUID, org.jooq.Record> latest = new LinkedHashMap<>();
        for (org.jooq.Record r : rows) {
            latest.putIfAbsent(r.get(METRIC_SNAPSHOT.SPECIALIST_ID), r);
        }
        return latest.entrySet().stream()
                .sorted(Comparator.comparing(e -> {
                    SpecialistView s = specialists.get(e.getKey());
                    return s == null ? e.getKey().toString() : s.displayName();
                }))
                .map(e -> {
                    SpecialistView s = specialists.get(e.getKey());
                    org.jooq.Record r = e.getValue();
                    return new MetricView.SpecialistBreakdown(
                            e.getKey(),
                            s == null ? e.getKey().toString() : s.displayName(),
                            r.get(METRIC_SNAPSHOT.VALUE_NUMERIC),
                            Boolean.TRUE.equals(r.get(METRIC_SNAPSHOT.INSUFFICIENT_DATA))
                    );
                })
                .toList();
    }

    @Override
    @Transactional
    public List<MetricView> recalculate(LocalDate windowStart, LocalDate windowEnd) {
        var tenant = tenants.requireById(TenantContext.require());
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        Instant from = windowStart.atStartOfDay(zone).toInstant();
        Instant to = windowEnd.plusDays(1).atStartOfDay(zone).toInstant();
        List<AppointmentView> visits = bookingService.calendar(from, to, null);
        List<SpecialistView> specialists = catalogService.listSpecialists(true);
        Map<UUID, List<WeeklyInterval>> weeklyBySpecialist = catalogService.allWeeklySchedules().stream()
                .collect(Collectors.groupingBy(WeeklyInterval::specialistId));
        List<WeeklyInterval> weekly = weeklyBySpecialist.values().stream()
                .flatMap(List::stream)
                .toList();
        List<MetricView> tenantMetrics = metricsCalculator.compute(visits, weekly, zone, windowStart, windowEnd, null)
                .stream()
                .map(m -> m.withWindow(windowStart, windowEnd))
                .toList();
        persist(tenantMetrics, windowStart, windowEnd);
        for (SpecialistView specialist : specialists) {
            List<AppointmentView> subset = visits.stream().filter(v -> v.specialistId().equals(specialist.id())).toList();
            List<WeeklyInterval> sw = weeklyBySpecialist.getOrDefault(specialist.id(), List.of());
            persist(
                    metricsCalculator.compute(subset, sw, zone, windowStart, windowEnd, specialist.id()).stream()
                            .map(m -> m.withWindow(windowStart, windowEnd))
                            .toList(),
                    windowStart,
                    windowEnd
            );
        }
        persistSignals(tenantMetrics, visits);
        return currentMetrics(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GrowthSignalView> signals() {
        List<GrowthSignalView> stored = dsl.selectFrom(GROWTH_SIGNAL)
                .where(TenantAwareDsl.tenantEquals(GROWTH_SIGNAL.TENANT_ID)
                        .and(GROWTH_SIGNAL.DISMISSED_AT.isNull()))
                .orderBy(GROWTH_SIGNAL.CREATED_AT.desc())
                .limit(20)
                .fetch(this::toSignal);
        if (!stored.isEmpty()) {
            return stored;
        }
        return List.of(new GrowthSignalView(
                null,
                "METRICS_PENDING",
                "Metrics not calculated yet",
                "Run the monthly metrics job to populate signals",
                "Open the dashboard after seed/recalculate",
                "INFO",
                "OPEN_METRICS",
                null,
                Instant.now(),
                null
        ));
    }

    @Override
    public List<MetricDefinitionView> definitions() {
        return MetricDefinitions.all();
    }

    @Override
    @Transactional
    public GrowthSignalView dismissSignal(UUID signalId) {
        var row = dsl.selectFrom(GROWTH_SIGNAL)
                .where(TenantAwareDsl.tenantEquals(GROWTH_SIGNAL.TENANT_ID).and(GROWTH_SIGNAL.ID.eq(signalId)))
                .orderBy(GROWTH_SIGNAL.CREATED_AT.desc())
                .limit(1)
                .fetchOptional()
                .orElseThrow(() -> DomainException.notFound("SIGNAL_NOT_FOUND", "Signal not found"));
        Instant now = Instant.now();
        dsl.update(GROWTH_SIGNAL)
                .set(GROWTH_SIGNAL.DISMISSED_AT, Utc.toLocal(now))
                .where(TenantAwareDsl.tenantEquals(GROWTH_SIGNAL.TENANT_ID)
                        .and(GROWTH_SIGNAL.ID.eq(signalId))
                        .and(GROWTH_SIGNAL.CREATED_AT.eq(row.get(GROWTH_SIGNAL.CREATED_AT))))
                .execute();
        return toSignal(row).withDismissed(now);
    }

    private GrowthSignalView toSignal(org.jooq.Record r) {
        Instant dismissed = r.get(GROWTH_SIGNAL.DISMISSED_AT) == null
                ? null
                : Utc.toInstant(r.get(GROWTH_SIGNAL.DISMISSED_AT));
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

    private void persist(List<MetricView> metrics, LocalDate from, LocalDate to) {
        for (MetricView metric : metrics) {
            dsl.insertInto(METRIC_SNAPSHOT)
                    .set(METRIC_SNAPSHOT.ID, UUID.randomUUID())
                    .set(METRIC_SNAPSHOT.TENANT_ID, TenantContext.require())
                    .set(METRIC_SNAPSHOT.SPECIALIST_ID, metric.specialistId())
                    .set(METRIC_SNAPSHOT.WINDOW_START, from)
                    .set(METRIC_SNAPSHOT.WINDOW_END, to)
                    .set(METRIC_SNAPSHOT.METRIC_KEY, metric.key())
                    .set(METRIC_SNAPSHOT.VALUE_NUMERIC, metric.value())
                    .set(METRIC_SNAPSHOT.SAMPLE_SIZE, metric.sampleSize())
                    .set(METRIC_SNAPSHOT.INSUFFICIENT_DATA, metric.insufficientData())
                    .set(METRIC_SNAPSHOT.EXPLANATION, metric.explanation())
                    .set(METRIC_SNAPSHOT.CREATED_AT, Utc.toLocal(Instant.now()))
                    .execute();
        }
    }

    private void persistSignals(List<MetricView> metrics, List<AppointmentView> visits) {
        List<GrowthSignalView> generated = new ArrayList<>();
        metrics.stream().filter(m -> "NO_SHOW_RATE".equals(m.key()) && m.value() != null && m.value() > 0.15)
                .findFirst()
                .ifPresent(m -> generated.add(new GrowthSignalView(
                        UUID.randomUUID(),
                        "NO_SHOW_SPIKE",
                        "No-show rate is elevated",
                        m.explanation(),
                        "Tighten Trust for new clients and send the 24h reminder",
                        "WARNING",
                        "OPEN_RULES",
                        "{\"focus\":\"newClientRequiresConfirmation\"}",
                        Instant.now(),
                        null
                )));
        metrics.stream().filter(m -> "SLOT_UTILIZATION".equals(m.key()) && m.value() != null && m.value() < 0.4)
                .findFirst()
                .ifPresent(m -> generated.add(new GrowthSignalView(
                        UUID.randomUUID(),
                        "UTILIZATION_GAP",
                        "Low slot utilization",
                        m.explanation(),
                        "Open a reactivation campaign for clients past their usual interval",
                        "WARNING",
                        "OPEN_CLIENTS",
                        null,
                        Instant.now(),
                        null
                )));
        long lowValue = visits.stream()
                .filter(v -> v.status().name().equals("COMPLETED") && v.durationSnapshot() > 0)
                .filter(v -> (v.priceSnapshot() / (v.durationSnapshot() / 60.0)) < 4000)
                .count();
        if (lowValue > 0) {
            generated.add(new GrowthSignalView(
                    UUID.randomUUID(),
                    "LOW_VALUE_SERVICES",
                    "Some completed services earn little per hour",
                    lowValue + " completed visits are below 40 GEL-equivalent per hour",
                    "Review price or duration on those offerings",
                    "INFO",
                    "OPEN_SERVICES",
                    null,
                    Instant.now(),
                    null
            ));
        }
        for (GrowthSignalView signal : generated) {
            dsl.insertInto(GROWTH_SIGNAL)
                    .set(GROWTH_SIGNAL.ID, signal.id())
                    .set(GROWTH_SIGNAL.TENANT_ID, TenantContext.require())
                    .set(GROWTH_SIGNAL.SIGNAL_KEY, signal.key())
                    .set(GROWTH_SIGNAL.TITLE, signal.title())
                    .set(GROWTH_SIGNAL.EVIDENCE, signal.evidence())
                    .set(GROWTH_SIGNAL.SUGGESTED_ACTION, signal.suggestedAction())
                    .set(GROWTH_SIGNAL.SEVERITY, signal.severity())
                    .set(GROWTH_SIGNAL.ACTION_TYPE, signal.actionType())
                    .set(GROWTH_SIGNAL.ACTION_PAYLOAD, signal.actionPayload())
                    .set(GROWTH_SIGNAL.CREATED_AT, Utc.toLocal(Instant.now()))
                    .execute();
        }
    }
}

package com.cadence.growth.internal;

import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.BookingService;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.catalog.api.WeeklyInterval;
import com.cadence.growth.api.GrowthService;
import com.cadence.growth.api.GrowthSignalView;
import com.cadence.growth.api.MetricView;
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
        var stored = dsl.selectFrom(METRIC_SNAPSHOT)
                .where(TenantAwareDsl.tenantEquals(METRIC_SNAPSHOT.TENANT_ID)
                        .and(specialistId == null
                                ? METRIC_SNAPSHOT.SPECIALIST_ID.isNull()
                                : METRIC_SNAPSHOT.SPECIALIST_ID.eq(specialistId)))
                .orderBy(METRIC_SNAPSHOT.CREATED_AT.desc())
                .limit(6)
                .fetch(r -> new MetricView(
                        r.get(METRIC_SNAPSHOT.METRIC_KEY),
                        r.get(METRIC_SNAPSHOT.VALUE_NUMERIC),
                        r.get(METRIC_SNAPSHOT.SAMPLE_SIZE),
                        Boolean.TRUE.equals(r.get(METRIC_SNAPSHOT.INSUFFICIENT_DATA)),
                        r.get(METRIC_SNAPSHOT.EXPLANATION),
                        r.get(METRIC_SNAPSHOT.SPECIALIST_ID)
                ));
        if (!stored.isEmpty()) {
            return stored;
        }
        return List.of(
                placeholder("SLOT_UTILIZATION", specialistId),
                placeholder("REPEAT_RATE", specialistId),
                placeholder("RETURN_INTERVAL", specialistId),
                placeholder("NO_SHOW_RATE", specialistId),
                placeholder("REVENUE_PER_HOUR", specialistId),
                placeholder("CHURN_RISK", specialistId)
        );
    }

    private static MetricView placeholder(String key, UUID specialistId) {
        return new MetricView(key, null, 0, true, "Will appear after Metrics Core recalculation", specialistId);
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
        List<MetricView> tenantMetrics = metricsCalculator.compute(visits, weekly, zone, windowStart, windowEnd, null);
        persist(tenantMetrics, windowStart, windowEnd);
        for (SpecialistView specialist : specialists) {
            List<AppointmentView> subset = visits.stream().filter(v -> v.specialistId().equals(specialist.id())).toList();
            List<WeeklyInterval> sw = weeklyBySpecialist.getOrDefault(specialist.id(), List.of());
            persist(metricsCalculator.compute(subset, sw, zone, windowStart, windowEnd, specialist.id()), windowStart, windowEnd);
        }
        persistSignals(tenantMetrics, visits);
        return tenantMetrics;
    }

    @Override
    @Transactional(readOnly = true)
    public List<GrowthSignalView> signals() {
        List<GrowthSignalView> stored = dsl.selectFrom(GROWTH_SIGNAL)
                .where(TenantAwareDsl.tenantEquals(GROWTH_SIGNAL.TENANT_ID))
                .orderBy(GROWTH_SIGNAL.CREATED_AT.desc())
                .limit(20)
                .fetch(r -> new GrowthSignalView(
                        r.get(GROWTH_SIGNAL.SIGNAL_KEY),
                        r.get(GROWTH_SIGNAL.TITLE),
                        r.get(GROWTH_SIGNAL.EVIDENCE),
                        r.get(GROWTH_SIGNAL.SUGGESTED_ACTION)
                ));
        if (!stored.isEmpty()) {
            return stored;
        }
        return List.of(new GrowthSignalView(
                "METRICS_PENDING",
                "Metrics not calculated yet",
                "Run the monthly metrics job to populate signals",
                "Open the dashboard after seed/recalculate"
        ));
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
                        "NO_SHOW_SPIKE",
                        "No-show rate is elevated",
                        m.explanation(),
                        "Tighten Trust for new clients and send the 24h reminder"
                )));
        metrics.stream().filter(m -> "SLOT_UTILIZATION".equals(m.key()) && m.value() != null && m.value() < 0.4)
                .findFirst()
                .ifPresent(m -> generated.add(new GrowthSignalView(
                        "UTILIZATION_GAP",
                        "Low slot utilization",
                        m.explanation(),
                        "Open a reactivation campaign for clients past their usual interval"
                )));
        long lowValue = visits.stream()
                .filter(v -> v.status().name().equals("COMPLETED") && v.durationSnapshot() > 0)
                .filter(v -> (v.priceSnapshot() / (v.durationSnapshot() / 60.0)) < 4000)
                .count();
        if (lowValue > 0) {
            generated.add(new GrowthSignalView(
                    "LOW_VALUE_SERVICES",
                    "Some completed services earn little per hour",
                    lowValue + " completed visits are below 40 GEL-equivalent per hour",
                    "Review price or duration on those offerings"
            ));
        }
        for (GrowthSignalView signal : generated) {
            dsl.insertInto(GROWTH_SIGNAL)
                    .set(GROWTH_SIGNAL.ID, UUID.randomUUID())
                    .set(GROWTH_SIGNAL.TENANT_ID, TenantContext.require())
                    .set(GROWTH_SIGNAL.SIGNAL_KEY, signal.key())
                    .set(GROWTH_SIGNAL.TITLE, signal.title())
                    .set(GROWTH_SIGNAL.EVIDENCE, signal.evidence())
                    .set(GROWTH_SIGNAL.SUGGESTED_ACTION, signal.suggestedAction())
                    .set(GROWTH_SIGNAL.CREATED_AT, Utc.toLocal(Instant.now()))
                    .execute();
        }
    }
}

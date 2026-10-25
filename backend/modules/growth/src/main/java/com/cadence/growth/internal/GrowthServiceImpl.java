package com.cadence.growth.internal;

import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.growth.api.GrowthService;
import com.cadence.growth.api.GrowthSignalView;
import com.cadence.growth.api.MetricDefinitionView;
import com.cadence.growth.api.MetricDefinitions;
import com.cadence.growth.api.MetricView;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.persistence.SqlStates;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.cadence.platform.jooq.Tables.METRIC_SNAPSHOT;

/**
 * Growth metrics from the booking journal (ADR 0001). A recalculation ({@link MetricsRecalculation}) folds the journal
 * as of T = now minus the as-of lag, checks it against the visit rows, computes both windows with the pure
 * {@link MetricsCalculator} and stores the snapshots with {@code created_at = T}, so any snapshot can be replayed by
 * folding the journal at its {@code created_at}.
 */
@Service
public class GrowthServiceImpl implements GrowthService {

    private final MetricsRecalculation recalculation;
    private final CatalogService catalogService;
    private final TenantDirectory tenants;
    private final MetricSnapshotRepository snapshots;
    private final GrowthSignalRepository signals;
    private final Clock clock;
    /** Read-only transaction for the result of {@link #recalculate}, which itself runs outside any transaction. */
    private final TransactionOperations readOnly;

    @Autowired
    GrowthServiceImpl(
            MetricsRecalculation recalculation,
            CatalogService catalogService,
            TenantDirectory tenants,
            MetricSnapshotRepository snapshots,
            GrowthSignalRepository signals,
            Clock clock,
            PlatformTransactionManager transactions
    ) {
        this(recalculation, catalogService, tenants, snapshots, signals, clock, readOnlyTemplate(transactions));
    }

    GrowthServiceImpl(
            MetricsRecalculation recalculation,
            CatalogService catalogService,
            TenantDirectory tenants,
            MetricSnapshotRepository snapshots,
            GrowthSignalRepository signals,
            Clock clock,
            TransactionOperations readOnly
    ) {
        this.recalculation = recalculation;
        this.catalogService = catalogService;
        this.tenants = tenants;
        this.snapshots = snapshots;
        this.signals = signals;
        this.clock = clock;
        this.readOnly = readOnly;
    }

    private static TransactionOperations readOnlyTemplate(PlatformTransactionManager transactions) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setReadOnly(true);
        return template;
    }

    @Override
    public String moduleName() {
        return "growth";
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricView> currentMetrics(UUID specialistId) {
        Map<String, Record> latest = snapshots.latestOfCurrentWindow(specialistId);
        if (latest.isEmpty()) {
            return placeholders(specialistId);
        }
        List<MetricView> result = new ArrayList<>();
        for (Record r : latest.values()) {
            LocalDate windowStart = r.get(METRIC_SNAPSHOT.WINDOW_START);
            LocalDate windowEnd = r.get(METRIC_SNAPSHOT.WINDOW_END);
            String key = r.get(METRIC_SNAPSHOT.METRIC_KEY);
            Double value = r.get(METRIC_SNAPSHOT.VALUE_NUMERIC);
            boolean insufficient = Boolean.TRUE.equals(r.get(METRIC_SNAPSHOT.INSUFFICIENT_DATA));
            result.add(new MetricView(
                    key,
                    value,
                    r.get(METRIC_SNAPSHOT.SAMPLE_SIZE),
                    insufficient,
                    r.get(METRIC_SNAPSHOT.EXPLANATION),
                    r.get(METRIC_SNAPSHOT.SPECIALIST_ID),
                    windowStart,
                    windowEnd,
                    MetricDefinitions.unitFor(key),
                    insufficient ? null : deltaMoM(key, specialistId, windowStart, value),
                    specialistId == null ? breakdownFor(key, windowStart, windowEnd) : List.of()
            ));
        }
        return result;
    }

    private List<MetricView> placeholders(UUID specialistId) {
        return MetricDefinitions.all().stream()
                .map(d -> MetricView.basic(d.key(), null, 0, true, "Will appear after Metrics Core recalculation", specialistId))
                .toList();
    }

    /**
     * Relative change against the latest sufficient snapshot of the previous calendar month (its full window), and
     * nothing else: no fallback to older windows.
     */
    private Double deltaMoM(String key, UUID specialistId, LocalDate windowStart, Double currentValue) {
        if (windowStart == null || currentValue == null) {
            return null;
        }
        YearMonth previous = YearMonth.from(windowStart).minusMonths(1);
        Double prior = snapshots.latestSufficientValue(key, specialistId, previous.atDay(1), previous.atEndOfMonth());
        if (prior == null || prior == 0.0) {
            return null;
        }
        return (currentValue - prior) / prior;
    }

    private List<MetricView.SpecialistBreakdown> breakdownFor(String key, LocalDate windowStart, LocalDate windowEnd) {
        Map<UUID, SpecialistView> specialists = catalogService.listSpecialists(false).stream()
                .collect(Collectors.toMap(SpecialistView::id, s -> s, (a, b) -> a));
        return snapshots.latestBySpecialist(key, windowStart, windowEnd).entrySet().stream()
                .sorted(Comparator.comparing(e -> nameOf(specialists, e.getKey())))
                .map(e -> new MetricView.SpecialistBreakdown(
                        e.getKey(),
                        nameOf(specialists, e.getKey()),
                        e.getValue().get(METRIC_SNAPSHOT.VALUE_NUMERIC),
                        Boolean.TRUE.equals(e.getValue().get(METRIC_SNAPSHOT.INSUFFICIENT_DATA))
                ))
                .toList();
    }

    private static String nameOf(Map<UUID, SpecialistView> specialists, UUID id) {
        SpecialistView s = specialists.get(id);
        return s == null ? id.toString() : s.displayName();
    }

    /**
     * Review m1: a read step (REPEATABLE READ snapshot, read-only) and a write step (READ COMMITTED, serialised per
     * tenant), see {@link MetricsRecalculation}. Not transactional itself, so each step runs in its own transaction even
     * when the caller has one (the batch tasklet). A serialization failure or deadlock of either step is retried, at
     * most {@value #MAX_ATTEMPTS} attempts in all.
     */
    @Override
    public List<MetricView> recalculate() {
        UUID tenantId = TenantContext.require();
        return run(() -> recalculation.read(tenantId));
    }

    /** Review D1: the seed's recalculation, as of now instead of now minus the lag; see the port's Javadoc. */
    @Override
    public List<MetricView> recalculateForSeed() {
        UUID tenantId = TenantContext.require();
        return run(() -> recalculation.readWithoutLag(tenantId));
    }

    private List<MetricView> run(java.util.function.Supplier<MetricsRecalculation.Computation> read) {
        for (int attempt = 1; ; attempt++) {
            try {
                recalculation.write(read.get());
                break;
            } catch (RuntimeException ex) {
                if (attempt >= MAX_ATTEMPTS || !retryable(ex)) {
                    throw ex;
                }
            }
        }
        return readOnly.execute(status -> currentMetrics(null));
    }

    static final int MAX_ATTEMPTS = 3;

    static boolean retryable(Throwable ex) {
        return SqlStates.hasViolation(ex, "40001", null) || SqlStates.hasViolation(ex, "40P01", null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GrowthSignalView> signals() {
        // Only the current period (the tenant's month now): older periods are history, as are resolved signals.
        ZoneId zone = ZoneId.of(tenants.requireById(TenantContext.require()).getTimezone());
        List<GrowthSignalView> stored = signals.open(YearMonth.now(clock.withZone(zone)).toString(), 20);
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
                clock.instant(),
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
        GrowthSignalView signal = signals.find(signalId)
                .orElseThrow(() -> DomainException.notFound("SIGNAL_NOT_FOUND", "Signal not found"));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        signals.dismiss(signalId, now);
        return signal.withDismissed(now);
    }
}

package com.cadence.growth.internal;

import com.cadence.booking.api.BookingJournal;
import com.cadence.booking.api.JournalDefect;
import com.cadence.booking.api.JournalFold;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.ScheduleExceptionView;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.catalog.api.WeeklyInterval;
import com.cadence.growth.api.MetricView;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The two transactional steps of a tenant's recalculation (ADR 0001 §5, review M3 and m1).
 *
 * <ol>
 *   <li>{@link #read}: REPEATABLE READ, read-only, its own transaction. Folds the journal as of T = now minus
 *   {@code app.metrics.as-of-lag} (every event stamped up to T is committed by then, so a replay at T sees the same
 *   journal), reconciles the whole journal with the visit rows of the same snapshot and computes both windows. A
 *   read-only REPEATABLE READ transaction never fails with a serialization error.</li>
 *   <li>{@link #write}: READ COMMITTED, its own transaction, serialised per tenant by a transaction-scoped advisory
 *   lock. Stores the snapshots and the audit, then upserts and resolves the current period's signals unless a
 *   recalculation with a later T has already written (the newer one decides). Concurrent dismissals only wait for the
 *   row lock: the upsert never touches {@code dismissed_at}.</li>
 * </ol>
 */
@Component
class MetricsRecalculation {

    static final String JOURNAL_MISMATCH = "growth.journal_mismatch";
    private static final int MAX_DEFECTS_AUDITED = 20;
    private static final int MAX_DETAIL = 200;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Everything the read step computed; the write step only stores it. */
    record Computation(UUID tenantId, Instant asOf, YearMonth period, List<Window> windows, List<JournalDefect> defects,
                       MetricsCalculator.Result currentTenant) {
    }

    /** One window's snapshot rows: tenant and every active specialist. */
    record Window(LocalDate start, LocalDate end, List<MetricView> rows) {
    }

    private final BookingJournal journal;
    private final CatalogService catalogService;
    private final TenantDirectory tenants;
    private final MetricSnapshotRepository snapshots;
    private final GrowthSignalRepository signals;
    private final AuditLogger auditLogger;
    private final org.jooq.DSLContext dsl;
    private final Clock clock;
    private final Duration asOfLag;

    MetricsRecalculation(
            BookingJournal journal,
            CatalogService catalogService,
            TenantDirectory tenants,
            MetricSnapshotRepository snapshots,
            GrowthSignalRepository signals,
            AuditLogger auditLogger,
            org.jooq.DSLContext dsl,
            Clock clock,
            @Value("${app.metrics.as-of-lag:PT1M}") Duration asOfLag
    ) {
        if (asOfLag.isNegative()) {
            throw new IllegalArgumentException("app.metrics.as-of-lag must not be negative");
        }
        this.journal = journal;
        this.catalogService = catalogService;
        this.tenants = tenants;
        this.snapshots = snapshots;
        this.signals = signals;
        this.auditLogger = auditLogger;
        this.dsl = dsl;
        this.clock = clock;
        this.asOfLag = asOfLag;
    }

    /** The regular read step: T = now minus {@code app.metrics.as-of-lag}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public Computation read(UUID tenantId) {
        return compute(tenantId, asOfLag);
    }

    /**
     * Review D1: T = now, no lag. Only for the single writer at startup (the demo seed), which has committed all of
     * its writes before it calls this, so no event stamped before T can still be in flight.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public Computation readWithoutLag(UUID tenantId) {
        return compute(tenantId, Duration.ZERO);
    }

    private Computation compute(UUID tenantId, Duration lag) {
        // The first statement fixes the snapshot; now and T are taken after it.
        ZoneId zone = ZoneId.of(tenants.requireById(tenantId).getTimezone());
        Instant now = clock.instant();
        Instant asOf = now.minus(lag).truncatedTo(ChronoUnit.MICROS);
        LocalDate today = LocalDate.ofInstant(asOf, zone);
        LocalDate currentStart = today.withDayOfMonth(1);
        YearMonth previousMonth = YearMonth.from(today).minusMonths(1);

        JournalFold fold = journal.fold(asOf);
        // The rows are current: check them against the whole journal of this snapshot (both are written together).
        List<JournalDefect> defects = journal.reconcile(now);

        List<SpecialistView> active = catalogService.listSpecialists(true);
        MetricsCalculator.Schedule schedule = schedule(active, previousMonth.atDay(1), today);

        List<Window> windows = new ArrayList<>();
        windows.add(window(fold, schedule, zone, previousMonth.atDay(1), previousMonth.atEndOfMonth(), asOf, active));
        windows.add(window(fold, schedule, zone, currentStart, today, asOf, active));
        MetricsCalculator.Result currentTenant = MetricsCalculator.compute(fold.facts(), schedule, zone, currentStart, today, asOf, null);
        return new Computation(tenantId, asOf, YearMonth.from(currentStart), windows, defects, currentTenant);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(Computation c) {
        dsl.query("select pg_advisory_xact_lock(hashtextextended({0}, 0))", org.jooq.impl.DSL.val("growth.metrics:" + c.tenantId()))
                .execute();
        boolean newerWritten = snapshots.newestAsOf().map(t -> t.isAfter(c.asOf())).orElse(false);
        auditMismatches(c);
        for (Window w : c.windows()) {
            snapshots.insert(w.rows(), w.start(), w.end(), c.asOf());
        }
        if (newerWritten) {
            return;
        }
        Set<String> firing = new HashSet<>();
        for (GrowthSignalRepository.Draft draft : SignalRules.evaluate(c.currentTenant(), c.period())) {
            signals.upsert(draft, c.asOf());
            firing.add(draft.signalKey());
        }
        // Review m4: a rule that no longer fires resolves this period's signal; firing again re-opens it (upsert).
        signals.resolveAllBut(c.period().toString(), firing, c.asOf());
    }

    private static Window window(JournalFold fold, MetricsCalculator.Schedule schedule, ZoneId zone, LocalDate ws,
                                 LocalDate we, Instant asOf, List<SpecialistView> active) {
        List<MetricView> rows = new ArrayList<>(
                MetricsCalculator.compute(fold.facts(), schedule, zone, ws, we, asOf, null).metrics());
        for (SpecialistView specialist : active) {
            rows.addAll(MetricsCalculator.compute(fold.facts(), schedule, zone, ws, we, asOf, specialist.id()).metrics());
        }
        return new Window(ws, we, rows.stream().map(m -> m.withWindow(ws, we)).toList());
    }

    private MetricsCalculator.Schedule schedule(List<SpecialistView> active, LocalDate from, LocalDate to) {
        Set<UUID> ids = new HashSet<>();
        Map<UUID, List<ScheduleExceptionView>> exceptions = new HashMap<>();
        for (SpecialistView s : active) {
            ids.add(s.id());
            exceptions.put(s.id(), catalogService.exceptions(s.id(), from, to));
        }
        Map<UUID, List<WeeklyInterval>> weekly = catalogService.allWeeklySchedules().stream()
                .filter(w -> ids.contains(w.specialistId()))
                .collect(Collectors.groupingBy(WeeklyInterval::specialistId));
        return new MetricsCalculator.Schedule(ids, weekly, exceptions);
    }

    /** One audit row per recalculation that found defects: the count and the first defects, no client data. */
    private void auditMismatches(Computation c) {
        if (c.defects().isEmpty()) {
            return;
        }
        ObjectNode after = MAPPER.createObjectNode();
        after.put("asOf", c.asOf().toString());
        after.put("count", c.defects().size());
        ArrayNode list = after.putArray("defects");
        c.defects().stream().limit(MAX_DEFECTS_AUDITED).forEach(d -> list.addObject()
                .put("appointmentId", d.appointmentId() == null ? null : d.appointmentId().toString())
                .put("kind", d.kind())
                .put("detail", MetricSnapshotRepository.truncate(d.detail(), MAX_DETAIL)));
        String json;
        try {
            json = MAPPER.writeValueAsString(after);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        auditLogger.record(
                ActorContext.current().map(ActorContext.Actor::actorId).orElse("system"),
                ActorContext.current().map(a -> a.role().name()).orElse("SYSTEM"),
                JOURNAL_MISMATCH,
                "tenant",
                c.tenantId(),
                null,
                json,
                "metrics",
                null
        );
    }
}

package com.cadence.growth.api;

import java.util.List;
import java.util.UUID;

/** Public growth / metrics port. */
public interface GrowthService {

    String moduleName();

    /**
     * Latest snapshot of the current window (the most recent window start) per metric, with the per-specialist
     * breakdown for the tenant view and the change against the previous calendar month.
     */
    List<MetricView> currentMetrics(UUID specialistId);

    /**
     * Recalculates the current tenant in its zone (ADR 0001 §5): the current month to date and the previous full
     * month, tenant and per active specialist, from the booking journal as of now; reconciles the journal with the
     * visit rows (audit {@code growth.journal_mismatch}) and upserts the current month's signals. Returns
     * {@link #currentMetrics} of the tenant.
     */
    List<MetricView> recalculate();

    /**
     * {@link #recalculate} as of now, without {@code app.metrics.as-of-lag}. Only for the single writer at application
     * startup (the demo seed), right after it has committed its own writes: with no concurrent writer, no event stamped
     * before now can still be uncommitted, which is what the lag guards against. Never exposed over HTTP and never
     * called by the nightly job (ModuleBoundaryArchTest allows only {@code DemoDataSeeder}).
     */
    List<MetricView> recalculateForSeed();

    List<GrowthSignalView> signals();

    List<MetricDefinitionView> definitions();

    GrowthSignalView dismissSignal(UUID signalId);
}

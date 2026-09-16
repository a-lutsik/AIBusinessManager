package com.cadence.growth.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Public growth / metrics port. */
public interface GrowthService {

    String moduleName();

    List<MetricView> currentMetrics(UUID specialistId);

    List<MetricView> recalculate(LocalDate windowStart, LocalDate windowEnd);

    List<GrowthSignalView> signals();

    List<MetricDefinitionView> definitions();

    GrowthSignalView dismissSignal(UUID signalId);
}

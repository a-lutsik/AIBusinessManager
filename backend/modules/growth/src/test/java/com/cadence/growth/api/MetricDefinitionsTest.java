package com.cadence.growth.api;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricDefinitionsTest {

    @Test
    void coversSixCoreMetrics() {
        Set<String> keys = MetricDefinitions.all().stream().map(MetricDefinitionView::key).collect(Collectors.toSet());
        assertEquals(6, keys.size());
        assertTrue(keys.containsAll(Set.of(
                "SLOT_UTILIZATION",
                "REPEAT_RATE",
                "RETURN_INTERVAL",
                "NO_SHOW_RATE",
                "REVENUE_PER_HOUR",
                "CHURN_RISK"
        )));
    }

    @Test
    void unitsAreStable() {
        assertEquals("RATIO", MetricDefinitions.unitFor("SLOT_UTILIZATION"));
        assertEquals("DAYS", MetricDefinitions.unitFor("RETURN_INTERVAL"));
        assertEquals("CURRENCY_PER_HOUR", MetricDefinitions.unitFor("REVENUE_PER_HOUR"));
    }
}

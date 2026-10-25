package com.cadence.growth.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals("NUMBER", MetricDefinitions.unitFor("UNKNOWN"));
    }

    /** PRODUCT_PLAN §4.5 / ADR 0001 §4 minimum samples; MetricsCalculatorTest checks the calculator uses these. */
    @Test
    void minimumSamplesAreTheAgreedOnes() {
        Map<String, Integer> expected = Map.of(
                "SLOT_UTILIZATION", 1, "REPEAT_RATE", 10, "RETURN_INTERVAL", 3,
                "NO_SHOW_RATE", 5, "REVENUE_PER_HOUR", 1, "CHURN_RISK", 5);
        expected.forEach((key, min) -> assertEquals(min, MetricDefinitions.minSampleSize(key), key));
        assertThrows(IllegalArgumentException.class, () -> MetricDefinitions.minSampleSize("UNKNOWN"));
    }

    /** The published formulas state the confirmed definitions (ADR 0001, user decisions of 2026-10-25). */
    @Test
    void formulasStateTheConfirmedDefinitions() {
        Map<String, String> formula = MetricDefinitions.all().stream()
                .collect(Collectors.toMap(MetricDefinitionView::key, MetricDefinitionView::formula));
        List.of("before the end of Wi", "whole history", "different days").forEach(s -> assertTrue(formula.get("REPEAT_RATE").contains(s), s));
        List.of("started before T", "not yet marked", "cancellations excluded")
                .forEach(s -> assertTrue(formula.get("NO_SHOW_RATE").contains(s), s));
        List.of("DAY_OFF", "SHIFT", "active at T").forEach(s -> assertTrue(formula.get("SLOT_UTILIZATION").contains(s), s));
        assertTrue(formula.get("RETURN_INTERVAL").contains("visit days"));
        List.of("365 days", "1.5×", "2×", "visit day").forEach(s -> assertTrue(formula.get("CHURN_RISK").contains(s), s));
    }
}

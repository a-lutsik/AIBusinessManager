package com.cadence.growth.internal;

import com.cadence.growth.api.MetricView;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Currency-free, deduplicated signal rules over the current month's tenant metrics (ADR 0001 §5). */
class SignalRulesTest {

    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final UUID CUT = UUID.randomUUID();
    private static final UUID CONSULT = UUID.randomUUID();

    private static MetricView m(String key, Double value, boolean insufficient) {
        return MetricView.basic(key, value, 10, insufficient, "x", null);
    }

    private static MetricsCalculator.Result result(double noShow, double utilization, double perHour,
                                                   List<MetricsCalculator.ServiceHours> services, boolean insufficient) {
        return new MetricsCalculator.Result(List.of(
                m("SLOT_UTILIZATION", insufficient ? null : utilization, insufficient),
                m("NO_SHOW_RATE", insufficient ? null : noShow, insufficient),
                m("REVENUE_PER_HOUR", insufficient ? null : perHour, insufficient)),
                List.of(), 1200, 4000, 3, 20, services);
    }

    private static List<String> keys(List<GrowthSignalRepository.Draft> drafts) {
        return drafts.stream().map(GrowthSignalRepository.Draft::signalKey).toList();
    }

    @Test
    void noShowSpikeFiresFromFifteenPercent() {
        assertEquals(List.of("NO_SHOW_SPIKE"), keys(SignalRules.evaluate(result(0.15, 0.5, 0, List.of(), false), OCTOBER)));
        assertEquals(List.of(), keys(SignalRules.evaluate(result(0.1499, 0.5, 0, List.of(), false), OCTOBER)));
        GrowthSignalRepository.Draft d = SignalRules.evaluate(result(0.2, 0.5, 0, List.of(), false), OCTOBER).getFirst();
        assertEquals("WARNING", d.severity());
        assertEquals("3 of 20 visits that reached their start were no-shows", d.evidence());
        assertEquals("NO_SHOW_SPIKE:tenant:2026-10", d.dedupeKey());
        assertEquals("2026-10", d.period());
        assertNull(d.specialistId());
    }

    @Test
    void utilizationGapFiresBelowFortyPercent() {
        assertEquals(List.of("UTILIZATION_GAP"), keys(SignalRules.evaluate(result(0, 0.3999, 0, List.of(), false), OCTOBER)));
        assertEquals(List.of(), keys(SignalRules.evaluate(result(0, 0.40, 0, List.of(), false), OCTOBER)));
        GrowthSignalRepository.Draft d = SignalRules.evaluate(result(0, 0.3, 0, List.of(), false), OCTOBER).getFirst();
        assertEquals("Booked 1200 / 4000 available minutes", d.evidence());
        assertEquals("UTILIZATION_GAP:tenant:2026-10", d.dedupeKey());
    }

    @Test
    void metricsWithoutEnoughDataNeverFire() {
        assertEquals(List.of(), keys(SignalRules.evaluate(result(0.9, 0.01, 1000, List.of(
                new MetricsCalculator.ServiceHours(CONSULT, "Consultation", 9, 0, 270)), true), OCTOBER)));
    }

    /**
     * Tenant 6000 minor per hour. Cut: 5 visits, 9500 over 250 min = 2280/h = 38% -> fires. Consultation: 2 visits
     * only (< 3) -> ignored. No amount reaches the evidence, the sums are in the action payload.
     */
    @Test
    void lowValueServicesCompareTheServiceShareOfTheTenantHourlyValue() {
        var services = List.of(
                new MetricsCalculator.ServiceHours(CUT, "Cut", 5, 9500, 250),
                new MetricsCalculator.ServiceHours(CONSULT, "Consultation", 2, 0, 60),
                new MetricsCalculator.ServiceHours(UUID.randomUUID(), "Color", 4, 24000, 240));
        List<GrowthSignalRepository.Draft> drafts = SignalRules.evaluate(result(0, 0.9, 6000, services, false), OCTOBER);
        assertEquals(List.of("LOW_VALUE_SERVICES"), keys(drafts));
        GrowthSignalRepository.Draft d = drafts.getFirst();
        assertEquals("Cut earns 38% of your average per hour over 5 visits", d.evidence());
        assertFalse(d.evidence().contains("9500"));
        assertFalse(d.evidence().contains("6000"));
        assertTrue(d.actionPayload().contains("\"valueMinor\":9500"), d.actionPayload());
        assertTrue(d.actionPayload().contains("\"tenantPerHourMinor\":6000.0"), d.actionPayload());
        assertEquals("LOW_VALUE_SERVICES:tenant:2026-10", d.dedupeKey());
    }

    @Test
    void halfTheTenantHourlyValueIsNotLowValue() {
        var services = List.of(new MetricsCalculator.ServiceHours(CUT, "Cut", 3, 3000, 60));
        assertEquals(List.of(), keys(SignalRules.evaluate(result(0, 0.9, 6000, services, false), OCTOBER)));
    }
}

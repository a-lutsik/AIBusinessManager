package com.cadence.growth.internal;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.VisitFact;
import com.cadence.growth.api.MetricDefinitions;
import com.cadence.growth.api.MetricView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.cadence.booking.api.AppointmentStatus.CANCELLED_BY_CLIENT;
import static com.cadence.booking.api.AppointmentStatus.COMPLETED;
import static com.cadence.booking.api.AppointmentStatus.CONFIRMED;
import static com.cadence.booking.api.AppointmentStatus.NO_SHOW;
import static com.cadence.growth.internal.MetricsGoldenDatasetTest.HAIRCUT;
import static com.cadence.growth.internal.MetricsGoldenDatasetTest.M1;
import static com.cadence.growth.internal.MetricsGoldenDatasetTest.M2;
import static com.cadence.growth.internal.MetricsGoldenDatasetTest.T;
import static com.cadence.growth.internal.MetricsGoldenDatasetTest.WE;
import static com.cadence.growth.internal.MetricsGoldenDatasetTest.WS;
import static com.cadence.growth.internal.MetricsGoldenDatasetTest.ZONE;
import static com.cadence.growth.internal.MetricsGoldenDatasetTest.metric;
import static com.cadence.growth.internal.MetricsGoldenDatasetTest.schedule;
import static com.cadence.growth.internal.MetricsGoldenDatasetTest.weekly;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Window rules, edge cases and the "not enough data" case of every metric. Thresholds are read from
 * {@link MetricDefinitions}: sample = minimum - 1 is insufficient, sample = minimum is not, so the published
 * definition and the calculator cannot drift apart.
 */
class MetricsCalculatorTest {

    private static int seq;

    private static UUID client(int n) {
        return MetricsGoldenDatasetTest.id("e" + String.format("%04d", n));
    }

    private static VisitFact visit(int client, UUID master, AppointmentStatus status, String start, int minutes) {
        Instant s = Instant.parse(start);
        return new VisitFact(MetricsGoldenDatasetTest.id("9" + String.format("%05d", ++seq)), master, HAIRCUT,
                client(client), status, s, s.plusSeconds(60L * minutes), 8000, 0, minutes, "GEL", "Haircut", "CONSOLE",
                s.minusSeconds(3600));
    }

    private static MetricsCalculator.Result compute(List<VisitFact> facts) {
        return MetricsCalculator.compute(facts, schedule(), ZONE, WS, WE, T, null);
    }

    private static void assertInsufficient(MetricView m, int sample) {
        assertTrue(m.insufficientData(), m.key() + ": " + m.explanation());
        assertNull(m.value(), m.key());
        assertEquals(sample, m.sampleSize(), m.key());
    }

    // ---- thresholds from MetricDefinitions ----

    /** n clients with one COMPLETED visit each in W. */
    private static List<VisitFact> oneVisitClients(int n) {
        List<VisitFact> f = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            f.add(visit(i, M1, COMPLETED, String.format("2026-09-%02dT06:00:00Z", 1 + i % 28), 60));
        }
        return f;
    }

    @Test
    void repeatRateNeedsTheDefinedNumberOfClients() {
        int min = MetricDefinitions.minSampleSize("REPEAT_RATE");
        assertInsufficient(metric(compute(oneVisitClients(min - 1)), "REPEAT_RATE"), min - 1);
        MetricView enough = metric(compute(oneVisitClients(min)), "REPEAT_RATE");
        assertFalse(enough.insufficientData());
        assertEquals(0.0, enough.value(), 0.0, "nobody came twice");
    }

    /** n clients, each with COMPLETED visits on 09-01 and 09-11 (gap 10). */
    private static List<VisitFact> twoVisitClients(int n) {
        List<VisitFact> f = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            f.add(visit(i, M1, COMPLETED, "2026-09-01T0" + (i % 9) + ":00:00Z", 30));
            f.add(visit(i, M1, COMPLETED, "2026-09-11T0" + (i % 9) + ":00:00Z", 30));
        }
        return f;
    }

    @Test
    void returnIntervalNeedsTheDefinedNumberOfClients() {
        int min = MetricDefinitions.minSampleSize("RETURN_INTERVAL");
        assertInsufficient(metric(compute(twoVisitClients(min - 1)), "RETURN_INTERVAL"), min - 1);
        MetricView enough = metric(compute(twoVisitClients(min)), "RETURN_INTERVAL");
        assertEquals(10.0, enough.value(), 0.0);
    }

    @Test
    void noShowRateNeedsTheDefinedNumberOfDueVisits() {
        int min = MetricDefinitions.minSampleSize("NO_SHOW_RATE");
        List<VisitFact> f = new ArrayList<>();
        for (int i = 1; i < min; i++) {
            f.add(visit(i, M1, i == 1 ? NO_SHOW : COMPLETED, String.format("2026-09-%02dT06:00:00Z", i), 60));
        }
        // A cancellation never counts.
        f.add(visit(99, M1, CANCELLED_BY_CLIENT, "2026-09-20T06:00:00Z", 60));
        assertInsufficient(metric(compute(f), "NO_SHOW_RATE"), min - 1);
        f.add(visit(98, M1, CONFIRMED, "2026-09-21T06:00:00Z", 60));
        MetricView enough = metric(compute(f), "NO_SHOW_RATE");
        assertEquals(1.0 / min, enough.value(), 1e-12);
        assertTrue(enough.explanation().contains("1 not marked"), enough.explanation());
    }

    @Test
    void noShowRateCountsOnlyVisitsThatStartedBeforeT() {
        // T inside the window: 2026-09-15T12:00Z. Five due visits before it, three after it are not in D.
        Instant t = Instant.parse("2026-09-15T12:00:00Z");
        List<VisitFact> f = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            f.add(visit(i, M1, i <= 2 ? NO_SHOW : COMPLETED, String.format("2026-09-%02dT06:00:00Z", i), 60));
        }
        f.add(visit(6, M1, CONFIRMED, "2026-09-15T12:00:00Z", 60));
        f.add(visit(7, M1, CONFIRMED, "2026-09-16T06:00:00Z", 60));
        f.add(visit(8, M1, AppointmentStatus.PENDING, "2026-09-29T06:00:00Z", 60));
        MetricView m = metric(MetricsCalculator.compute(f, schedule(), ZONE, WS, WE, t, null), "NO_SHOW_RATE");
        assertEquals(2.0 / 5.0, m.value(), 1e-12);
        assertEquals(5, m.sampleSize(), "a visit starting exactly at T has not started before T");
    }

    @Test
    void revenuePerHourNeedsCompletedMinutes() {
        List<VisitFact> f = new ArrayList<>(List.of(visit(1, M1, NO_SHOW, "2026-09-02T06:00:00Z", 60)));
        assertInsufficient(metric(compute(f), "REVENUE_PER_HOUR"), 0);
        f.add(visit(2, M1, COMPLETED, "2026-09-03T06:00:00Z", MetricDefinitions.minSampleSize("REVENUE_PER_HOUR")));
        MetricView one = metric(compute(f), "REVENUE_PER_HOUR");
        assertEquals(8000 * 60.0, one.value(), 1e-9, "8000 minor in one minute = 480000 per hour");
    }

    /** n clients with COMPLETED 2026-09-01 and 2026-09-21 (typical 20 days), last visit 14 days before T. */
    @Test
    void churnRiskNeedsTheDefinedNumberOfEvaluatedClients() {
        int min = MetricDefinitions.minSampleSize("CHURN_RISK");
        List<VisitFact> f = new ArrayList<>();
        for (int i = 1; i < min; i++) {
            f.add(visit(i, M1, COMPLETED, "2026-09-01T0" + i + ":00:00Z", 30));
            f.add(visit(i, M1, COMPLETED, "2026-09-21T0" + i + ":00:00Z", 30));
        }
        assertInsufficient(metric(compute(f), "CHURN_RISK"), min - 1);
        f.add(visit(50, M1, COMPLETED, "2026-09-02T06:00:00Z", 30));
        MetricView enough = metric(compute(f), "CHURN_RISK");
        assertFalse(enough.insufficientData());
        // Client 50: one visit, tenant typical 20; 09-02 -> 10-05 = 33 days >= 30 (1.5x) WARNING, < 40.
        assertEquals(0.0, enough.value(), 0.0);
        assertTrue(enough.explanation().contains("0 high risk and 1 warning"), enough.explanation());
    }

    @Test
    void clientsWithoutAnyTypicalIntervalAreExcludedAndSaidSo() {
        List<VisitFact> f = oneVisitClients(6);
        MetricsCalculator.Result result = compute(f);
        assertInsufficient(metric(result, "CHURN_RISK"), 0);
        assertTrue(metric(result, "CHURN_RISK").explanation().contains("6 without a typical interval excluded"));
        assertTrue(result.churn().isEmpty());
    }

    @Test
    void churnLooksBackExactly365DaysFromT() {
        // 2025-10-05T12:00Z is T - 365 days: included; one second earlier is not.
        List<VisitFact> f = new ArrayList<>();
        f.add(visit(1, M1, COMPLETED, "2025-10-05T12:00:00Z", 30));
        f.add(visit(2, M1, COMPLETED, "2025-10-05T11:59:59Z", 30));
        f.add(visit(3, M1, COMPLETED, "2026-01-01T06:00:00Z", 30));
        f.add(visit(3, M1, COMPLETED, "2026-01-11T06:00:00Z", 30));
        Set<UUID> evaluated = new java.util.HashSet<>();
        compute(f).churn().forEach(a -> evaluated.add(a.clientId()));
        assertEquals(Set.of(client(1), client(3)), evaluated);
    }

    @Test
    void slotUtilizationWithoutActiveSpecialistsHasNoDenominator() {
        var noOne = new MetricsCalculator.Schedule(Set.of(), Map.of(M1, List.of(weekly(M1, 1, 600, 1080))), Map.of());
        List<VisitFact> f = List.of(visit(1, M1, COMPLETED, "2026-09-07T06:00:00Z", 60));
        assertInsufficient(metric(MetricsCalculator.compute(f, noOne, ZONE, WS, WE, T, null), "SLOT_UTILIZATION"), 0);
        // A slice of an inactive specialist has an empty S as well.
        var onlyM2 = new MetricsCalculator.Schedule(Set.of(M2), Map.of(M1, List.of(weekly(M1, 1, 600, 1080))), Map.of());
        assertInsufficient(metric(MetricsCalculator.compute(f, onlyM2, ZONE, WS, WE, T, M1), "SLOT_UTILIZATION"), 0);
    }

    @Test
    void anEvenNumberOfGapsGivesAFractionalMedian() {
        assertEquals(5.5, MetricsCalculator.median(List.of(10.0, 1.0)), 0.0);
        assertEquals(14.0, MetricsCalculator.median(List.of(14.0)), 0.0);
        assertEquals(2.5, MetricsCalculator.median(List.of(4.0, 1.0, 3.0, 2.0)), 0.0);
    }

    @Test
    void gapsAreCountedBetweenTenantZoneDatesNotIn24HourBlocks() {
        // 2026-09-01T19:00Z is 09-01 23:00 local, 2026-09-02T21:00Z is 09-03 01:00 local: 2 days, though 26 h apart.
        List<VisitFact> f = new ArrayList<>();
        for (int c = 1; c <= 3; c++) {
            f.add(visit(c, M1, COMPLETED, "2026-09-01T19:0" + c + ":00Z", 30));
            f.add(visit(c, M1, COMPLETED, "2026-09-02T21:0" + c + ":00Z", 30));
        }
        assertEquals(2.0, metric(compute(f), "RETURN_INTERVAL").value(), 0.0);
    }

    @Test
    void theWindowIsTheTenantZoneDatesNotUtcDates() {
        // 2026-08-31T20:00Z is 2026-09-01 00:00 local (in W); 2026-08-31T19:59Z is not.
        List<VisitFact> f = new ArrayList<>(oneVisitClients(9));
        f.add(visit(100, M1, COMPLETED, "2026-08-31T20:00:00Z", 60));
        f.add(visit(101, M1, COMPLETED, "2026-08-31T19:59:00Z", 60));
        assertEquals(10, metric(compute(f), "REPEAT_RATE").sampleSize());
    }

    @Test
    void sliceNarrowsTheFactsToTheSpecialist() {
        List<VisitFact> f = new ArrayList<>(oneVisitClients(10));
        f.add(visit(200, M2, COMPLETED, "2026-09-05T06:00:00Z", 60));
        assertEquals(11, metric(compute(f), "REPEAT_RATE").sampleSize());
        assertEquals(1, metric(MetricsCalculator.compute(f, schedule(), ZONE, WS, WE, T, M2), "REPEAT_RATE").sampleSize());
    }

    @Test
    void sameInputGivesTheSameResult() {
        LocalDate ws = LocalDate.of(2026, 9, 1);
        var a = MetricsCalculator.compute(MetricsGoldenDatasetTest.facts(), schedule(), ZONE, ws, WE, T, null);
        var b = MetricsCalculator.compute(MetricsGoldenDatasetTest.facts(), schedule(), ZONE, ws, WE, T, null);
        assertEquals(a.metrics(), b.metrics());
    }
}

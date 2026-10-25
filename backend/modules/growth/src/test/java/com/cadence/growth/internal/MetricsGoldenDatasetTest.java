package com.cadence.growth.internal;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.VisitFact;
import com.cadence.catalog.api.ScheduleExceptionView;
import com.cadence.catalog.api.WeeklyInterval;
import com.cadence.growth.api.MetricView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.cadence.booking.api.AppointmentStatus.CANCELLED_BY_BUSINESS;
import static com.cadence.booking.api.AppointmentStatus.CANCELLED_BY_CLIENT;
import static com.cadence.booking.api.AppointmentStatus.COMPLETED;
import static com.cadence.booking.api.AppointmentStatus.CONFIRMED;
import static com.cadence.booking.api.AppointmentStatus.NO_SHOW;
import static com.cadence.booking.api.AppointmentStatus.PENDING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden dataset (ADR 0001 §6): 34 visit facts, 2 active masters and 1 inactive, 14 clients, window September 2026
 * in Asia/Tbilisi (UTC+4, no DST), as of T = 2026-10-05T12:00Z (16:00 local, day(T) = 2026-10-05). Every expected
 * value below is worked out by hand in the comments, from the definitions of ADR 0001 §4, not from the code.
 *
 * <p>W = 2026-09-01..2026-09-30, Wi = [2026-08-31T20:00Z, 2026-09-30T20:00Z). Times are 10:00Z (14:00 local) unless
 * stated. Durations 60 min, Haircut 8000 minor, unless stated.
 */
class MetricsGoldenDatasetTest {

    static final ZoneId ZONE = ZoneId.of("Asia/Tbilisi");
    static final LocalDate WS = LocalDate.of(2026, 9, 1);
    static final LocalDate WE = LocalDate.of(2026, 9, 30);
    static final Instant T = Instant.parse("2026-10-05T12:00:00Z");
    static final double EPS = 1e-12;

    static final UUID M1 = id("a1");
    static final UUID M2 = id("a2");
    static final UUID M3 = id("a3");
    static final UUID HAIRCUT = id("b1");
    static final UUID COLOR = id("b2");
    static final UUID CONSULT = id("b3");
    static final UUID[] C = new UUID[17];

    static {
        for (int i = 1; i <= 16; i++) {
            C[i] = id("c" + String.format("%02d", i));
        }
    }

    static UUID id(String suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + "0".repeat(12 - suffix.length()) + suffix);
    }

    private static int seq;

    static VisitFact fact(int client, UUID master, AppointmentStatus status, String start, UUID service, int minutes,
                          int price, int discount) {
        Instant s = Instant.parse(start);
        String name = service.equals(HAIRCUT) ? "Haircut" : service.equals(COLOR) ? "Color" : "Consultation";
        return new VisitFact(id("f" + String.format("%03d", ++seq)), master, service, C[client], status, s,
                s.plusSeconds(60L * minutes), price, discount, minutes, "GEL", name, "CONSOLE", s.minusSeconds(86_400));
    }

    /** A 60-minute Haircut at 10:00Z of {@code date}. */
    static VisitFact haircut(int client, UUID master, AppointmentStatus status, String date) {
        return fact(client, master, status, date + "T10:00:00Z", HAIRCUT, 60, 8000, 0);
    }

    static List<VisitFact> facts() {
        seq = 0;
        List<VisitFact> f = new ArrayList<>();
        // ---- history before W ----
        f.add(haircut(1, M1, COMPLETED, "2026-07-01"));
        f.add(haircut(1, M1, COMPLETED, "2026-08-01"));
        f.add(haircut(2, M2, COMPLETED, "2026-08-20"));
        f.add(haircut(4, M1, COMPLETED, "2026-06-10"));
        f.add(haircut(4, M1, COMPLETED, "2026-07-10"));
        f.add(haircut(4, M1, COMPLETED, "2026-08-09"));
        f.add(haircut(6, M2, COMPLETED, "2025-12-01"));
        f.add(haircut(13, M1, COMPLETED, "2026-08-15"));
        f.add(haircut(14, M2, COMPLETED, "2025-09-01"));
        // Starts before Wi (2026-08-31 23:30 local), ends inside it: 30 minutes of it are in Wi.
        f.add(fact(13, M2, NO_SHOW, "2026-08-31T19:30:00Z", HAIRCUT, 60, 8000, 0));
        // ---- COMPLETED in W (16) ----
        f.add(haircut(1, M1, COMPLETED, "2026-09-01"));
        f.add(haircut(2, M1, COMPLETED, "2026-09-03"));
        f.add(haircut(3, M2, COMPLETED, "2026-09-02"));
        f.add(fact(3, M2, COMPLETED, "2026-09-16T10:00:00Z", COLOR, 120, 20000, 2000));
        f.add(haircut(4, M1, COMPLETED, "2026-09-08"));
        f.add(haircut(5, M1, COMPLETED, "2026-09-04"));
        f.add(haircut(5, M2, COMPLETED, "2026-09-25"));
        f.add(fact(6, M2, COMPLETED, "2026-09-09T10:00:00Z", COLOR, 120, 20000, 0));
        f.add(haircut(7, M1, COMPLETED, "2026-09-10"));
        f.add(fact(7, M1, COMPLETED, "2026-09-11T10:00:00Z", CONSULT, 30, 0, 0));
        f.add(haircut(7, M1, COMPLETED, "2026-09-21"));
        f.add(fact(8, M2, COMPLETED, "2026-09-02T12:00:00Z", HAIRCUT, 60, 8000, 500));
        f.add(haircut(9, M1, COMPLETED, "2026-09-15"));
        f.add(fact(10, M3, COMPLETED, "2026-09-17T10:00:00Z", CONSULT, 30, 0, 0));
        f.add(haircut(11, M2, COMPLETED, "2026-09-18"));
        f.add(fact(12, M1, COMPLETED, "2026-09-10T12:00:00Z", HAIRCUT, 60, 8000, 0));
        // ---- other visits in W ----
        f.add(haircut(9, M1, NO_SHOW, "2026-09-22"));
        f.add(haircut(13, M2, NO_SHOW, "2026-09-23"));
        f.add(haircut(2, M1, CONFIRMED, "2026-09-28"));
        f.add(haircut(14, M2, PENDING, "2026-09-29"));
        // Starts in W (2026-09-30 23:30 local), ends after Wi: 30 minutes of it are in Wi.
        f.add(fact(3, M1, CONFIRMED, "2026-09-30T19:30:00Z", HAIRCUT, 60, 8000, 0));
        f.add(haircut(11, M1, CANCELLED_BY_CLIENT, "2026-09-24"));
        f.add(haircut(5, M2, CANCELLED_BY_BUSINESS, "2026-09-26"));
        // ---- after Wi: 2026-09-30T21:00Z is 2026-10-01 01:00 local, outside W ----
        f.add(fact(12, M1, COMPLETED, "2026-09-30T21:00:00Z", HAIRCUT, 60, 8000, 0));
        return f;
    }

    static WeeklyInterval weekly(UUID master, int weekday, int start, int end) {
        return new WeeklyInterval(UUID.randomUUID(), master, weekday, start, end);
    }

    static ScheduleExceptionView exception(UUID master, String date, String kind, Integer start, Integer end) {
        return new ScheduleExceptionView(UUID.randomUUID(), master, LocalDate.parse(date), kind, start, end, null);
    }

    /**
     * M1 Mon-Fri 10:00-18:00 (480 min), DAY_OFF on Mon 2026-09-14, an ignored "VACATION" on 2026-09-15.
     * M2 Tue-Sat 09:00-13:00 (240 min), SHIFT 10:00-12:00 (120 min) on Sat 2026-09-19.
     * M3 (inactive) Mon-Sun 08:00-20:00: not in S, contributes nothing.
     */
    static MetricsCalculator.Schedule schedule() {
        List<WeeklyInterval> m1 = new ArrayList<>();
        List<WeeklyInterval> m2 = new ArrayList<>();
        List<WeeklyInterval> m3 = new ArrayList<>();
        for (int d = 1; d <= 5; d++) {
            m1.add(weekly(M1, d, 600, 1080));
        }
        for (int d = 2; d <= 6; d++) {
            m2.add(weekly(M2, d, 540, 780));
        }
        for (int d = 1; d <= 7; d++) {
            m3.add(weekly(M3, d, 480, 1200));
        }
        return new MetricsCalculator.Schedule(
                Set.of(M1, M2),
                Map.of(M1, m1, M2, m2, M3, m3),
                Map.of(
                        M1, List.of(exception(M1, "2026-09-14", "DAY_OFF", null, null),
                                exception(M1, "2026-09-15", "VACATION", null, null)),
                        M2, List.of(exception(M2, "2026-09-19", "SHIFT", 600, 720)))
        );
    }

    static MetricView metric(MetricsCalculator.Result result, String key) {
        return result.metrics().stream().filter(m -> m.key().equals(key)).findFirst().orElseThrow();
    }

    private static MetricsCalculator.Result tenant() {
        return MetricsCalculator.compute(facts(), schedule(), ZONE, WS, WE, T, null);
    }

    private static MetricsCalculator.Result master1() {
        return MetricsCalculator.compute(facts(), schedule(), ZONE, WS, WE, T, M1);
    }

    private static void assertValue(double expected, int sample, MetricView m) {
        assertFalse(m.insufficientData(), m.key() + " should have enough data: " + m.explanation());
        assertEquals(expected, m.value(), EPS, m.key());
        assertEquals(sample, m.sampleSize(), m.key() + " sample size");
    }

    /**
     * Available minutes (S = {M1, M2}; September 2026 has Mon x4, Tue x5, Wed x5, Thu x4, Fri x4, Sat x4, Sun x4):
     * M1 Mon-Fri = 22 days x 480 = 10560, minus the DAY_OFF Monday 480 = 10080 (VACATION ignored);
     * M2 Tue-Sat = 22 days x 240 = 5280, Saturday 19th 120 instead of 240 = 5160; total 15240, 2 exceptions applied.
     * Booked minutes in Wi of occupying visits of M1/M2: COMPLETED in W 1020 - M3's 30 = 990; NO_SHOW 60 + 60 = 120;
     * CONFIRMED 2026-09-28 60; PENDING 60; the visit crossing the start 30; the one crossing the end 30;
     * total 1290. Cancellations and the visit after Wi add 0. 1290 / 15240.
     */
    @Test
    void slotUtilization() {
        MetricView m = metric(tenant(), "SLOT_UTILIZATION");
        assertValue(1290.0 / 15240.0, 15240, m);
        assertTrue(m.explanation().contains("1290"), m.explanation());
        assertTrue(m.explanation().contains("15240"), m.explanation());
        assertTrue(m.explanation().contains("2 schedule exception"), m.explanation());
    }

    /**
     * P (COMPLETED in W): C1..C12 = 12 (C10 with the inactive M3 counts; C12's second visit is after Wi).
     * R (>= 2 COMPLETED starting before the end of Wi): C1 (3), C2 (2), C3 (2), C4 (4), C5 (2), C6 (2), C7 (3) = 7.
     * 7 / 12.
     */
    @Test
    void repeatRate() {
        assertValue(7.0 / 12.0, 12, metric(tenant(), "REPEAT_RATE"));
    }

    /**
     * Per-client median gaps (days between local dates) of COMPLETED before the end of Wi, clients of P with >= 2:
     * C1 31,31 -> 31; C2 14 -> 14; C3 14 -> 14; C4 30,30,30 -> 30; C5 21 -> 21; C6 2025-12-01..2026-09-09 = 282;
     * C7 1,10 -> 5.5. Sorted 5.5, 14, 14, 21, 30, 31, 282: median 21 over 7 clients.
     */
    @Test
    void returnInterval() {
        assertValue(21.0, 7, metric(tenant(), "RETURN_INTERVAL"));
    }

    /**
     * D (in W, start before T, occupying): COMPLETED 16 + NO_SHOW 2 + CONFIRMED 2 (09-28 and the 09-30 one crossing
     * the end) + PENDING 1 = 21; N = 2. 2 / 21, and 3 of D are not marked yet.
     */
    @Test
    void noShowRate() {
        MetricView m = metric(tenant(), "NO_SHOW_RATE");
        assertValue(2.0 / 21.0, 21, m);
        assertTrue(m.explanation().contains("3 not marked"), m.explanation());
    }

    /**
     * C (COMPLETED in W) = 16 visits: 12 Haircuts 12 x 8000 - 500 = 95500; 2 Colors 20000 - 2000 + 20000 = 38000;
     * 2 Consultations 0. Value 133500 over 12 x 60 + 2 x 120 + 2 x 30 = 1020 min = 17 h. 133500 / 17 per hour.
     * Sample = completed minutes (minimum 1 minute).
     */
    @Test
    void revenuePerHour() {
        assertValue(133500.0 / 17.0, 1020, metric(tenant(), "REVENUE_PER_HOUR"));
    }

    /**
     * K (COMPLETED in [T - 365 d, T]): every client but C14 (only 2025-09-01) = 13. Typical intervals of clients
     * with >= 2 COMPLETED up to T: C1 31, C2 14, C3 14, C4 30, C5 21, C6 282, C7 5.5, C12 (09-10 and 10-01 local)
     * 21; tenant median of the 8 = (21 + 21) / 2 = 21 for C8..C11 and C13. Days since the last COMPLETED to 10-05:
     * C1 34 (<46.5), C2 32 (>= 28 HIGH), C3 19, C4 27, C5 10, C6 26, C7 14 (>= 11 HIGH), C8 33 (>= 31.5 WARNING,
     * < 42), C9 20, C10 18, C11 17, C12 4, C13 51 (>= 42 HIGH). HIGH 3, WARNING 1: 3 / 13.
     */
    @Test
    void churnRisk() {
        MetricsCalculator.Result result = tenant();
        assertValue(3.0 / 13.0, 13, metric(result, "CHURN_RISK"));
        Map<UUID, MetricsCalculator.ChurnAssessment> byClient = new java.util.HashMap<>();
        result.churn().forEach(a -> byClient.put(a.clientId(), a));
        assertEquals(13, byClient.size());
        assertEquals(MetricsCalculator.ChurnLevel.HIGH, byClient.get(C[2]).level());
        assertEquals(MetricsCalculator.ChurnLevel.HIGH, byClient.get(C[7]).level());
        assertEquals(MetricsCalculator.ChurnLevel.HIGH, byClient.get(C[13]).level());
        assertEquals(MetricsCalculator.ChurnLevel.WARNING, byClient.get(C[8]).level());
        assertEquals(51, byClient.get(C[13]).daysSinceLast());
        assertEquals(21.0, byClient.get(C[13]).typicalDays(), EPS);
        assertTrue(byClient.get(C[13]).typicalFromTenant());
        assertEquals(5.5, byClient.get(C[7]).typicalDays(), EPS);
        assertFalse(byClient.get(C[7]).typicalFromTenant());
    }

    /** The no-show figures and the per-service hours the signals read come from the same pass. */
    @Test
    void figuresForSignals() {
        MetricsCalculator.Result result = tenant();
        assertEquals(1290, result.bookedMinutes());
        assertEquals(15240, result.availableMinutes());
        assertEquals(2, result.noShows());
        assertEquals(21, result.dueVisits());
        // Haircut 12 visits 95500 over 720 min; Color 2 visits 38000 over 240 min; Consultation 2 visits 0 over 60 min.
        Map<UUID, MetricsCalculator.ServiceHours> services = new java.util.HashMap<>();
        result.services().forEach(s -> services.put(s.serviceId(), s));
        assertEquals(new MetricsCalculator.ServiceHours(HAIRCUT, "Haircut", 12, 95500, 720), services.get(HAIRCUT));
        assertEquals(new MetricsCalculator.ServiceHours(COLOR, "Color", 2, 38000, 240), services.get(COLOR));
        assertEquals(new MetricsCalculator.ServiceHours(CONSULT, "Consultation", 2, 0, 60), services.get(CONSULT));
    }

    // ---- slice of master M1: the same definitions on the facts of M1 only ----

    /** S = {M1}: booked 9 COMPLETED (8 x 60 + 30 = 510) + NO_SHOW 60 + CONFIRMED 60 + the end-crossing 30 = 660; 10080. */
    @Test
    void masterSliceSlotUtilization() {
        assertValue(660.0 / 10080.0, 10080, metric(master1(), "SLOT_UTILIZATION"));
    }

    /** P = C1, C2, C4, C5, C7, C9, C12 = 7 < 10: not enough data, no value. */
    @Test
    void masterSliceRepeatRateIsInsufficient() {
        MetricView m = metric(master1(), "REPEAT_RATE");
        assertTrue(m.insufficientData());
        assertNull(m.value());
        assertEquals(7, m.sampleSize());
    }

    /** Clients of P with >= 2 M1 visits before the end of Wi: C1 31, C4 30, C7 5.5 -> median 30 over 3 clients. */
    @Test
    void masterSliceReturnInterval() {
        assertValue(30.0, 3, metric(master1(), "RETURN_INTERVAL"));
    }

    /** D = 9 COMPLETED + 1 NO_SHOW + 2 CONFIRMED = 12, N = 1. */
    @Test
    void masterSliceNoShowRate() {
        assertValue(1.0 / 12.0, 12, metric(master1(), "NO_SHOW_RATE"));
    }

    /** 9 COMPLETED: 8 Haircuts 64000 + 1 Consultation 0, over 8 x 60 + 30 = 510 min = 8.5 h. */
    @Test
    void masterSliceRevenuePerHour() {
        assertValue(64000.0 / 8.5, 510, metric(master1(), "REVENUE_PER_HOUR"));
    }

    /**
     * K = C1, C2, C4, C5, C7, C9, C12, C13 = 8. Typical with >= 2 M1 visits: C1 31, C4 30, C7 5.5, C12 21; slice
     * median (21 + 30) / 2 = 25.5 for C2, C5, C9, C13. C7 14 >= 11 HIGH; C13 51 >= 2 x 25.5 = 51 HIGH (boundary);
     * C2 32 and C5 31 < 38.25. 2 / 8.
     */
    @Test
    void masterSliceChurnRisk() {
        assertValue(2.0 / 8.0, 8, metric(master1(), "CHURN_RISK"));
    }

    // ---- same-day visits are one visit day (ADR 0001, review decision M1) ----

    /**
     * The golden facts plus C15 with two COMPLETED Haircuts on 2026-09-14 (08:00Z and 12:00Z, M2: one visit day) and
     * C16 with two on 2026-09-03 (06:00Z, 13:00Z = 10:00 and 17:00 local, M1) and one on 2026-09-17 (M1): visit
     * days 09-03 and 09-17.
     */
    static List<VisitFact> factsWithSameDayVisits() {
        List<VisitFact> f = facts();
        f.add(fact(15, M2, COMPLETED, "2026-09-14T08:00:00Z", HAIRCUT, 60, 8000, 0));
        f.add(fact(15, M2, COMPLETED, "2026-09-14T12:00:00Z", HAIRCUT, 60, 8000, 0));
        f.add(fact(16, M1, COMPLETED, "2026-09-03T06:00:00Z", HAIRCUT, 60, 8000, 0));
        f.add(fact(16, M1, COMPLETED, "2026-09-03T13:00:00Z", HAIRCUT, 60, 8000, 0));
        f.add(fact(16, M1, COMPLETED, "2026-09-17T06:00:00Z", HAIRCUT, 60, 8000, 0));
        return f;
    }

    private static MetricsCalculator.Result tenantWithSameDayVisits() {
        return MetricsCalculator.compute(factsWithSameDayVisits(), schedule(), ZONE, WS, WE, T, null);
    }

    /**
     * P = 12 + C15 + C16 = 14. R = the 7 before + C16 (two visit days) = 8; C15's second visit is the same day, so
     * C15 is not a repeat client. 8 / 14 (counting visits instead of days would give 9 / 14).
     */
    @Test
    void sameDayVisitsAreOneVisitDayForRepeatRate() {
        assertValue(8.0 / 14.0, 14, metric(tenantWithSameDayVisits(), "REPEAT_RATE"));
    }

    /**
     * C16's gap is between visit days 09-03 and 09-17 = 14 (not the gaps 0 and 14, median 7); C15 has one visit day
     * and no interval. Medians 5.5, 14, 14, 14, 21, 30, 31, 282: (14 + 21) / 2 = 17.5 over 8 clients.
     */
    @Test
    void sameDayVisitsAreOneVisitDayForReturnInterval() {
        assertValue(17.5, 8, metric(tenantWithSameDayVisits(), "RETURN_INTERVAL"));
    }

    /**
     * K = 13 + C15 + C16 = 15. Typical intervals: the 8 before + C16 14 -> 5.5, 14, 14, 14, 21, 21, 30, 31, 282,
     * tenant median 21. C15 has no interval of its own (typical 0 would make it HIGH at once): tenant 21, 21 days
     * since 09-14 -> OK. C16: 18 days since 09-17 < 21 -> OK. HIGH stays C2, C7, C13: 3 / 15.
     */
    @Test
    void sameDayVisitsAreOneVisitDayForChurnRisk() {
        MetricsCalculator.Result result = tenantWithSameDayVisits();
        assertValue(3.0 / 15.0, 15, metric(result, "CHURN_RISK"));
        MetricsCalculator.ChurnAssessment c15 = result.churn().stream()
                .filter(a -> a.clientId().equals(C[15])).findFirst().orElseThrow();
        assertTrue(c15.typicalFromTenant());
        assertEquals(21.0, c15.typicalDays(), EPS);
        assertEquals(MetricsCalculator.ChurnLevel.OK, c15.level());
        MetricsCalculator.ChurnAssessment c16 = result.churn().stream()
                .filter(a -> a.clientId().equals(C[16])).findFirst().orElseThrow();
        assertEquals(14.0, c16.typicalDays(), EPS);
        assertFalse(c16.typicalFromTenant());
    }

    @Test
    void everyMetricCarriesTheSliceAndTheSixKeysInOrder() {
        List<String> keys = master1().metrics().stream().map(MetricView::key).toList();
        assertEquals(List.of("SLOT_UTILIZATION", "REPEAT_RATE", "RETURN_INTERVAL", "NO_SHOW_RATE", "REVENUE_PER_HOUR",
                "CHURN_RISK"), keys);
        assertTrue(master1().metrics().stream().allMatch(m -> M1.equals(m.specialistId())));
        assertTrue(tenant().metrics().stream().allMatch(m -> m.specialistId() == null));
    }
}

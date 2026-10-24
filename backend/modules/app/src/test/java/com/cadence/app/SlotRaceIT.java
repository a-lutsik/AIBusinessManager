package com.cadence.app;

import com.cadence.app.seed.DemoIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Concurrent public bookings against the real schema. The application-level free-slot check cannot stop two requests
 * that run it at the same moment, so the database constraints decide: the GiST exclusion constraint
 * {@code no_overlapping_active_appointments} for one slot, the unique {@code client_phone_uq} for one new phone. The
 * losers must get the normal domain answers (409 SLOT_OCCUPIED, or success reusing the client), never a 5xx.
 *
 * <p>Runs with {@link RelaxedPublicRateLimits}: the burst comes from one peer address and the same few phones, which
 * the production limits (5 slot reads a minute per IP, 3 bookings a day per phone) would refuse. Slots from +7 and +11 days onwards are used so other ITs sharing the
 * database (they book the first free slot from +2/+3 days) cannot occupy the chosen ones.
 */
@SpringBootTest
@RelaxedPublicRateLimits
@AutoConfigureMockMvc
class SlotRaceIT extends AbstractTimescaleIT {

    @Autowired
    MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private record Outcome(int status, String code, String detail) {
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
    }

    /** Free slots from {@code fromDaysAhead} to the end of the 28-day booking horizon. */
    private List<String> freeSlots(int fromDaysAhead) throws Exception {
        return TestSlots.free(mvc, mapper, DemoIds.HAIRCUT, DemoIds.NINO, fromDaysAhead);
    }

    /** Never throws: a failure inside the request (MockMvc rethrows unhandled exceptions) is reported as status 500. */
    private Outcome book(String slot, String phone, String name) {
        try {
            MvcResult res = mvc.perform(post("/api/public/tenants/lumen-studio/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "serviceId": "%s",
                                      "specialistId": "%s",
                                      "serviceStart": "%s",
                                      "phone": "%s",
                                      "name": "%s",
                                      "locale": "en",
                                      "marketingConsent": false
                                    }
                                    """.formatted(DemoIds.HAIRCUT, DemoIds.NINO, slot, phone, name)))
                    .andReturn();
            int status = res.getResponse().getStatus();
            byte[] body = res.getResponse().getContentAsByteArray();
            String code = null;
            if (status != 200 && body.length > 0) {
                JsonNode json = mapper.readTree(body);
                code = json.hasNonNull("code") ? json.get("code").asText() : null;
            }
            return new Outcome(status, code, new String(body));
        } catch (Throwable t) {
            return new Outcome(500, "EXCEPTION", t.toString());
        }
    }

    /** Runs all tasks at once: every worker is parked on the latch until all are ready, then released together. */
    private <T> List<T> fireTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all workers started").isTrue();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long count(String sql, Object... params) throws SQLException {
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void concurrentBookingsOfOneSlotYieldOneWinnerAndSlotOccupiedForTheRest() throws Exception {
        List<String> slots = freeSlots(7);
        assertThat(slots).as("demo schedule must offer a free slot between +7 and +28 days").isNotEmpty();
        String slot = slots.get(0);
        int contenders = 8;

        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String phone = "+995555" + String.format("%06d", 810000 + i);
            String name = "Racer " + i;
            tasks.add(() -> book(slot, phone, name));
        }
        List<Outcome> outcomes = fireTogether(tasks);

        assertThat(outcomes).as("no 5xx: %s", outcomes).noneMatch(o -> o.status() >= 500);
        assertThat(outcomes.stream().filter(o -> o.status() == 200).count()).as("winners: %s", outcomes).isEqualTo(1);
        List<Outcome> losers = outcomes.stream().filter(o -> o.status() != 200).toList();
        assertThat(losers).hasSize(contenders - 1);
        assertThat(losers).as("losers: %s", losers).allSatisfy(o -> {
            assertThat(o.status()).isEqualTo(409);
            assertThat(o.code()).isEqualTo("SLOT_OCCUPIED");
        });

        LocalDateTime utcStart = LocalDateTime.ofInstant(Instant.parse(slot), ZoneOffset.UTC);
        assertThat(count("select count(*) from appointment where specialist_id = ? and service_start = ?",
                DemoIds.NINO, utcStart)).isEqualTo(1);
    }

    @Test
    void concurrentFirstBookingsWithTheSameNewPhoneCreateOneClient() throws Exception {
        List<String> slots = freeSlots(11);
        // Consecutive slots overlap through duration and buffers, so take starts at least two hours apart.
        List<String> distinct = new ArrayList<>();
        Instant last = null;
        for (String slot : slots) {
            Instant start = Instant.parse(slot);
            if (last == null || !start.isBefore(last.plus(Duration.ofHours(2)))) {
                distinct.add(slot);
                last = start;
            }
            if (distinct.size() == 6) {
                break;
            }
        }
        assertThat(distinct).as("demo schedule must offer six well-separated free slots between +11 and +28 days").hasSize(6);
        String phone = "+995555777001";

        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < distinct.size(); i++) {
            String slot = distinct.get(i);
            String name = "Same Phone " + i;
            tasks.add(() -> book(slot, phone, name));
        }
        List<Outcome> outcomes = fireTogether(tasks);

        assertThat(outcomes).as("outcomes: %s", outcomes).allSatisfy(o -> assertThat(o.status()).isEqualTo(200));
        assertThat(count("select count(*) from client where tenant_id = ? and normalized_phone = ?",
                DemoIds.LUMEN, phone)).isEqualTo(1);
        assertThat(count("""
                select count(*) from appointment
                where tenant_id = ? and client_id = (select id from client where tenant_id = ? and normalized_phone = ?)
                """, DemoIds.LUMEN, DemoIds.LUMEN, phone)).isEqualTo(6);
    }
}

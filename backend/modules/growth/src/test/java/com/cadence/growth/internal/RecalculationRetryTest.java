package com.cadence.growth.internal;

import com.cadence.platform.tenancy.TenantContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review m1: a serialization failure (40001) or deadlock (40P01) is retried, at most three attempts; nothing else is. */
class RecalculationRetryTest {

    private final AtomicInteger writes = new AtomicInteger();

    @BeforeEach
    void tenant() {
        TenantContext.set(UUID.randomUUID());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static RuntimeException failure(String sqlState) {
        return new RuntimeException("jOOQ", new SQLException("could not serialize", sqlState));
    }

    /** A service whose write step fails with {@code failures[i]} on attempt i (null = success). */
    private GrowthServiceImpl service(RuntimeException... failures) {
        MetricsRecalculation recalculation = new MetricsRecalculation(null, null, null, null, null, null, null,
                Clock.systemUTC(), Duration.ZERO) {
            @Override
            public Computation read(UUID tenantId) {
                return null;
            }

            @Override
            public void write(Computation c) {
                int attempt = writes.getAndIncrement();
                if (attempt < failures.length && failures[attempt] != null) {
                    throw failures[attempt];
                }
            }
        };
        MetricSnapshotRepository noSnapshots = new MetricSnapshotRepository(null) {
            @Override
            Map<String, Record> latestOfCurrentWindow(UUID specialistId) {
                return Map.of();
            }
        };
        return new GrowthServiceImpl(recalculation, null, null, noSnapshots, null, Clock.systemUTC(),
                org.springframework.transaction.support.TransactionOperations.withoutTransaction());
    }

    @Test
    void serializationFailuresAndDeadlocksAreRetried() {
        assertEquals(6, service(failure("40001"), failure("40P01")).recalculate().size(), "placeholders after success");
        assertEquals(3, writes.get());
    }

    @Test
    void retriesStopAfterThreeAttempts() {
        RuntimeException last = failure("40001");
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service(failure("40001"), failure("40001"), last).recalculate());
        assertSame(last, thrown);
        assertEquals(GrowthServiceImpl.MAX_ATTEMPTS, writes.get());
    }

    @Test
    void otherFailuresAreNotRetried() {
        RuntimeException unique = failure("23505");
        assertSame(unique, assertThrows(RuntimeException.class, () -> service(unique).recalculate()));
        assertEquals(1, writes.get());
        assertTrue(GrowthServiceImpl.retryable(failure("40001")));
        assertFalse(GrowthServiceImpl.retryable(new IllegalStateException("unknown journal event type")));
    }
}

package com.cadence.app.ratelimit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory sliding-window counters keyed by an arbitrary string.
 *
 * <p><b>Per instance.</b> State lives in the memory of this JVM and is lost on restart. That is deliberate for the
 * single-VPS deployment; running several instances behind a balancer multiplies every limit by the instance count
 * and would need a shared store (Redis or the database) instead.
 *
 * <p><b>Bounded.</b> At most {@code maxTrackedKeys} counters exist. Expired counters are swept periodically and on
 * demand when the bound is reached; if it is still reached, a request that would need a new counter is refused
 * (fail closed, {@link Decision#capacityExhausted()}), so a flood of distinct keys cannot exhaust memory.
 *
 * <p>All limits of one call are checked first and recorded only when every one of them allows the request, so a
 * refused request consumes nothing.
 */
@Component
public class SlidingWindowRateLimiter {

    /** Minimum time between two audit-worthy rejections of the same key, so a flood cannot flood the audit log. */
    static final Duration AUDIT_COOLDOWN = Duration.ofMinutes(1);
    private static final long CAPACITY_RETRY_SECONDS = 60;
    private static final long CAPACITY_SWEEP_INTERVAL_MS = 1_000;

    /** One limit: at most {@code max} hits of {@code key} within {@code window}. */
    public record Limit(String name, String key, int max, Duration window) {
    }

    /**
     * @param allowed            the request may proceed and was recorded
     * @param violated           the limit that refused the request ({@code null} when allowed)
     * @param retryAfterSeconds  seconds until a retry can succeed (0 when allowed)
     * @param capacityExhausted  refused because the key table is full, not because {@code violated} was exceeded
     * @param auditDue           this rejection should be written to the audit log (rate-limited per key)
     */
    public record Decision(boolean allowed, Limit violated, long retryAfterSeconds, boolean capacityExhausted, boolean auditDue) {

        static Decision allow() {
            return new Decision(true, null, 0, false, false);
        }
    }

    private static final class Bucket {
        final long windowMs;
        final ArrayDeque<Long> hits = new ArrayDeque<>();
        long lastAuditMs = Long.MIN_VALUE;

        Bucket(long windowMs) {
            this.windowMs = windowMs;
        }

        void expire(long now) {
            while (!hits.isEmpty() && now - hits.peekFirst() >= windowMs) {
                hits.removeFirst();
            }
        }
    }

    private final Map<String, Bucket> buckets = new HashMap<>();
    private final Clock clock;
    private final int maxTrackedKeys;
    private long lastCapacitySweepMs = -CAPACITY_SWEEP_INTERVAL_MS;

    @Autowired
    public SlidingWindowRateLimiter(Clock clock, RateLimitProperties properties) {
        this(clock, properties.maxTrackedKeys());
    }

    public SlidingWindowRateLimiter(Clock clock, int maxTrackedKeys) {
        this.clock = clock;
        this.maxTrackedKeys = maxTrackedKeys;
    }

    public synchronized Decision tryAcquire(List<Limit> limits) {
        long now = clock.millis();
        int newKeys = 0;
        for (Limit limit : limits) {
            Bucket bucket = buckets.get(limit.key());
            if (bucket == null) {
                newKeys++;
                if (buckets.size() + newKeys > maxTrackedKeys) {
                    sweepForCapacity(now);
                    if (buckets.size() + newKeys > maxTrackedKeys) {
                        return new Decision(false, limit, CAPACITY_RETRY_SECONDS, true, false);
                    }
                }
                continue;
            }
            bucket.expire(now);
            if (bucket.hits.size() >= limit.max()) {
                long retryMs = bucket.hits.peekFirst() + bucket.windowMs - now;
                boolean auditDue = bucket.lastAuditMs == Long.MIN_VALUE
                        || now - bucket.lastAuditMs >= AUDIT_COOLDOWN.toMillis();
                if (auditDue) {
                    bucket.lastAuditMs = now;
                }
                return new Decision(false, limit, Math.max(1, (retryMs + 999) / 1000), false, auditDue);
            }
        }
        for (Limit limit : limits) {
            buckets.computeIfAbsent(limit.key(), k -> new Bucket(limit.window().toMillis())).hits.addLast(now);
        }
        return Decision.allow();
    }

    /** Drops expired counters; also invoked on a schedule. */
    @Scheduled(fixedDelay = 60_000)
    public synchronized void sweep() {
        long now = clock.millis();
        buckets.values().removeIf(b -> {
            b.expire(now);
            return b.hits.isEmpty();
        });
    }

    synchronized int trackedKeys() {
        return buckets.size();
    }

    private void sweepForCapacity(long now) {
        if (now - lastCapacitySweepMs < CAPACITY_SWEEP_INTERVAL_MS) {
            return;
        }
        lastCapacitySweepMs = now;
        sweep();
    }
}

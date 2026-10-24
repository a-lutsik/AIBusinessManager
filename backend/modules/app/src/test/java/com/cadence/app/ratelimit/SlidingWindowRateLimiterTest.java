package com.cadence.app.ratelimit;

import com.cadence.app.ratelimit.SlidingWindowRateLimiter.Decision;
import com.cadence.app.ratelimit.SlidingWindowRateLimiter.Limit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlidingWindowRateLimiterTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private MutableClock clock;
    private SlidingWindowRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        limiter = new SlidingWindowRateLimiter(clock, 100);
    }

    private static Limit slots(String ip, int max) {
        return new Limit("slots_per_ip", "slots:" + ip, max, MINUTE);
    }

    private Decision hit(Limit... limits) {
        return limiter.tryAcquire(List.of(limits));
    }

    @Test
    void allowsUpToTheMaximumThenRefusesWithRetryAfter() {
        for (int i = 0; i < 5; i++) {
            assertThat(hit(slots("a", 5)).allowed()).as("hit %d", i + 1).isTrue();
        }
        Decision sixth = hit(slots("a", 5));
        assertThat(sixth.allowed()).isFalse();
        assertThat(sixth.violated().name()).isEqualTo("slots_per_ip");
        assertThat(sixth.retryAfterSeconds()).isEqualTo(60);
        assertThat(sixth.capacityExhausted()).isFalse();
    }

    @Test
    void retryAfterShrinksAsTheWindowAdvancesAndIsAtLeastOneSecond() {
        for (int i = 0; i < 5; i++) {
            hit(slots("a", 5));
        }
        clock.advance(Duration.ofSeconds(45));
        assertThat(hit(slots("a", 5)).retryAfterSeconds()).isEqualTo(15);
        clock.advance(Duration.ofMillis(14_900));
        assertThat(hit(slots("a", 5)).retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void windowSlidesInsteadOfResettingAtFixedBoundaries() {
        hit(slots("a", 2));
        clock.advance(Duration.ofSeconds(40));
        hit(slots("a", 2));
        assertThat(hit(slots("a", 2)).allowed()).isFalse();
        clock.advance(Duration.ofSeconds(21)); // the first hit is now 61s old, the second 21s
        assertThat(hit(slots("a", 2)).allowed()).isTrue();
        assertThat(hit(slots("a", 2)).allowed()).isFalse();
    }

    @Test
    void aHitExactlyOneWindowOldNoLongerCounts() {
        hit(slots("a", 1));
        clock.advance(MINUTE);
        assertThat(hit(slots("a", 1)).allowed()).isTrue();
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 5; i++) {
            hit(slots("a", 5));
        }
        assertThat(hit(slots("a", 5)).allowed()).isFalse();
        assertThat(hit(slots("b", 5)).allowed()).isTrue();
    }

    @Test
    void aRefusedRequestConsumesNothingOfTheOtherLimits() {
        Limit phone = new Limit("bookings_per_phone_day", "phone:+1", 1, Duration.ofDays(1));
        Limit tenant = new Limit("bookings_per_tenant_hour", "tenant:x", 5, Duration.ofHours(1));
        assertThat(hit(phone, tenant).allowed()).isTrue();
        Decision refused = hit(phone, tenant);
        assertThat(refused.violated()).isEqualTo(phone);
        // The tenant counter saw only the first attempt: four more are still allowed.
        for (int i = 0; i < 4; i++) {
            assertThat(hit(new Limit("bookings_per_phone_day", "phone:+" + (i + 2), 1, Duration.ofDays(1)), tenant).allowed())
                    .as("tenant hit %d", i + 2).isTrue();
        }
        assertThat(hit(new Limit("bookings_per_phone_day", "phone:+9", 1, Duration.ofDays(1)), tenant).violated())
                .isEqualTo(tenant);
    }

    @Test
    void differentWindowsPerLimitAreHonoured() {
        Limit day = new Limit("bookings_per_phone_day", "phone:+1", 3, Duration.ofDays(1));
        for (int i = 0; i < 3; i++) {
            assertThat(hit(day).allowed()).isTrue();
            clock.advance(Duration.ofHours(5));
        }
        assertThat(hit(day).allowed()).isFalse();
        clock.advance(Duration.ofHours(10)); // first hit is now 25h old
        assertThat(hit(day).allowed()).isTrue();
    }

    @Test
    void auditIsDueOncePerCooldownPerKey() {
        hit(slots("a", 1));
        assertThat(hit(slots("a", 1)).auditDue()).isTrue();
        assertThat(hit(slots("a", 1)).auditDue()).isFalse();
        clock.advance(Duration.ofSeconds(30));
        assertThat(hit(slots("a", 1)).auditDue()).isFalse();
        clock.advance(Duration.ofSeconds(31)); // cooldown over, but also the hit expired
        assertThat(hit(slots("a", 1)).allowed()).isTrue();
        assertThat(hit(slots("a", 1)).auditDue()).isTrue();
    }

    @Test
    void sweepDropsExpiredKeysOnly() {
        hit(slots("old", 5));
        clock.advance(Duration.ofSeconds(50));
        hit(slots("fresh", 5));
        clock.advance(Duration.ofSeconds(20));
        limiter.sweep();
        assertThat(limiter.trackedKeys()).isEqualTo(1);
        assertThat(hit(slots("fresh", 1)).allowed()).isFalse();
    }

    @Test
    void trackedKeysAreCappedAndFailClosedUntilOldOnesExpire() {
        SlidingWindowRateLimiter small = new SlidingWindowRateLimiter(clock, 3);
        for (int i = 0; i < 3; i++) {
            assertThat(small.tryAcquire(List.of(slots("k" + i, 5))).allowed()).isTrue();
        }
        Decision overflow = small.tryAcquire(List.of(slots("k3", 5)));
        assertThat(overflow.allowed()).isFalse();
        assertThat(overflow.capacityExhausted()).isTrue();
        assertThat(overflow.auditDue()).isFalse();
        assertThat(small.trackedKeys()).isEqualTo(3);
        // Known keys keep working while the table is full.
        assertThat(small.tryAcquire(List.of(slots("k0", 5))).allowed()).isTrue();
        // Expired keys are swept on demand and make room again.
        clock.advance(Duration.ofMinutes(2));
        assertThat(small.tryAcquire(List.of(slots("k3", 5))).allowed()).isTrue();
        assertThat(small.trackedKeys()).isEqualTo(1);
    }

    @Test
    void aMultiKeyRequestNeedingMoreRoomThanLeftIsRefusedWithoutPartialRecording() {
        SlidingWindowRateLimiter small = new SlidingWindowRateLimiter(clock, 2);
        small.tryAcquire(List.of(slots("a", 5)));
        Decision d = small.tryAcquire(List.of(slots("b", 5), slots("c", 5)));
        assertThat(d.capacityExhausted()).isTrue();
        assertThat(small.trackedKeys()).isEqualTo(1);
    }
}

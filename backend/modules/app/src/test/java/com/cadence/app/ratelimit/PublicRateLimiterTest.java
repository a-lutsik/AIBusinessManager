package com.cadence.app.ratelimit;

import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.jooq.tables.records.TenantRecord;
import com.cadence.platform.phone.PhoneNormalizer;
import com.cadence.platform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PublicRateLimiterTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String PHONE = "+995555123456";

    private MutableClock clock;
    private AuditLogger audit;
    private PublicRateLimiter limiter;
    private TenantRecord tenant;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        audit = mock(AuditLogger.class);
        RateLimitProperties props = RateLimitProperties.defaults();
        limiter = new PublicRateLimiter(props, new SlidingWindowRateLimiter(clock, props), new PhoneNormalizer(), audit);
        tenant = tenant(TENANT_ID, "lumen-studio");
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static TenantRecord tenant(UUID id, String slug) {
        TenantRecord record = new TenantRecord();
        record.setId(id);
        record.setSlug(slug);
        record.setCountryCode("GE");
        return record;
    }

    @Test
    void sixthSlotsRequestOfAnIpWithinAMinuteIsRefused() {
        for (int i = 0; i < 5; i++) {
            limiter.checkSlots("203.0.113.9");
        }
        assertThatThrownBy(() -> limiter.checkSlots("203.0.113.9"))
                .isInstanceOfSatisfying(RateLimitedException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(429);
                    assertThat(ex.code()).isEqualTo("RATE_LIMITED");
                    assertThat(ex.retryAfterSeconds()).isEqualTo(60);
                    assertThat(ex.getMessage()).isNotBlank();
                });
        limiter.checkSlots("203.0.113.10"); // another IP is unaffected
        clock.advance(Duration.ofMinutes(1));
        limiter.checkSlots("203.0.113.9");
    }

    @Test
    void slotsRejectionIsAuditedWithMaskedIpAndTenantScope() {
        for (int i = 0; i < 5; i++) {
            limiter.checkSlots("203.0.113.9");
        }
        assertThatThrownBy(() -> limiter.checkSlots("203.0.113.9")).isInstanceOf(RateLimitedException.class);

        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq("public"), eq("PUBLIC"), eq("public.rate_limited"), eq("tenant"), eq(TENANT_ID),
                eq(null), after.capture(), eq("public-api"), eq(null));
        assertThat(after.getValue())
                .contains("\"limit\":\"slots_per_ip\"", "\"max\":5", "\"windowSeconds\":60", "\"ip\":\"203.0.113.0\"")
                .doesNotContain("203.0.113.9");
    }

    @Test
    void repeatedRejectionsOfOneKeyAreAuditedOncePerMinute() {
        for (int i = 0; i < 5; i++) {
            limiter.checkSlots("203.0.113.9");
        }
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> limiter.checkSlots("203.0.113.9")).isInstanceOf(RateLimitedException.class);
        }
        verify(audit, times(1)).record(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void fourthBookingForOnePhoneInADayIsRefusedWhateverTheFormatOfTheNumber() {
        limiter.checkBooking(tenant, "203.0.113.1", PHONE);
        limiter.checkBooking(tenant, "203.0.113.2", "555 12 34 56"); // national format, same E.164
        limiter.checkBooking(tenant, "203.0.113.3", "+995 555 123 456");
        assertThatThrownBy(() -> limiter.checkBooking(tenant, "203.0.113.4", "555123456"))
                .isInstanceOfSatisfying(RateLimitedException.class, ex -> {
                    assertThat(ex.getMessage()).contains("phone number").contains("3");
                    assertThat(ex.retryAfterSeconds()).isEqualTo(86_400);
                });
        clock.advance(Duration.ofDays(1));
        limiter.checkBooking(tenant, "203.0.113.4", PHONE);
    }

    @Test
    void phoneRejectionIsAuditedWithoutTheRawPhoneNumber() {
        for (int i = 0; i < 3; i++) {
            limiter.checkBooking(tenant, "203.0.113." + i, PHONE);
        }
        assertThatThrownBy(() -> limiter.checkBooking(tenant, "198.51.100.77", PHONE)).isInstanceOf(RateLimitedException.class);

        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq("public"), eq("PUBLIC"), eq("public.rate_limited"), eq("tenant"), eq(TENANT_ID),
                eq(null), after.capture(), eq("public-api"), eq(null));
        assertThat(after.getValue())
                .contains("\"limit\":\"bookings_per_phone_day\"", "\"phone\":\"+995*******56\"", "\"ip\":\"198.51.100.0\"")
                .doesNotContain("555123456").doesNotContain("123456").doesNotContain("198.51.100.77");
    }

    @Test
    void differentPhonesAreNotBlockedByThePhoneLimit() {
        for (int i = 0; i < 10; i++) {
            limiter.checkBooking(tenant, "203.0.113." + i, "+99555500000" + i);
        }
        verifyNoInteractions(audit);
    }

    @Test
    void twentyFirstBookingOfATenantInAnHourIsRefused() {
        for (int i = 0; i < 20; i++) {
            limiter.checkBooking(tenant, "10.1.0." + i, "+9955551000" + String.format("%02d", i));
        }
        assertThatThrownBy(() -> limiter.checkBooking(tenant, "10.1.0.99", "+995555100099"))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        ex -> assertThat(ex.getMessage()).contains("booking page"));
        // Another tenant has its own counter.
        TenantRecord other = tenant(UUID.randomUUID(), "aura-atelier");
        limiter.checkBooking(other, "10.1.0.99", "+995555100099");
        clock.advance(Duration.ofHours(1));
        limiter.checkBooking(tenant, "10.1.0.99", "+995555100099");
    }

    @Test
    void phoneCounterIsPerTenant() {
        TenantRecord other = tenant(UUID.randomUUID(), "aura-atelier");
        for (int i = 0; i < 3; i++) {
            limiter.checkBooking(tenant, "10.1.0." + i, PHONE);
        }
        limiter.checkBooking(other, "10.1.0.9", PHONE);
    }

    @Test
    void unparsablePhoneIsNotCountedPerPhoneOrTenantButStillPerIp() {
        for (int i = 0; i < 20; i++) {
            limiter.checkBooking(tenant, "203.0.113.50", "garbage-" + i);
        }
        assertThatThrownBy(() -> limiter.checkBooking(tenant, "203.0.113.50", "garbage"))
                .isInstanceOfSatisfying(RateLimitedException.class, ex -> assertThat(ex.getMessage()).contains("network"));
        // None of it touched the tenant counter: a full 20 valid attempts from other IPs still pass.
        for (int i = 0; i < 20; i++) {
            limiter.checkBooking(tenant, "10.2.0." + i, "+9955552000" + String.format("%02d", i));
        }
        assertThatCode(() -> limiter.checkBooking(tenant, "203.0.113.60", null)).doesNotThrowAnyException();
    }

    @Test
    void auditFailureNeverTurnsTheRefusalIntoAnotherError() {
        doThrow(new IllegalStateException("db down")).when(audit)
                .record(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any());
        for (int i = 0; i < 5; i++) {
            limiter.checkSlots("203.0.113.9");
        }
        assertThatThrownBy(() -> limiter.checkSlots("203.0.113.9")).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void withoutTenantScopeTheRefusalIsNotAudited() {
        TenantContext.clear();
        for (int i = 0; i < 5; i++) {
            limiter.checkSlots("203.0.113.9");
        }
        assertThatThrownBy(() -> limiter.checkSlots("203.0.113.9")).isInstanceOf(RateLimitedException.class);
        verify(audit, never()).record(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void maskedPhoneKeepsOnlyTheCountryPartAndLastTwoDigits() {
        assertThat(PublicRateLimiter.maskPhone("+995555123456")).isEqualTo("+995*******56");
        assertThat(PublicRateLimiter.maskPhone("+1234")).isEqualTo("***");
    }

    @Test
    void propertiesValidateLimits() {
        assertThatThrownBy(() -> new RateLimitProperties(0, 3, 20, 20, 10, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("slots-per-minute-per-ip");
        assertThat(new RateLimitProperties(5, 3, 20, 20, 10, null).trustedProxies()).isEmpty();
    }
}

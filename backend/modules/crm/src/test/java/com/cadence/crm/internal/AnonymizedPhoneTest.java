package com.cadence.crm.internal;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnonymizedPhoneTest {

    @Test
    void fitsTheNormalizedPhoneColumnAndCannotBeAnE164Number() {
        Random random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            UUID id = new UUID(random.nextLong(), random.nextLong());
            String value = AnonymizedPhone.forClient(id);
            assertThat(value.length()).isLessThanOrEqualTo(AnonymizedPhone.MAX_LENGTH);
            assertThat(value).startsWith("anon-").doesNotStartWith("+");
        }
        assertThat(AnonymizedPhone.forClient(new UUID(-1L, -1L)).length()).isLessThanOrEqualTo(AnonymizedPhone.MAX_LENGTH);
        assertThat(AnonymizedPhone.forClient(new UUID(0L, 0L)).length()).isLessThanOrEqualTo(AnonymizedPhone.MAX_LENGTH);
    }

    @Test
    void distinctIdsGiveDistinctValuesIncludingIdsThatDifferOnlyInOneBit() {
        Set<String> seen = new HashSet<>();
        Random random = new Random(7);
        for (int i = 0; i < 5000; i++) {
            UUID id = new UUID(random.nextLong(), random.nextLong());
            assertThat(seen.add(AnonymizedPhone.forClient(id))).isTrue();
        }
        UUID base = new UUID(0L, 0L);
        for (int bit = 0; bit < 64; bit++) {
            assertThat(AnonymizedPhone.forClient(new UUID(1L << bit, 0L))).isNotEqualTo(AnonymizedPhone.forClient(base));
            assertThat(AnonymizedPhone.forClient(new UUID(0L, 1L << bit))).isNotEqualTo(AnonymizedPhone.forClient(base));
        }
    }

    @Test
    void sameIdAlwaysGivesTheSameValue() {
        UUID id = UUID.randomUUID();
        assertThat(AnonymizedPhone.forClient(id)).isEqualTo(AnonymizedPhone.forClient(id));
    }
}

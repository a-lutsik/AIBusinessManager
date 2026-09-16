package com.cadence.platform.phone;

import com.cadence.platform.error.DomainException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhoneNormalizerTest {

    private final PhoneNormalizer normalizer = new PhoneNormalizer();

    @Test
    void formatsGeorgianLocalNumberToE164() {
        assertEquals("+995555123456", normalizer.toE164("555123456", "GE"));
    }

    @Test
    void rejectsInvalid() {
        assertThrows(DomainException.class, () -> normalizer.toE164("12", "GE"));
    }
}

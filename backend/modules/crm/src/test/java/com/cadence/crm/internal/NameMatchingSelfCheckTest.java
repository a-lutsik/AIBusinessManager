package com.cadence.crm.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameMatchingSelfCheckTest {

    @Test
    void aDatabaseThatDoesNotLowerCyrillicFailsTheStartWithAClearMessage() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> NameMatchingSelfCheck.assertAgrees("Ё", "Е"));

        assertTrue(e.getMessage().contains("UTF-8 locale"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> NameMatchingSelfCheck.assertLetter(false));
    }

    @Test
    void anAgreeingDatabasePasses() {
        assertDoesNotThrow(() -> NameMatchingSelfCheck.assertAgrees("Ё", "е"));
        assertDoesNotThrow(() -> NameMatchingSelfCheck.assertLetter(true));
    }
}

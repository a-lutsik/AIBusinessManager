package com.cadence.ai.internal.privacy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FreeTextMaskerLabelTest {

    private final FreeTextMasker masker = new FreeTextMasker();

    @Test
    void aLabelKeepsWordsButNotContactsTokensOrLines() {
        assertEquals("Medical massage [phone] [ref]", masker.maskLabel(" Medical massage\n+995 555 12 34 56\r\n client_3 "));
    }

    @Test
    void aLabelIsBounded() {
        assertEquals(120, masker.maskLabel("x".repeat(500)).length());
        assertNull(masker.maskLabel(null));
    }

    @Test
    void aKnownNameIsFoundInflected() {
        NameIndex names = NameIndex.of(Map.of("MASTER_1", "Нино Беридзе", "CLIENT_1", "Анна Петрова"));

        assertEquals("Стрижка у MASTER_1, звонила CLIENT_1, потом CLIENT_1",
                names.apply("Стрижка у Нины, звонила Анне Петровой, потом Петрову"));
    }
}

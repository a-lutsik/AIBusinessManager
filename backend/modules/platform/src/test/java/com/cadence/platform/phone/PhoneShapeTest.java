package com.cadence.platform.phone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One rule for "this text shows a phone number", used by the MASTER response policy and the master's client search.
 * A run of digits counts with spaces, dashes, dots and parentheses between them ignored.
 */
class PhoneShapeTest {

    private static final String PHONE = "+995555123456";

    @Test
    void theStoredPhoneAsNameInAnyFormattingIsPhoneShaped() {
        assertThat(PhoneShape.showsPhone(PHONE, PHONE)).isTrue();
        assertThat(PhoneShape.showsPhone("+995 (555) 12-34.56", PHONE)).isTrue();
        assertThat(PhoneShape.showsPhone("Mariam 995 555 123 456", PHONE)).isTrue();
    }

    @Test
    void localFormatDigitsInsideANameThatEndTheClientsPhoneArePhoneShaped() {
        // 7 or 8 digits are short for a phone of their own, but a suffix of this client's phone gives it away.
        assertThat(PhoneShape.showsPhone("Anna 555 12 34 56", PHONE)).as("9 local digits").isTrue();
        assertThat(PhoneShape.showsPhone("Anna 512-34-56", PHONE)).as("7-digit suffix").isTrue();
        assertThat(PhoneShape.showsPhone("Anna 5 512.34.56", PHONE)).as("8-digit suffix").isTrue();
        assertThat(PhoneShape.showsPhone("Anna 123 45 67 call", PHONE)).as("7 digits, not this phone's suffix").isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Anna 599 12 34 56", "Room 123-456-789", "id 123456789"})
    void anyRunOfNineDigitsIsPhoneShapedWhateverThePhone(String name) {
        assertThat(PhoneShape.showsPhone(name, PHONE)).isTrue();
        assertThat(PhoneShape.showsPhone(name, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mariam K.", "Anna 2", "Studio 54", "Visit 12.10.2026", "Nino, room 12, floor 3"})
    void ordinaryNamesAreNot(String name) {
        assertThat(PhoneShape.showsPhone(name, PHONE)).isFalse();
    }

    @Test
    void digitRunsSeparatedByOtherCharactersAreNotJoined() {
        // "555-12" and "34-56" are two runs (a letter between them), neither a 7-digit suffix nor 9 digits long.
        assertThat(PhoneShape.showsPhone("555-12 a 34-56", PHONE)).isFalse();
    }

    @Test
    void nullTextIsNotAPhone() {
        assertThat(PhoneShape.showsPhone(null, PHONE)).isFalse();
    }
}

package com.cadence.platform.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiTextTest {

    @Test
    void nulCharactersAreStrippedBecausePostgresRejectsThemInVarchar() {
        assertThat(AiText.truncate("a\u0000b\u0000", 100)).isEqualTo("ab");
    }

    @Test
    void theLimitAppliesAfterStripping() {
        assertThat(AiText.truncate("\u0000".repeat(50) + "abc", 20)).isEqualTo("abc");
    }

    @Test
    void nullAndCleanTextsPassThrough() {
        assertThat(AiText.truncate(null, 10)).isNull();
        assertThat(AiText.truncate("clean", 10)).isEqualTo("clean");
    }
}

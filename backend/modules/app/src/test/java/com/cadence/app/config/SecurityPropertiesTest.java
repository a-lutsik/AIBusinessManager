package com.cadence.app.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityPropertiesTest {

    @Test
    void oauth2OnlyIsValid() {
        assertThatCode(() -> new SecurityProperties(true, false).validate()).doesNotThrowAnyException();
    }

    @Test
    void headerAuthOnlyIsValid() {
        assertThatCode(() -> new SecurityProperties(false, true).validate()).doesNotThrowAnyException();
    }

    @Test
    void noAuthenticationConfiguredFailsFast() {
        assertThatThrownBy(() -> new SecurityProperties(false, false).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No authentication configured");
    }

    @Test
    void bothModesAtOnceFailsFast() {
        assertThatThrownBy(() -> new SecurityProperties(true, true).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("header-auth-enabled");
    }
}

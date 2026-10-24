package com.cadence.app.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbPasswordGuardTest {

    @Test
    void defaultPasswordIsAllowedWhenTheFlagIsOn() {
        assertThatCode(() -> DbPasswordGuard.check(true, false, "cadence_app", "cadence_app")).doesNotThrowAnyException();
    }

    @Test
    void defaultPasswordFailsFastWhenTheFlagIsOffOnEitherSide() {
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, "cadence_app", "s3cret"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_DB_PASSWORD");
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, "s3cret", "cadence_app"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, "", "s3cret"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void customPasswordPassesWhenTheFlagIsOff() {
        assertThatCode(() -> DbPasswordGuard.check(false, false, "long-random-secret", "long-random-secret"))
                .doesNotThrowAnyException();
    }

    @Test
    void localProfilesAreExempt() {
        assertThatCode(() -> DbPasswordGuard.check(false, true, "cadence_app", "cadence_app")).doesNotThrowAnyException();
    }
}

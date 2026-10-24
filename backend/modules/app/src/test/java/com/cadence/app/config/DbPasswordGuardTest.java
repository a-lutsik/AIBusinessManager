package com.cadence.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbPasswordGuardTest {

    private static final String RUNTIME = "runtime-secret-1";
    private static final String OWNER = "owner-secret-0002";

    @Test
    void defaultsAreAllowedWhenTheFlagIsOn() {
        assertThatCode(() -> DbPasswordGuard.check(true, false, "cadence_app", "cadence_app", "cadence"))
                .doesNotThrowAnyException();
        assertThatCode(() -> DbPasswordGuard.check(true, false, null, null, null)).doesNotThrowAnyException();
    }

    @Test
    void localProfilesAreExempt() {
        assertThatCode(() -> DbPasswordGuard.check(false, true, "cadence_app", "cadence_app", "cadence"))
                .doesNotThrowAnyException();
    }

    @Test
    void distinctCustomPasswordsPassWhenTheFlagIsOff() {
        assertThatCode(() -> DbPasswordGuard.check(false, false, RUNTIME, RUNTIME, OWNER)).doesNotThrowAnyException();
    }

    @Test
    void runtimePasswordDefaultFailsOnEitherSide() {
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, "cadence_app", RUNTIME, OWNER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_DB_PASSWORD");
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, RUNTIME, "cadence_app", OWNER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_DB_PASSWORD");
    }

    @Test
    void runtimePasswordBlankOrMissingFails() {
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, "", RUNTIME, OWNER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_DB_PASSWORD");
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, RUNTIME, "   ", OWNER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_DB_PASSWORD");
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, null, null, OWNER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_DB_PASSWORD");
    }

    @Test
    void ownerPasswordDefaultFails() {
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, RUNTIME, RUNTIME, "cadence"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FLYWAY_DB_PASSWORD");
    }

    @Test
    void ownerPasswordBlankOrMissingFails() {
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, RUNTIME, RUNTIME, ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FLYWAY_DB_PASSWORD");
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, RUNTIME, RUNTIME, "  "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FLYWAY_DB_PASSWORD");
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, RUNTIME, RUNTIME, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FLYWAY_DB_PASSWORD");
    }

    @Test
    void ownerAndRuntimePasswordsMustDiffer() {
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, RUNTIME, RUNTIME, RUNTIME))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be different");
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, OWNER, RUNTIME, RUNTIME))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be different");
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, RUNTIME, OWNER, RUNTIME))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be different");
    }

    @Test
    void examplePlaceholdersAreRejectedCaseInsensitively() {
        for (String placeholder : new String[]{"CHANGE_ME_runtime_secret", "CHANGE_ME_owner_secret", "change_me_something_long", "Change_Me_xxxxxxxxxxxxxxx"}) {
            assertThatThrownBy(() -> DbPasswordGuard.check(false, false, placeholder, placeholder, OWNER))
                    .as("runtime " + placeholder)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_DB_PASSWORD").hasMessageContaining("placeholder");
            assertThatThrownBy(() -> DbPasswordGuard.check(false, false, RUNTIME, RUNTIME, placeholder))
                    .as("owner " + placeholder)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("FLYWAY_DB_PASSWORD").hasMessageContaining("placeholder");
        }
        // The placeholder only counts at the start of the value.
        assertThatCode(() -> DbPasswordGuard.check(false, false, "xx-CHANGE_ME-" + RUNTIME, RUNTIME, OWNER)).doesNotThrowAnyException();
    }

    @Test
    void passwordsShorterThanSixteenCharactersAreRejected() {
        String fifteen = "abcdefghijklmno";
        String sixteen = "abcdefghijklmnop";
        assertThat(fifteen).hasSize(15);
        assertThat(sixteen).hasSize(16);
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, fifteen, fifteen, OWNER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_DB_PASSWORD").hasMessageContaining("16");
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, RUNTIME, fifteen, OWNER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_DB_PASSWORD");
        assertThatThrownBy(() -> DbPasswordGuard.check(false, false, RUNTIME, RUNTIME, fifteen))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FLYWAY_DB_PASSWORD").hasMessageContaining("16");
        assertThatCode(() -> DbPasswordGuard.check(false, false, sixteen, sixteen, OWNER)).doesNotThrowAnyException();
    }

    @Test
    void weakPasswordsAreAcceptedWhereTheGuardIsInactive() {
        assertThatCode(() -> DbPasswordGuard.check(true, false, "CHANGE_ME_runtime_secret", "CHANGE_ME_runtime_secret", "short"))
                .doesNotThrowAnyException();
        assertThatCode(() -> DbPasswordGuard.check(false, true, "CHANGE_ME_runtime_secret", "CHANGE_ME_runtime_secret", "short"))
                .doesNotThrowAnyException();
    }

    @Test
    void devHeadersAndTestProfilesAreLocal() {
        assertThat(DbPasswordGuard.isLocalProfile(profiles("dev-headers"))).isTrue();
        assertThat(DbPasswordGuard.isLocalProfile(profiles("test"))).isTrue();
    }

    @Test
    void otherProfilesAreNotLocal() {
        assertThat(DbPasswordGuard.isLocalProfile(profiles())).isFalse();
        assertThat(DbPasswordGuard.isLocalProfile(profiles("prod"))).isFalse();
    }

    @Test
    void prodIsNeverLocalEvenWithADevProfile() {
        assertThat(DbPasswordGuard.isLocalProfile(profiles("prod", "dev-headers"))).isFalse();
        assertThat(DbPasswordGuard.isLocalProfile(profiles("prod", "test"))).isFalse();
    }

    private static MockEnvironment profiles(String... active) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(active);
        return env;
    }
}

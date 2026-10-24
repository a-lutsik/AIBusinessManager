package com.cadence.app.config;

import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Refuses to start with the well-known local-development database passwords unless explicitly allowed. The check runs
 * as a Flyway configuration customizer, i.e. before V7 can create the runtime role {@code cadence_app} with its password
 * and before any migration touches the database as the owner.
 *
 * <p>{@code app.persistence.allow-default-db-password} is {@code true} by default (local development); production must
 * set it to {@code false} (the {@code prod} profile does) and provide two distinct secrets:
 * <ul>
 *   <li>{@code APP_DB_PASSWORD} for the runtime role {@code cadence_app} (feeds the datasource and the V7 migration);</li>
 *   <li>{@code FLYWAY_DB_PASSWORD} for the schema owner {@code cadence} used by Flyway only.</li>
 * </ul>
 * With the guard active a blank or default value of either, an example placeholder ({@code CHANGE_ME...}), a value
 * shorter than 16 characters, or the same value for both, stops the startup. The
 * {@code dev-headers} and {@code test} profiles are local by definition and are exempt, except when {@code prod} is
 * active as well: a production process is never treated as local.
 */
@Configuration
public class DbPasswordGuard {

    /** The default committed in application.yml and in V7's placeholder for the runtime role. */
    static final String DEV_DEFAULT_PASSWORD = "cadence_app";

    /** The default committed in application.yml and docker-compose.yml for the Flyway owner. */
    static final String DEV_DEFAULT_OWNER_PASSWORD = "cadence";

    /** Prefix of the unfilled placeholders in .env.prod.example ({@code CHANGE_ME_owner_secret}, ...). */
    static final String PLACEHOLDER_PREFIX = "CHANGE_ME";

    /** Shortest password accepted while the guard is active. */
    static final int MIN_PASSWORD_LENGTH = 16;

    @Bean
    FlywayConfigurationCustomizer dbPasswordGuardBeforeMigration(Environment env) {
        return configuration -> check(
                env.getProperty("app.persistence.allow-default-db-password", Boolean.class, true),
                isLocalProfile(env),
                env.getProperty("spring.datasource.password"),
                env.getProperty("spring.flyway.placeholders.appDbPassword"),
                env.getProperty("spring.flyway.password")
        );
    }

    static boolean isLocalProfile(Environment env) {
        return env.acceptsProfiles(Profiles.of("dev-headers", "test")) && !env.acceptsProfiles(Profiles.of("prod"));
    }

    static void check(
            boolean allowDefault,
            boolean localProfile,
            String datasourcePassword,
            String flywayPlaceholderPassword,
            String flywayOwnerPassword
    ) {
        if (allowDefault || localProfile) {
            return;
        }
        if (isBlankOrEqual(datasourcePassword, DEV_DEFAULT_PASSWORD)
                || isBlankOrEqual(flywayPlaceholderPassword, DEV_DEFAULT_PASSWORD)) {
            throw new IllegalStateException(
                    "The runtime database role cadence_app still uses the default development password. "
                            + "Set APP_DB_PASSWORD to a secret value (it feeds both the datasource and the V7 migration) "
                            + "or, for local development only, app.persistence.allow-default-db-password=true.");
        }
        if (isBlankOrEqual(flywayOwnerPassword, DEV_DEFAULT_OWNER_PASSWORD)) {
            throw new IllegalStateException(
                    "The database owner role used by Flyway still has a blank or default development password. "
                            + "Set FLYWAY_DB_PASSWORD to a secret value "
                            + "or, for local development only, app.persistence.allow-default-db-password=true.");
        }
        requireStrong("APP_DB_PASSWORD", datasourcePassword);
        requireStrong("APP_DB_PASSWORD", flywayPlaceholderPassword);
        requireStrong("FLYWAY_DB_PASSWORD", flywayOwnerPassword);
        if (flywayOwnerPassword.equals(datasourcePassword) || flywayOwnerPassword.equals(flywayPlaceholderPassword)) {
            throw new IllegalStateException(
                    "FLYWAY_DB_PASSWORD (schema owner) and APP_DB_PASSWORD (runtime role) must be different secrets: "
                            + "the runtime role is the one subject to row-level security and must not share a "
                            + "credential with the owner that bypasses it.");
        }
    }

    /** Rejects the copied-over example placeholders and passwords too short to be a generated secret. */
    private static void requireStrong(String variable, String password) {
        if (password.regionMatches(true, 0, PLACEHOLDER_PREFIX, 0, PLACEHOLDER_PREFIX.length())) {
            throw new IllegalStateException(
                    variable + " still holds an example placeholder (" + PLACEHOLDER_PREFIX + "...): "
                            + "generate a real secret, e.g. `openssl rand -base64 24`.");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    variable + " is shorter than " + MIN_PASSWORD_LENGTH + " characters: "
                            + "use a generated secret, e.g. `openssl rand -base64 24`.");
        }
    }

    private static boolean isBlankOrEqual(String password, String defaultPassword) {
        return password == null || password.isBlank() || defaultPassword.equals(password);
    }
}

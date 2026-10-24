package com.cadence.app.config;

import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Refuses to start with the well-known local-development password of the runtime DB role {@code cadence_app} unless
 * explicitly allowed. The check runs as a Flyway configuration customizer, i.e. before V7 can create the role with that
 * password.
 *
 * <p>{@code app.persistence.allow-default-db-password} is {@code true} by default (local development); production must
 * set it to {@code false} and provide {@code APP_DB_PASSWORD}. The {@code dev-headers} and {@code test} profiles are
 * local by definition and are exempt.
 */
@Configuration
public class DbPasswordGuard {

    /** The default committed in application.yml and in V7's placeholder. */
    static final String DEV_DEFAULT_PASSWORD = "cadence_app";

    @Bean
    FlywayConfigurationCustomizer dbPasswordGuardBeforeMigration(Environment env) {
        return configuration -> check(
                env.getProperty("app.persistence.allow-default-db-password", Boolean.class, true),
                env.acceptsProfiles(Profiles.of("dev-headers", "test")),
                env.getProperty("spring.datasource.password"),
                env.getProperty("spring.flyway.placeholders.appDbPassword")
        );
    }

    static void check(boolean allowDefault, boolean localProfile, String datasourcePassword, String flywayPlaceholderPassword) {
        if (allowDefault || localProfile) {
            return;
        }
        if (isDefault(datasourcePassword) || isDefault(flywayPlaceholderPassword)) {
            throw new IllegalStateException(
                    "The runtime database role cadence_app still uses the default development password. "
                            + "Set APP_DB_PASSWORD to a secret value (it feeds both the datasource and the V7 migration) "
                            + "or, for local development only, app.persistence.allow-default-db-password=true.");
        }
    }

    private static boolean isDefault(String password) {
        return password == null || password.isBlank() || DEV_DEFAULT_PASSWORD.equals(password);
    }
}

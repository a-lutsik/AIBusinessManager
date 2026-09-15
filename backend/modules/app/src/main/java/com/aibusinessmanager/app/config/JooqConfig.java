package com.aibusinessmanager.app.config;

import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.springframework.boot.jooq.autoconfigure.DefaultConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JooqConfig {

    @Bean
    DefaultConfigurationCustomizer jooqUnquotedPublicSchema() {
        return configuration -> {
            Settings settings = configuration.settings();
            settings.withRenderSchema(false);
            settings.withRenderQuotedNames(RenderQuotedNames.EXPLICIT_DEFAULT_UNQUOTED);
        };
    }
}

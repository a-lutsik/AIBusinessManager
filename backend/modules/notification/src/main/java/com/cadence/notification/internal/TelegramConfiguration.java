package com.cadence.notification.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** The bot token travels in the request URL, so under the {@code prod} profile the Bot API can only be the official one. */
@Configuration
@EnableConfigurationProperties(TelegramProperties.class)
class TelegramConfiguration {

    static final String OFFICIAL_API = "https://api.telegram.org";

    TelegramConfiguration(TelegramProperties props, Environment env) {
        if (props.enabled() && env.acceptsProfiles(Profiles.of("prod")) && !OFFICIAL_API.equals(props.apiBaseUrl())) {
            throw new IllegalStateException("app.telegram.api-base-url must be " + OFFICIAL_API + " under the prod profile");
        }
    }
}

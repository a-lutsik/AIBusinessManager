package com.cadence.platform.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@code messages_*.properties} bundle explicitly: Boot's own message-source auto-configuration only
 * activates for a base {@code messages.properties}, which the per-locale catalogs deliberately do not have.
 */
@Configuration(proxyBeanMethods = false)
public class MessageSourceConfiguration {

    @Bean(name = "messageSource")
    public MessageSource messageSource() {
        return MessageCatalog.newMessageSource();
    }
}

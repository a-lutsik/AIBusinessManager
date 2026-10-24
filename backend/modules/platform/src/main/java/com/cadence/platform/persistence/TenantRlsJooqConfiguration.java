package com.cadence.platform.persistence;

import org.jooq.ExecuteListenerProvider;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link TenantRlsExecuteListener}. Spring Boot's {@code JooqAutoConfiguration} collects every
 * {@link ExecuteListenerProvider} bean ({@code ObjectProvider<ExecuteListenerProvider>.orderedStream()}) into the
 * jOOQ {@code DefaultConfiguration}, next to its own exception-translator listener.
 */
@Configuration(proxyBeanMethods = false)
public class TenantRlsJooqConfiguration {

    @Bean
    ExecuteListenerProvider tenantRlsExecuteListenerProvider(
            @Value("${app.persistence.require-transaction:false}") boolean requireTransaction
    ) {
        return new DefaultExecuteListenerProvider(new TenantRlsExecuteListener(requireTransaction));
    }
}

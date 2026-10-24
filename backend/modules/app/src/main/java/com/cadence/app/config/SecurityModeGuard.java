package com.cadence.app.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Fails the startup on an invalid authentication mode: none or both modes on, or header authentication together with
 * the {@code prod} profile (see {@link SecurityProperties#validate(Environment)}). Created eagerly with the context, so
 * the check does not depend on bean order.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityModeGuard {

    public SecurityModeGuard(SecurityProperties props, Environment env) {
        props.validate(env);
    }
}

package com.cadence.app.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitWebConfig implements WebMvcConfigurer {

    private final PublicSlotsRateLimitInterceptor slotsInterceptor;

    public RateLimitWebConfig(PublicSlotsRateLimitInterceptor slotsInterceptor) {
        this.slotsInterceptor = slotsInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(slotsInterceptor).addPathPatterns("/api/public/tenants/*/slots");
    }
}

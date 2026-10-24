package com.cadence.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableConfigurationProperties({SecurityProperties.class, CorsProperties.class})
@Import({SecurityModeGuard.class, JwtEndpointGuard.class})
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * One CORS policy for every chain. Allowed origins come from {@code app.cors.allowed-origins}; with none the API
     * answers no cross-origin request. The X-* identity headers are only honoured in the dev-headers profile.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
        if (props.allowedOrigins().isEmpty()) {
            log.warn("app.cors.allowed-origins is empty: every cross-origin browser request to the API will be refused. "
                    + "Set APP_CORS_ALLOWED_ORIGINS to the console origin(s) if a browser client on another origin must call it");
        }
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(props.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept-Language",
                "X-Tenant-Id", "X-Actor-Role", "X-Actor-Id", "X-Specialist-Id"));
        cors.setExposedHeaders(List.of("Retry-After"));
        cors.setAllowCredentials(props.allowCredentials());
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    @Bean
    @Order(1)
    SecurityFilterChain publicApiChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/public/**", "/actuator/health", "/actuator/info", "/v3/api-docs", "/openapi.yaml")
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain appApiChain(HttpSecurity http, SecurityProperties props) throws Exception {
        http.securityMatcher("/api/app/**")
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    if (props.oauth2Enabled()) {
                        auth.requestMatchers(HttpMethod.OPTIONS).permitAll().anyRequest().authenticated();
                    } else {
                        auth.anyRequest().permitAll();
                    }
                });
        if (props.oauth2Enabled()) {
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        }
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain defaultChain(HttpSecurity http) throws Exception {
        http.cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}

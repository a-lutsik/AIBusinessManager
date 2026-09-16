package com.cadence.app.integration;

import com.cadence.notification.api.NotificationChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.messaging.MessageChannel;

import java.util.List;

/**
 * Phase 4 channel seams. Outbox poller stays {@code @Scheduled}; adapters subscribe here later.
 */
@Configuration
@EnableIntegration
public class NotificationIntegrationConfig {

    @Bean
    MessageChannel notificationOutbound() {
        return new DirectChannel();
    }

    @Bean
    List<String> notificationChannelIds(List<NotificationChannel> channels) {
        return channels.stream().map(NotificationChannel::channelId).toList();
    }
}

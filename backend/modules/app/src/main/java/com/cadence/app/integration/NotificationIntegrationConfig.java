package com.cadence.app.integration;

import com.cadence.notification.api.NotificationChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.config.EnableIntegration;

import java.util.List;

/**
 * Ids of the notification channels in use (a start-up overview). The outbox poller, the delivery worker and the reminder job are plain {@code @Scheduled} beans.
 */
@Configuration
@EnableIntegration
public class NotificationIntegrationConfig {

    @Bean
    List<String> notificationChannelIds(List<NotificationChannel> channels) {
        return channels.stream().map(NotificationChannel::channelId).toList();
    }
}

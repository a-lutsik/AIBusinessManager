package com.aibusinessmanager.notification.api;

/**
 * Strategy port for outbound channels. Telegram adapter arrives in phase 4.
 */
public interface NotificationChannel {

    String channelId();

    void send(String tenantScopedPayloadJson);
}

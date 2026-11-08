package com.cadence.notification.api;

/**
 * Strategy port for outbound channels. A channel never throws for a delivery problem: it records the attempt in the
 * delivery journal ({@code notification_delivery}) and reports the outcome, so the caller can fall back to another one.
 * Runs in the tenant scope of the caller.
 */
public interface NotificationChannel {

    String channelId();

    DeliveryOutcome send(OutboundMessage message);
}

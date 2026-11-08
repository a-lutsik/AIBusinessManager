package com.cadence.notification.api;

/**
 * Strategy port for outbound channels: the transport only. A channel never throws for a delivery problem, it reports the
 * outcome; the delivery worker journals it and falls back to another channel. A transport that talks to a provider must
 * not be called inside a database transaction.
 */
public interface NotificationChannel {

    String channelId();

    DeliveryOutcome send(OutboundMessage message);
}

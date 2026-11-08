package com.cadence.notification.api;

import java.util.UUID;

/**
 * One ready-made text for one client. {@code destination} is the channel address (the Telegram chat id), null when the
 * client has none; {@code appointmentId} is null for a message that is not about a visit.
 */
public record OutboundMessage(UUID clientId, UUID appointmentId, String destination, String templateKey, String text) {
}

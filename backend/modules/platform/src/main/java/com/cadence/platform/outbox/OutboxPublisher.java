package com.cadence.platform.outbox;

import java.util.UUID;

/**
 * Platform outbox port. Implementations live in {@code internal} adapters; domain modules
 * must not write to {@code outbox_event} directly.
 * <p>
 * Poller (later in 1A): {@code @Scheduled} + {@code SELECT FOR UPDATE SKIP LOCKED} via jOOQ.
 * Do not add Spring Integration solely for this poller.
 */
public interface OutboxPublisher {

    void publish(String type, String aggregateType, UUID aggregateId, String jsonPayload);
}

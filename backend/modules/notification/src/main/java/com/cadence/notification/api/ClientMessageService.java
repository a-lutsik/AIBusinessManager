package com.cadence.notification.api;

/**
 * Queues a visit message for the client. Delivery happens later, outside the caller's transaction, on the delivery worker:
 * Telegram when the client has linked it, otherwise (no chat, channel off, delivery failed, the client blocked the bot) a
 * manual owner task with the ready text when {@link VisitMessage#manualFallback()} is set. The text is written in the
 * client's language and the tenant's time zone. Each message is queued once per visit, kind and visit start.
 */
public interface ClientMessageService {

    /**
     * Returns false when nothing was queued: the message is already queued or sent for this visit start, or it is a
     * message without manual fallback for a client who has no Telegram chat (nothing could be delivered). Tenant scope required.
     */
    boolean enqueue(VisitMessage message);
}

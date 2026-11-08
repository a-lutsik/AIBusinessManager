package com.cadence.notification.api;

/**
 * Sends a visit message to the client in the client's language and the tenant's time zone: Telegram when the client has
 * linked it, otherwise (no chat, channel off, delivery failed or the client blocked the bot) a manual owner task with the
 * ready text, so the message is never lost. Each message goes out once per visit, kind and visit start.
 */
public interface ClientMessageService {

    /** Returns false when this message was already sent for this visit start (nothing was done). Tenant scope required. */
    boolean send(VisitMessage message);
}

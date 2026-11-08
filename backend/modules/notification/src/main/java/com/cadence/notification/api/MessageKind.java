package com.cadence.notification.api;

/** What a visit message is about; part of the idempotency key of {@link ClientMessageService}. */
public enum MessageKind {
    CONFIRMATION("client.telegram.confirmed"),
    CONFIRMATION_PENDING("client.telegram.pending"),
    REMINDER_DAY("client.telegram.reminder.day"),
    REMINDER_HOURS("client.telegram.reminder.hours");

    private final String messageKey;

    MessageKind(String messageKey) {
        this.messageKey = messageKey;
    }

    /** Key of the text in the message catalog. */
    public String messageKey() {
        return messageKey;
    }
}

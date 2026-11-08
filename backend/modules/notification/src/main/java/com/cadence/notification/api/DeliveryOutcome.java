package com.cadence.notification.api;

/** Result of one send attempt; {@code error} is a short reason for everything but {@link Status#SENT}. */
public record DeliveryOutcome(Status status, String error) {

    public enum Status {
        SENT,
        /** The provider refused or could not be reached. */
        FAILED,
        /** The provider says the client blocked the bot or deleted the chat (HTTP 403): the link is dead. */
        REJECTED_BY_CLIENT,
        SKIPPED_NO_CHAT,
        SKIPPED_DISABLED,
        /** Interrupted where nothing can have reached the provider (before the call, or while waiting after a refusal): the message goes back in the queue. */
        INTERRUPTED,
        /** Interrupted inside a call, or while waiting after a call without an answer: it may have been delivered, so it is never sent again. */
        UNKNOWN,
        /** Written to the owner as a task with the ready text (the manual channel). */
        QUEUED_MANUAL
    }

    public static DeliveryOutcome sent() {
        return new DeliveryOutcome(Status.SENT, null);
    }

    /** Whether the client has the message (or the owner has the task to send it). */
    public boolean delivered() {
        return status == Status.SENT || status == Status.QUEUED_MANUAL;
    }
}

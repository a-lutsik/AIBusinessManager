package com.cadence.notification.api;

/** Entry point of the Telegram Bot API webhook. */
public interface TelegramWebhook {

    /**
     * Handles one update. Returns false only when the secret header does not match the configured secret (the caller
     * answers 401); everything else, unknown or malformed updates included, is accepted so Telegram does not retry it.
     */
    boolean handle(String secretTokenHeader, String updateJson);
}

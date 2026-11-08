package com.cadence.notification.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Telegram channel settings, {@code app.telegram.*}. One bot serves the whole installation (the tenant is found by the
 * one-time link token). The channel is off by default; when on, the bot token, the bot username and the webhook secret
 * are mandatory and the application refuses to start without them. The token comes from {@code APP_TELEGRAM_BOT_TOKEN}
 * only and never appears in {@link #toString()}, logs or responses.
 *
 * @param webhookSecret     value Telegram sends in {@code X-Telegram-Bot-Api-Secret-Token} (1-256 of A-Z a-z 0-9 _ -)
 * @param apiBaseUrl        Bot API origin; only tests point it elsewhere (a stub HTTP server)
 * @param maxAttempts       attempts per message (first try included) on network errors, 5xx and 429
 * @param linkTtl           how long a deep link stays valid
 * @param reminderPollMs    pause between runs of the reminder job
 */
@ConfigurationProperties(prefix = "app.telegram")
public record TelegramProperties(
        @DefaultValue("false") boolean enabled,
        String botToken,
        String botUsername,
        String webhookSecret,
        @DefaultValue("https://api.telegram.org") String apiBaseUrl,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("72h") Duration linkTtl,
        @DefaultValue("60000") long reminderPollMs
) {

    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_-]{1,256}");
    private static final Pattern TOKEN = Pattern.compile("[0-9]{3,}:[A-Za-z0-9_-]{20,}");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{5,32}");

    public TelegramProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("app.telegram.max-attempts must be at least 1, got " + maxAttempts);
        }
        if (enabled) {
            require(botToken, "app.telegram.bot-token (APP_TELEGRAM_BOT_TOKEN)");
            require(botUsername, "app.telegram.bot-username (APP_TELEGRAM_BOT_USERNAME)");
            require(webhookSecret, "app.telegram.webhook-secret (APP_TELEGRAM_WEBHOOK_SECRET)");
            if (!TOKEN.matcher(botToken).matches()) {
                throw new IllegalArgumentException("app.telegram.bot-token does not look like a BotFather token (<digits>:<secret>)");
            }
            if (!USERNAME.matcher(botUsername).matches()) {
                throw new IllegalArgumentException("app.telegram.bot-username must be the bot's username without @");
            }
            if (!SECRET.matcher(webhookSecret).matches()) {
                throw new IllegalArgumentException("app.telegram.webhook-secret must be 1-256 characters of A-Z a-z 0-9 _ -");
            }
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("The Telegram channel is enabled but " + name + " is not set");
        }
    }

    @Override
    public String toString() {
        return "TelegramProperties[enabled=" + enabled + ", botUsername=" + botUsername + ", apiBaseUrl=" + apiBaseUrl + "]";
    }
}

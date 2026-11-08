package com.cadence.notification.internal;

import com.cadence.notification.api.DeliveryOutcome;
import com.cadence.notification.api.DeliveryOutcome.Status;
import com.cadence.notification.api.NotificationChannel;
import com.cadence.notification.api.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Telegram transport, no database access (it is called outside any transaction). Outcomes: SENT; FAILED (provider error,
 * with the reason); REJECTED_BY_CLIENT (HTTP 403: the client blocked the bot, the caller drops the link);
 * SKIPPED_NO_CHAT (the client has not linked Telegram); SKIPPED_DISABLED (channel off); INTERRUPTED (shutdown, nothing sent).
 */
@Component
public class TelegramNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationChannel.class);

    private final TelegramProperties props;
    private final TelegramBotClient bot;

    TelegramNotificationChannel(TelegramProperties props, TelegramBotClient bot) {
        this.props = props;
        this.bot = bot;
    }

    @Override
    public String channelId() {
        return "telegram";
    }

    @Override
    public DeliveryOutcome send(OutboundMessage message) {
        if (!props.enabled()) {
            return new DeliveryOutcome(Status.SKIPPED_DISABLED, "Telegram channel is disabled");
        }
        if (message.destination() == null) {
            return new DeliveryOutcome(Status.SKIPPED_NO_CHAT, "Client has not linked Telegram");
        }
        TelegramBotClient.Result result = bot.sendMessage(message.destination(), message.text(), null);
        if (result.ok()) {
            return DeliveryOutcome.sent();
        }
        Status status = result.interrupted() ? Status.INTERRUPTED : result.forbidden() ? Status.REJECTED_BY_CLIENT : Status.FAILED;
        log.warn("Telegram message not delivered: client={}, visit={}, template={}, outcome={}, reason={}",
                message.clientId(), message.appointmentId(), message.templateKey(), status, result.reason());
        return new DeliveryOutcome(status, result.reason());
    }
}

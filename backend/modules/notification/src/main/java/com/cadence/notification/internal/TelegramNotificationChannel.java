package com.cadence.notification.internal;

import com.cadence.notification.api.DeliveryOutcome;
import com.cadence.notification.api.DeliveryOutcome.Status;
import com.cadence.notification.api.NotificationChannel;
import com.cadence.notification.api.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Telegram adapter. Every attempt lands in the delivery journal: SENT, FAILED (with the reason), SKIPPED_NO_CHAT (the
 * client has not linked Telegram) or SKIPPED_DISABLED (channel off). HTTP 403 from the Bot API means the client blocked
 * the bot: the attempt is journaled as FAILED and reported as {@link Status#REJECTED_BY_CLIENT} so the caller drops the link.
 */
@Component
public class TelegramNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationChannel.class);

    private final TelegramProperties props;
    private final TelegramBotClient bot;
    private final DeliveryJournal journal;

    TelegramNotificationChannel(TelegramProperties props, TelegramBotClient bot, DeliveryJournal journal) {
        this.props = props;
        this.bot = bot;
        this.journal = journal;
    }

    @Override
    public String channelId() {
        return "telegram";
    }

    @Override
    @Transactional
    public DeliveryOutcome send(OutboundMessage message) {
        if (!props.enabled()) {
            return skipped(message, Status.SKIPPED_DISABLED, "Telegram channel is disabled");
        }
        if (message.destination() == null) {
            return skipped(message, Status.SKIPPED_NO_CHAT, "Client has not linked Telegram");
        }
        TelegramBotClient.Result result = bot.sendMessage(message.destination(), message.text());
        if (result.ok()) {
            journal.record(channelId(), message, "SENT", null);
            log.info("Telegram message sent: client={}, visit={}, template={}", message.clientId(), message.appointmentId(), message.templateKey());
            return DeliveryOutcome.sent();
        }
        journal.record(channelId(), message, "FAILED", result.reason());
        Status status = result.forbidden() ? Status.REJECTED_BY_CLIENT : Status.FAILED;
        log.warn("Telegram message not delivered: client={}, visit={}, template={}, outcome={}, reason={}",
                message.clientId(), message.appointmentId(), message.templateKey(), status, result.reason());
        return new DeliveryOutcome(status, result.reason());
    }

    private DeliveryOutcome skipped(OutboundMessage message, Status status, String reason) {
        journal.record(channelId(), message, status.name(), reason);
        log.debug("Telegram message skipped: client={}, visit={}, template={}, outcome={}",
                message.clientId(), message.appointmentId(), message.templateKey(), status);
        return new DeliveryOutcome(status, reason);
    }
}

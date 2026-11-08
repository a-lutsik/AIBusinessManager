package com.cadence.notification.internal;

import com.cadence.notification.api.DeliveryOutcome;
import com.cadence.notification.api.DeliveryOutcome.Status;
import com.cadence.notification.api.NotificationChannel;
import com.cadence.notification.api.OutboundMessage;
import com.cadence.notification.api.OwnerTaskService;
import com.cadence.platform.i18n.MessageCatalog;
import org.springframework.stereotype.Component;

/** Manual-fallback channel: an owner task with the ready-made text, so the owner can send it by hand. Runs in the caller's transaction. */
@Component
public class ManualTaskNotificationChannel implements NotificationChannel {

    private final OwnerTaskService ownerTaskService;
    private final MessageCatalog messages;

    ManualTaskNotificationChannel(OwnerTaskService ownerTaskService, MessageCatalog messages) {
        this.ownerTaskService = ownerTaskService;
        this.messages = messages;
    }

    @Override
    public String channelId() {
        return "manual";
    }

    @Override
    public DeliveryOutcome send(OutboundMessage message) {
        ownerTaskService.create(
                "MANUAL_MESSAGE",
                messages.forOwner("owner.task.manualMessage.title"),
                message.text(),
                message.text(),
                null,
                message.appointmentId(),
                message.clientId()
        );
        return new DeliveryOutcome(Status.QUEUED_MANUAL, null);
    }
}

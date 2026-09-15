package com.aibusinessmanager.app.outbox;

import com.aibusinessmanager.notification.api.NotificationChannel;
import com.aibusinessmanager.platform.outbox.OutboxHandler;
import org.jooq.Record;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.aibusinessmanager.platform.jooq.Tables.OUTBOX_EVENT;

@Component
public class BookingCreatedNotifyHandler implements OutboxHandler {

    private final List<NotificationChannel> channels;

    public BookingCreatedNotifyHandler(List<NotificationChannel> channels) {
        this.channels = channels;
    }

    @Override
    public String type() {
        return "BOOKING_CREATED";
    }

    @Override
    public void handle(Record event) {
        String payload = event.get(OUTBOX_EVENT.PAYLOAD);
        for (NotificationChannel channel : channels) {
            if ("telegram".equals(channel.channelId())) {
                channel.send(payload);
            }
        }
    }
}

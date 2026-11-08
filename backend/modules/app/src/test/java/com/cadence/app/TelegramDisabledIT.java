package com.cadence.app;

import com.cadence.app.reminder.VisitReminderJob;
import com.cadence.app.seed.DemoIds;
import com.cadence.notification.api.ClientMessageService;
import com.cadence.notification.api.DeliveryRunner;
import com.cadence.notification.api.MessageKind;
import com.cadence.notification.api.VisitMessage;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The default configuration: the Telegram channel is off. The confirmation still reaches the owner as a task. */
@SpringBootTest
class TelegramDisabledIT extends AbstractTimescaleIT {

    @Autowired
    ClientMessageService clientMessages;
    @Autowired
    DeliveryRunner deliveries;
    @Autowired
    ApplicationContext context;

    private static String scalar(String sql, Object... args) throws SQLException {
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void aDisabledChannelIsJournaledAsSkippedAndFallsBackToAManualTask() throws Exception {
        // Messages that other ITs of this JVM left in the queue (the outbox poller of OutboxPollerIT queues the seeded visits)
        // must not crowd out this one, and the visit chosen must have none of its own.
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
             PreparedStatement ps = c.prepareStatement("update visit_message_dispatch set status = 'DONE' where status <> 'DONE'")) {
            ps.executeUpdate();
        }
        UUID appointmentId = UUID.fromString(scalar("select id from appointment where tenant_id = ? and status = 'CONFIRMED'"
                + " and not exists (select 1 from visit_message_dispatch d where d.appointment_id = appointment.id) order by created_at limit 1", DemoIds.LUMEN));
        UUID clientId = UUID.fromString(scalar("select client_id from appointment where id = ?", appointmentId));
        Instant start = Instant.parse(scalar("select to_char(service_start, 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') from appointment where id = ?", appointmentId));
        try (TenantContext.Scope ignored = TenantContext.open(DemoIds.LUMEN)) {
            ActorContext.set(ActorContext.system());
            assertThat(clientMessages.enqueue(new VisitMessage(appointmentId, clientId, MessageKind.CONFIRMATION, "Haircut", start, true))).isTrue();
        } finally {
            ActorContext.clear();
        }
        deliveries.runOnce();

        assertThat(scalar("select status from notification_delivery where appointment_id = ? and channel_id = 'telegram'", appointmentId))
                .isEqualTo("SKIPPED_DISABLED");
        assertThat(scalar("select count(*) from owner_task where appointment_id = ? and kind = 'MANUAL_MESSAGE'", appointmentId)).isEqualTo("1");
        assertThat(scalar("select status from visit_message_dispatch where appointment_id = ? and kind = 'CONFIRMATION'", appointmentId)).isEqualTo("DONE");
    }

    @Test
    void theReminderJobIsNotRunningWhileTheChannelIsOff() {
        assertThat(context.getBeanNamesForType(VisitReminderJob.class)).isEmpty();
    }
}

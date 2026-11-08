package com.cadence.app;

import com.cadence.app.reminder.VisitReminderJob;
import com.cadence.app.seed.DemoIds;
import com.cadence.notification.api.ClientMessageService;
import com.cadence.notification.api.MessageKind;
import com.cadence.notification.api.VisitMessage;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.cadence.app.TestJwt.owner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Telegram channel end to end against a stub Bot API (an in-process HTTP server; no real Telegram call is ever made):
 * binding through the one-time deep link and the webhook, the delivery journal statuses, the 403 and 5xx paths with their
 * manual fallback, idempotency of a message and of the reminders.
 */
@SpringBootTest
@RelaxedPublicRateLimits
@AutoConfigureMockMvc
class TelegramChannelIT extends AbstractTimescaleIT {

    private static final String SECRET = "stub-webhook-secret";
    private static final Pattern START_TOKEN = Pattern.compile("start=([A-Za-z0-9_-]+)");

    /** What the stub Bot API received (chat id and text of every sendMessage), and how it answers. */
    private static final HttpServer BOT;
    private static final List<String[]> SENT = new CopyOnWriteArrayList<>();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static volatile int answer = 200;

    static {
        try {
            BOT = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
        BOT.createContext("/", exchange -> {
            CALLS.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] reply;
            if (answer == 200) {
                JsonNode json = new ObjectMapper().readTree(body);
                SENT.add(new String[] {json.path("chat_id").asText(), json.path("text").asText()});
                reply = "{\"ok\":true,\"result\":{}}".getBytes(StandardCharsets.UTF_8);
            } else {
                reply = ("{\"ok\":false,\"error_code\":" + answer + ",\"description\":\"stub says " + answer + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(answer, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        BOT.start();
    }

    @DynamicPropertySource
    static void telegram(DynamicPropertyRegistry registry) {
        registry.add("app.telegram.enabled", () -> "true");
        registry.add("app.telegram.bot-token", () -> "123456789:STUB_token_for_the_stub_server_only");
        registry.add("app.telegram.bot-username", () -> "cadence_stub_bot");
        registry.add("app.telegram.webhook-secret", () -> SECRET);
        registry.add("app.telegram.api-base-url", () -> "http://127.0.0.1:" + BOT.getAddress().getPort());
        registry.add("app.telegram.max-attempts", () -> "3");
        registry.add("app.telegram.reminder-poll-ms", () -> "3600000");
    }

    @AfterAll
    static void stopBot() {
        BOT.stop(0);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ClientMessageService clientMessages;
    @Autowired
    VisitReminderJob reminders;
    @Autowired
    TenantDirectory tenants;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void resetBot() {
        SENT.clear();
        CALLS.set(0);
        answer = 200;
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
    }

    private record Booking(String id, String token, UUID clientId, Instant start) {
    }

    private static String phone(int n) {
        return "+99555" + String.format("%07d", 8_100_000 + n);
    }

    private Booking book(String phone, String locale) throws Exception {
        String slot = TestSlots.firstFree(mvc, mapper, DemoIds.HAIRCUT, DemoIds.NINO, 2);
        MvcResult created = mvc.perform(post("/api/public/tenants/lumen-studio/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId": "%s", "specialistId": "%s", "serviceStart": "%s", "phone": "%s",
                                 "name": "Tg Guest", "locale": "%s", "marketingConsent": false}
                                """.formatted(DemoIds.HAIRCUT, DemoIds.NINO, slot, phone, locale)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = mapper.readTree(created.getResponse().getContentAsByteArray());
        String id = body.get("id").asText();
        return new Booking(id, body.get("accessToken").asText(), UUID.fromString(scalar("select client_id from appointment where id = ?::uuid", id)),
                Instant.parse(body.get("serviceStart").asText()));
    }

    private String scalar(String sql, Object... args) throws SQLException {
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void update(String sql, Object... args) throws SQLException {
        try (Connection c = ownerConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private String chatOf(UUID clientId) throws SQLException {
        return scalar("select telegram_chat_id from client where id = ?", clientId);
    }

    private String startToken(Booking booking) throws Exception {
        MvcResult link = mvc.perform(post("/api/public/bookings/" + booking.id() + "/telegram-link").param("token", booking.token()))
                .andExpect(status().isOk())
                .andReturn();
        String url = mapper.readTree(link.getResponse().getContentAsByteArray()).get("url").asText();
        assertThat(url).startsWith("https://t.me/cadence_stub_bot?start=");
        Matcher m = START_TOKEN.matcher(url);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private int webhook(String secret, String update) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/public/telegram/webhook").contentType(MediaType.APPLICATION_JSON).content(update);
        if (secret != null) {
            request.header("X-Telegram-Bot-Api-Secret-Token", secret);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static String startUpdate(long chatId, String token) {
        return """
                {"update_id": 1, "message": {"chat": {"id": %d, "type": "private"}, "from": {"language_code": "ru"}, "text": "/start %s"}}
                """.formatted(chatId, token);
    }

    private void bind(Booking booking, long chatId) throws Exception {
        assertThat(webhook(SECRET, startUpdate(chatId, startToken(booking)))).isEqualTo(200);
        assertThat(chatOf(booking.clientId())).isEqualTo(String.valueOf(chatId));
        SENT.clear();
        CALLS.set(0);
    }

    private boolean send(Booking booking, MessageKind kind, boolean manualFallback) {
        try (TenantContext.Scope ignored = TenantContext.open(DemoIds.LUMEN)) {
            ActorContext.set(ActorContext.system());
            return clientMessages.send(new VisitMessage(UUID.fromString(booking.id()), booking.clientId(), kind, "Haircut", booking.start(), manualFallback));
        } finally {
            ActorContext.clear();
        }
    }

    private String delivery(Booking booking, MessageKind kind) throws SQLException {
        return scalar("select status || '|' || coalesce(error, '') from notification_delivery"
                + " where appointment_id = ?::uuid and template_key = ? and channel_id = 'telegram' order by created_at desc limit 1",
                booking.id(), kind.name());
    }

    private long manualTasks(Booking booking) throws SQLException {
        return Long.parseLong(scalar("select count(*) from owner_task where appointment_id = ?::uuid and kind = 'MANUAL_MESSAGE'", booking.id()));
    }

    @Test
    void linkBindsTheChatOnceAndTheConfirmationIsSentAndNeverRepeated() throws Exception {
        Booking booking = book(phone(1), "ru");
        assertThat(chatOf(booking.clientId())).isNull();
        String token = startToken(booking);

        assertThat(webhook(SECRET, startUpdate(70_001, token))).isEqualTo(200);
        assertThat(chatOf(booking.clientId())).isEqualTo("70001");
        assertThat(SENT).hasSize(1);
        assertThat(SENT.get(0)[0]).isEqualTo("70001");
        assertThat(SENT.get(0)[1]).as("the 'linked' answer is in the client's locale").contains("Готово");
        // The token is spent: a second use binds nothing and is told so.
        SENT.clear();
        assertThat(webhook(SECRET, startUpdate(70_002, token))).isEqualTo(200);
        assertThat(chatOf(booking.clientId())).isEqualTo("70001");
        assertThat(SENT.get(0)[1]).contains("Ссылка устарела");
        assertThat(scalar("select used_at is not null from client_channel_link_token"
                + " where token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')", token)).isEqualTo("t");
        // The database holds only the hash of the token.
        assertThat(scalar("select count(*) from client_channel_link_token where token_hash = ?", token)).isEqualTo("0");

        SENT.clear();
        assertThat(send(booking, MessageKind.CONFIRMATION, true)).isTrue();
        assertThat(SENT).hasSize(1);
        assertThat(SENT.get(0)[0]).isEqualTo("70001");
        assertThat(SENT.get(0)[1]).contains("Haircut");
        assertThat(delivery(booking, MessageKind.CONFIRMATION)).isEqualTo("SENT|");
        assertThat(manualTasks(booking)).isZero();

        assertThat(send(booking, MessageKind.CONFIRMATION, true)).as("same visit, kind and start: nothing is sent twice").isFalse();
        assertThat(SENT).hasSize(1);
    }

    @Test
    void aClientWhoBlockedTheBotIsUnlinkedAndTheOwnerGetsTheText() throws Exception {
        Booking booking = book(phone(2), "en");
        bind(booking, 70_010);
        answer = 403;

        assertThat(send(booking, MessageKind.CONFIRMATION, true)).isTrue();

        assertThat(delivery(booking, MessageKind.CONFIRMATION)).startsWith("FAILED|HTTP 403");
        assertThat(chatOf(booking.clientId())).as("the dead link is dropped").isNull();
        assertThat(manualTasks(booking)).isEqualTo(1);
        assertThat(CALLS.get()).as("403 is final, no retry").isEqualTo(1);
    }

    @Test
    void aProviderErrorIsRetriedAFewTimesThenFailsIntoAManualTask() throws Exception {
        Booking booking = book(phone(3), "en");
        bind(booking, 70_020);
        answer = 500;

        assertThat(send(booking, MessageKind.REMINDER_DAY, true)).isTrue();

        assertThat(CALLS.get()).isEqualTo(3);
        assertThat(delivery(booking, MessageKind.REMINDER_DAY)).startsWith("FAILED|HTTP 500");
        assertThat(chatOf(booking.clientId())).as("a provider error does not unlink").isEqualTo("70020");
        assertThat(manualTasks(booking)).isEqualTo(1);
    }

    @Test
    void aClientWithoutAChatIsSkippedAndGetsAManualTaskUnlessTheOwnerAlreadyHasOne() throws Exception {
        Booking booking = book(phone(4), "en");

        assertThat(send(booking, MessageKind.CONFIRMATION, true)).isTrue();
        assertThat(delivery(booking, MessageKind.CONFIRMATION)).startsWith("SKIPPED_NO_CHAT|");
        assertThat(manualTasks(booking)).isEqualTo(1);
        assertThat(CALLS.get()).isZero();

        assertThat(send(booking, MessageKind.CONFIRMATION_PENDING, false)).isTrue();
        assertThat(delivery(booking, MessageKind.CONFIRMATION_PENDING)).startsWith("SKIPPED_NO_CHAT|");
        assertThat(manualTasks(booking)).as("manualFallback=false adds no second task").isEqualTo(1);
    }

    @Test
    void theWebhookChecksTheSecretAndAcceptsEverythingElse() throws Exception {
        Booking booking = book(phone(5), "en");
        String update = startUpdate(70_030, startToken(booking));

        assertThat(webhook(null, update)).isEqualTo(401);
        assertThat(webhook("wrong-secret", update)).isEqualTo(401);
        assertThat(chatOf(booking.clientId())).isNull();

        assertThat(webhook(SECRET, "{\"update_id\": 2, \"edited_channel_post\": {}}")).as("unknown update").isEqualTo(200);
        assertThat(webhook(SECRET, "not json")).as("malformed body").isEqualTo(200);
        assertThat(webhook(SECRET, startUpdate(70_030, "unknown-token-value"))).isEqualTo(200);
        assertThat(chatOf(booking.clientId())).isNull();

        assertThat(webhook(SECRET, update)).isEqualTo(200);
        assertThat(chatOf(booking.clientId())).isEqualTo("70030");
    }

    @Test
    void aPublicLinkCannotTakeOverAClientWhoAlreadyHasAChatButTheOwnerCanRelink() throws Exception {
        Booking booking = book(phone(6), "en");
        bind(booking, 70_040);

        mvc.perform(post("/api/public/bookings/" + booking.id() + "/telegram-link").param("token", booking.token()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/app/clients/" + booking.clientId() + "/telegram-link").with(owner(DemoIds.LUMEN)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/app/clients/" + booking.clientId() + "/telegram-link").with(TestJwt.master(DemoIds.LUMEN, DemoIds.NINO)))
                .andExpect(status().isForbidden());
    }

    @Test
    void remindersGoOutOnceReflectTheMoveAndSkipCancelledVisits() throws Exception {
        Booking booking = book(phone(7), "en");
        bind(booking, 70_050);
        // Booked three days ago, starting in five hours: due for the day reminder.
        place(booking, 5, 72);
        run();
        assertThat(SENT).hasSize(1);
        run();
        assertThat(SENT).as("a second run sends nothing").hasSize(1);

        // Moved to an hour from now: a new start is a new key, the short reminder goes out.
        place(booking, 1, 72);
        run();
        run();
        assertThat(SENT).hasSize(2);

        // Moved again and then cancelled: not reminded about.
        SENT.clear();
        update("update appointment set status = 'CANCELLED_BY_CLIENT' where id = ?::uuid", booking.id());
        place(booking, 3, 72);
        run();
        assertThat(SENT).isEmpty();
    }

    @Test
    void aVisitBookedInsideTheReminderWindowGetsNoReminder() throws Exception {
        Booking booking = book(phone(8), "en");
        bind(booking, 70_060);
        place(booking, 20, 1);

        run();
        assertThat(SENT).isEmpty();
    }

    /** One run of the reminder job for the demo tenant. Seeded visits may be reminded too (no chat: owner tasks); assert on the stub. */
    private void run() {
        reminders.remindTenant(tenants.requireById(DemoIds.LUMEN), Instant.now());
    }

    /** Puts the visit {@code startInHours} from now, created {@code createdHoursAgo} ago; a cancelled visit stays cancelled. */
    private void place(Booking booking, int startInHours, int createdHoursAgo) throws SQLException {
        update("""
                update appointment set status = case when status like 'CANCELLED%' then status else 'CONFIRMED' end,
                    service_start = (now() at time zone 'utc') + make_interval(hours => ?),
                    service_end = (now() at time zone 'utc') + make_interval(hours => ?, mins => 30),
                    occupied_start = (now() at time zone 'utc') + make_interval(hours => ?),
                    occupied_end = (now() at time zone 'utc') + make_interval(hours => ?, mins => 30),
                    created_at = (now() at time zone 'utc') - make_interval(hours => ?),
                    specialist_id = '00000000-0000-4000-8000-000000000013'
                where id = ?::uuid
                """, startInHours, startInHours, startInHours, startInHours, createdHoursAgo, booking.id());
    }
}

package com.cadence.app;

import com.cadence.app.reminder.VisitReminderJob;
import com.cadence.app.seed.DemoIds;
import com.cadence.crm.api.CrmService;
import com.cadence.notification.api.ClientMessageService;
import com.cadence.notification.api.DeliveryRunner;
import com.cadence.notification.api.MessageKind;
import com.cadence.notification.api.VisitMessage;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    /** Handlers still running in the stub (a delayed answer outlives the test that asked for it). */
    private static final AtomicInteger INFLIGHT = new AtomicInteger();
    private static volatile int answer = 200;
    private static volatile long delayMs = 0;
    /** The next N calls answer {@code failStatus} (429 carries retry_after 1), then the stub answers {@code answer}. */
    private static final AtomicInteger failNext = new AtomicInteger();
    private static volatile int failStatus = 500;

    static {
        try {
            BOT = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
        BOT.setExecutor(Executors.newCachedThreadPool());
        BOT.createContext("/", exchange -> {
            CALLS.incrementAndGet();
            INFLIGHT.incrementAndGet();
            try {
                handle(exchange);
            } finally {
                INFLIGHT.decrementAndGet();
            }
        });
        BOT.start();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        int status = failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? failStatus : answer;
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        byte[] reply;
        if (status == 200) {
            JsonNode json = new ObjectMapper().readTree(body);
            SENT.add(new String[] {json.path("chat_id").asText(), json.path("text").asText()});
            reply = "{\"ok\":true,\"result\":{}}".getBytes(StandardCharsets.UTF_8);
        } else {
            reply = ("{\"ok\":false,\"error_code\":" + status + ",\"description\":\"stub says " + status + "\""
                    + (status == 429 ? ",\"parameters\":{\"retry_after\":1}" : "") + "}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        exchange.sendResponseHeaders(status, reply.length);
        exchange.getResponseBody().write(reply);
        exchange.close();
    }

    @DynamicPropertySource
    static void telegram(DynamicPropertyRegistry registry) {
        registry.add("app.telegram.enabled", () -> "true");
        registry.add("app.telegram.bot-token", () -> "123456789:STUB_token_for_the_stub_server_only");
        registry.add("app.telegram.bot-username", () -> "cadence_stub_bot");
        registry.add("app.telegram.webhook-secret", () -> SECRET);
        registry.add("app.telegram.api-base-url", () -> "http://127.0.0.1:" + BOT.getAddress().getPort());
        registry.add("app.telegram.max-attempts", () -> "3");
        registry.add("app.telegram.read-timeout", () -> "1s");
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
    DeliveryRunner deliveries;
    @Autowired
    CrmService crm;
    @Autowired
    VisitReminderJob reminders;
    @Autowired
    TenantDirectory tenants;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void resetBot() throws SQLException, InterruptedException {
        // Leftovers of an earlier test (a message put back in the queue) must not be delivered during this one.
        update("update visit_message_dispatch set status = 'DONE' where status <> 'DONE'");
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (INFLIGHT.get() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        SENT.clear();
        CALLS.set(0);
        answer = 200;
        delayMs = 0;
        failNext.set(0);
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(DB.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
    }

    private record Booking(String id, String token, UUID clientId, Instant start, String phone) {
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
        // Other ITs of this JVM may leave "confirm new clients" on, which would make the visit PENDING; these tests are about CONFIRMED ones.
        update("update appointment set status = 'CONFIRMED' where id = ?::uuid", id);
        return new Booking(id, body.get("accessToken").asText(), UUID.fromString(scalar("select client_id from appointment where id = ?::uuid", id)),
                Instant.parse(body.get("serviceStart").asText()), phone);
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
                {"update_id": 1, "message": {"chat": {"id": %d, "type": "private"}, "from": {"id": %d, "language_code": "ru"}, "text": "/start %s"}}
                """.formatted(chatId, chatId, token);
    }

    /** The contact a person shares: {@code userId} is the owner of the contact, {@code fromId} the sender. */
    private static String contactUpdate(long chatId, long fromId, long userId, String phoneWithoutPlus) {
        return """
                {"update_id": 2, "message": {"chat": {"id": %d, "type": "private"}, "from": {"id": %d, "language_code": "en"},
                 "contact": {"phone_number": "%s", "user_id": %d}}}
                """.formatted(chatId, fromId, phoneWithoutPlus, userId);
    }

    private static String withoutPlus(String phone) {
        return phone.substring(1);
    }

    private void start(Booking booking, long chatId) throws Exception {
        assertThat(webhook(SECRET, startUpdate(chatId, startToken(booking)))).isEqualTo(200);
    }

    /** The full binding as the client does it: Start, then their own contact. */
    private void bind(Booking booking, long chatId) throws Exception {
        start(booking, chatId);
        assertThat(webhook(SECRET, contactUpdate(chatId, chatId, chatId, withoutPlus(booking.phone())))).isEqualTo(200);
        assertThat(chatOf(booking.clientId())).isEqualTo(String.valueOf(chatId));
        SENT.clear();
        CALLS.set(0);
    }

    /** Queues the message and runs the delivery worker once. */
    private boolean send(Booking booking, MessageKind kind, boolean manualFallback) {
        boolean queued;
        try (TenantContext.Scope ignored = TenantContext.open(DemoIds.LUMEN)) {
            ActorContext.set(ActorContext.system());
            queued = clientMessages.enqueue(new VisitMessage(UUID.fromString(booking.id()), booking.clientId(), kind, "Haircut", booking.start(), manualFallback));
        } finally {
            ActorContext.clear();
        }
        deliveries.runOnce();
        return queued;
    }

    private String delivery(Booking booking, MessageKind kind) throws SQLException {
        return scalar("select status || '|' || coalesce(error, '') from notification_delivery"
                + " where appointment_id = ?::uuid and template_key = ? and channel_id = 'telegram' order by created_at desc limit 1",
                booking.id(), kind.name());
    }

    private long manualTasks(Booking booking) throws SQLException {
        return Long.parseLong(scalar("select count(*) from owner_task where appointment_id = ?::uuid and kind = 'MANUAL_MESSAGE'", booking.id()));
    }

    private String queueStatus(Booking booking, MessageKind kind) throws SQLException {
        return scalar("select status from visit_message_dispatch where appointment_id = ?::uuid and kind = ?", booking.id(), kind.name());
    }

    @Test
    void startAsksForTheContactAndTheOwnContactBindsOnceThenTheConfirmationIsSentOnce() throws Exception {
        Booking booking = book(phone(1), "ru");
        String token = startToken(booking);

        assertThat(webhook(SECRET, startUpdate(70_001, token))).isEqualTo(200);
        assertThat(chatOf(booking.clientId())).as("a link alone binds nothing").isNull();
        assertThat(SENT).hasSize(1);
        assertThat(SENT.get(0)[1]).contains("поделитесь");

        SENT.clear();
        assertThat(webhook(SECRET, contactUpdate(70_001, 70_001, 70_001, withoutPlus(booking.phone())))).isEqualTo(200);
        assertThat(chatOf(booking.clientId())).isEqualTo("70001");
        assertThat(SENT.get(0)[1]).as("the answer is in the client's locale").contains("Готово");
        // The token is spent: a second Start with it is answered as expired and binds nothing.
        SENT.clear();
        assertThat(webhook(SECRET, startUpdate(70_002, token))).isEqualTo(200);
        assertThat(SENT.get(0)[1]).contains("Ссылка устарела");
        assertThat(scalar("select used_at is not null from client_channel_link_token"
                + " where token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')", token)).isEqualTo("t");
        assertThat(scalar("select count(*) from client_channel_link_token where token_hash = ?", token)).isEqualTo("0");

        SENT.clear();
        assertThat(send(booking, MessageKind.CONFIRMATION, true)).isTrue();
        assertThat(SENT).hasSize(1);
        assertThat(SENT.get(0)[0]).isEqualTo("70001");
        assertThat(SENT.get(0)[1]).contains("Haircut");
        assertThat(delivery(booking, MessageKind.CONFIRMATION)).isEqualTo("SENT|");
        assertThat(queueStatus(booking, MessageKind.CONFIRMATION)).isEqualTo("DONE");
        assertThat(manualTasks(booking)).isZero();

        assertThat(send(booking, MessageKind.CONFIRMATION, true)).as("same visit, kind and start: nothing is queued twice").isFalse();
        assertThat(SENT).hasSize(1);
    }

    @Test
    void aForeignContactDoesNotBindAndSpendsTheToken() throws Exception {
        Booking booking = book(phone(11), "en");
        start(booking, 70_100);
        SENT.clear();

        // Someone else's number: the visitor holds the link of a booking made with a number that is not theirs.
        assertThat(webhook(SECRET, contactUpdate(70_100, 70_100, 70_100, "995555999999"))).isEqualTo(200);
        assertThat(chatOf(booking.clientId())).isNull();
        assertThat(SENT.get(0)[1]).contains("does not match");
        // The token is spent: even the right contact afterwards binds nothing.
        assertThat(webhook(SECRET, contactUpdate(70_100, 70_100, 70_100, withoutPlus(booking.phone())))).isEqualTo(200);
        assertThat(chatOf(booking.clientId())).isNull();
    }

    @Test
    void aContactOfAnotherTelegramUserDoesNotBindEvenWithTheRightNumber() throws Exception {
        Booking booking = book(phone(12), "en");
        start(booking, 70_110);

        // The sender forwards the owner's contact card: contact.user_id is not the sender.
        assertThat(webhook(SECRET, contactUpdate(70_110, 70_110, 12_345, withoutPlus(booking.phone())))).isEqualTo(200);
        assertThat(chatOf(booking.clientId())).isNull();
    }

    @Test
    void aContactWithoutStartAndTheSameNumberFormattedWithAPlusAreHandled() throws Exception {
        Booking booking = book(phone(13), "en");
        assertThat(webhook(SECRET, contactUpdate(70_120, 70_120, 70_120, withoutPlus(booking.phone())))).as("no Start before").isEqualTo(200);
        assertThat(chatOf(booking.clientId())).isNull();

        start(booking, 70_120);
        assertThat(webhook(SECRET, contactUpdate(70_120, 70_120, 70_120, booking.phone()))).isEqualTo(200);
        assertThat(chatOf(booking.clientId())).isEqualTo("70120");
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
    void unlinkingDropsOnlyTheChatThatFailed() throws Exception {
        Booking booking = book(phone(14), "en");
        bind(booking, 70_130);
        try (TenantContext.Scope ignored = TenantContext.open(DemoIds.LUMEN)) {
            crm.unlinkTelegram(booking.clientId(), "70131", "test");
        }
        assertThat(chatOf(booking.clientId())).as("another chat was meant").isEqualTo("70130");
        try (TenantContext.Scope ignored = TenantContext.open(DemoIds.LUMEN)) {
            crm.unlinkTelegram(booking.clientId(), "70130", "test");
        }
        assertThat(chatOf(booking.clientId())).isNull();
    }

    @Test
    void aProviderErrorIsRetriedAFewTimesThenFailsIntoAManualTask() throws Exception {
        Booking booking = book(phone(3), "en");
        bind(booking, 70_020);
        answer = 500;

        assertThat(send(booking, MessageKind.CONFIRMATION, true)).isTrue();

        assertThat(CALLS.get()).isEqualTo(3);
        assertThat(delivery(booking, MessageKind.CONFIRMATION)).startsWith("FAILED|HTTP 500");
        assertThat(chatOf(booking.clientId())).as("a provider error does not unlink").isEqualTo("70020");
        assertThat(manualTasks(booking)).isEqualTo(1);
    }

    @Test
    void tooManyRequestsWaitsForRetryAfterAndThenSucceeds() throws Exception {
        Booking booking = book(phone(15), "en");
        bind(booking, 70_140);
        failStatus = 429;
        failNext.set(1);

        long started = System.nanoTime();
        send(booking, MessageKind.CONFIRMATION, true);

        assertThat(System.nanoTime() - started).as("waited for retry_after=1").isGreaterThan(900_000_000L);
        assertThat(CALLS.get()).isEqualTo(2);
        assertThat(delivery(booking, MessageKind.CONFIRMATION)).isEqualTo("SENT|");
    }

    @Test
    void aStuckProviderTimesOutAndFallsBack() throws Exception {
        Booking booking = book(phone(16), "en");
        bind(booking, 70_150);
        delayMs = 2_500;

        send(booking, MessageKind.CONFIRMATION, true);

        assertThat(delivery(booking, MessageKind.CONFIRMATION)).startsWith("FAILED|no answer: HttpTimeoutException");
        assertThat(manualTasks(booking)).isEqualTo(1);
    }

    @Test
    void aClientWithoutAChatIsSkippedAndGetsAManualTaskOnlyWhenAsked() throws Exception {
        Booking booking = book(phone(4), "en");

        assertThat(send(booking, MessageKind.CONFIRMATION, true)).isTrue();
        assertThat(delivery(booking, MessageKind.CONFIRMATION)).startsWith("SKIPPED_NO_CHAT|");
        assertThat(manualTasks(booking)).isEqualTo(1);
        assertThat(CALLS.get()).isZero();

        assertThat(send(booking, MessageKind.REMINDER_DAY, false)).as("nobody to remind: not queued").isFalse();
        assertThat(delivery(booking, MessageKind.REMINDER_DAY)).isNull();
        assertThat(manualTasks(booking)).as("a reminder never becomes an owner task").isEqualTo(1);
    }

    @Test
    void aFailedReminderCreatesNoOwnerTask() throws Exception {
        Booking booking = book(phone(17), "en");
        bind(booking, 70_160);
        answer = 500;

        send(booking, MessageKind.REMINDER_DAY, false);

        assertThat(delivery(booking, MessageKind.REMINDER_DAY)).startsWith("FAILED|HTTP 500");
        assertThat(manualTasks(booking)).isZero();
    }

    @Test
    void theVisitIsCheckedRightBeforeSending() throws Exception {
        Booking cancelled = book(phone(18), "en");
        bind(cancelled, 70_170);
        update("update appointment set status = 'CANCELLED_BY_CLIENT' where id = ?::uuid", cancelled.id());
        send(cancelled, MessageKind.CONFIRMATION, true);
        assertThat(SENT).as("cancelled visit").isEmpty();
        assertThat(queueStatus(cancelled, MessageKind.CONFIRMATION)).isEqualTo("DONE");

        Booking moved = book(phone(19), "en");
        bind(moved, 70_180);
        try (TenantContext.Scope ignored = TenantContext.open(DemoIds.LUMEN)) {
            ActorContext.set(ActorContext.system());
            clientMessages.enqueue(new VisitMessage(UUID.fromString(moved.id()), moved.clientId(), MessageKind.CONFIRMATION, "Haircut", moved.start(), true));
        } finally {
            ActorContext.clear();
        }
        update("update appointment set service_start = service_start + interval '1 day', service_end = service_end + interval '1 day',"
                + " occupied_start = occupied_start + interval '1 day', occupied_end = occupied_end + interval '1 day' where id = ?::uuid", moved.id());
        deliveries.runOnce();
        assertThat(SENT).as("the visit moved after the message was queued").isEmpty();
    }

    @Test
    void twoWorkersNeverSendTheSameMessageTwice() throws Exception {
        int messages = 6;
        for (int i = 0; i < messages; i++) {
            Booking booking = book(phone(30 + i), "en");
            bind(booking, 71_000 + i);
            try (TenantContext.Scope ignored = TenantContext.open(DemoIds.LUMEN)) {
                ActorContext.set(ActorContext.system());
                clientMessages.enqueue(new VisitMessage(UUID.fromString(booking.id()), booking.clientId(), MessageKind.CONFIRMATION, "Haircut", booking.start(), true));
            } finally {
                ActorContext.clear();
            }
        }
        SENT.clear();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> runs = List.of(pool.submit(() -> deliveries.runOnce()), pool.submit(() -> deliveries.runOnce()));
            int handled = 0;
            for (Future<Integer> run : runs) {
                handled += run.get();
            }
            assertThat(handled).isEqualTo(messages);
        } finally {
            pool.shutdown();
        }
        assertThat(SENT).extracting(m -> m[0]).doesNotHaveDuplicates().hasSize(messages);
    }

    @Test
    void aShutdownInsideTheCallLeavesTheMessageInDoubtAndNeverResendsIt() throws Exception {
        Booking booking = book(phone(20), "en");
        bind(booking, 70_190);
        queue(booking);
        delayMs = 5_000;

        Thread worker = interruptWhenSending(() -> deliveries.runOnce(), booking);

        assertThat(queueStatus(booking, MessageKind.CONFIRMATION)).as("outcome unknown: not put back in the queue").isEqualTo("SENDING");
        assertThat(delivery(booking, MessageKind.CONFIRMATION)).isNull();
        assertThat(worker.isAlive()).isFalse();
        // The next pass does not send it again; only the stale path (see the test below) settles it.
        delayMs = 0;
        int callsBefore = CALLS.get();
        deliveries.runOnce();
        assertThat(CALLS.get()).isEqualTo(callsBefore);
    }

    @Test
    void aCrashPutsOnlyTheMessageInFlightInDoubtNotTheWholeBatch() throws Exception {
        Booking first = book(phone(22), "en");
        Booking second = book(phone(23), "en");
        bind(first, 70_210);
        bind(second, 70_211);
        queue(first);
        queue(second);
        delayMs = 5_000;

        interruptWhenSending(() -> deliveries.runOnce(), first, second);

        assertThat(scalar("select count(*) from visit_message_dispatch where status = 'SENDING'")).as("one attempted").isEqualTo("1");
        assertThat(scalar("select count(*) from visit_message_dispatch where status = 'PENDING'")).as("the other untouched").isEqualTo("1");
        // A restart settles exactly the one in doubt as stale and delivers the other normally.
        update("update visit_message_dispatch set claimed_at = (now() at time zone 'utc') - interval '1 hour' where status = 'SENDING'");
        delayMs = 0;
        deliveries.runOnce();
        assertThat(scalar("select count(*) from notification_delivery where status = 'FAILED' and error like 'Delivery state unknown%'"
                + " and appointment_id in (?::uuid, ?::uuid)", first.id(), second.id())).isEqualTo("1");
        assertThat(SENT).as("the second visit was delivered normally").filteredOn(m -> m[0].equals("70211")).hasSize(1);
    }

    @Test
    void concurrentClaimsInOpenTransactionsGetDifferentRows() throws Exception {
        Booking first = book(phone(24), "en");
        Booking second = book(phone(25), "en");
        bind(first, 70_220);
        bind(second, 70_221);
        queue(first);
        queue(second);
        try (Connection a = appConnection(); Connection b = appConnection(); Connection c = appConnection()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            c.setAutoCommit(false);
            UUID fromA = claimOne(a);
            UUID fromB = claimOne(b);
            assertThat(fromA).isNotNull();
            assertThat(fromB).as("A still holds its row in an open transaction").isNotNull().isNotEqualTo(fromA);
            assertThat(claimOne(c)).as("both rows are taken").isNull();
            a.rollback();
            b.rollback();
            c.rollback();
        }
    }

    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(DB.getJdbcUrl(), APP_USER, APP_PASSWORD);
    }

    private static UUID claimOne(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select appointment_id from visit_message_claim(1, localtimestamp - interval '5 minutes')");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getObject(1, UUID.class) : null;
        }
    }

    private void queue(Booking booking) {
        try (TenantContext.Scope ignored = TenantContext.open(DemoIds.LUMEN)) {
            ActorContext.set(ActorContext.system());
            clientMessages.enqueue(new VisitMessage(UUID.fromString(booking.id()), booking.clientId(), MessageKind.CONFIRMATION, "Haircut", booking.start(), true));
        } finally {
            ActorContext.clear();
        }
    }

    /** Runs the pass in a thread, waits until one of the visits is SENDING (its call is in flight), interrupts and joins it. */
    private Thread interruptWhenSending(Runnable pass, Booking... visits) throws Exception {
        Thread worker = new Thread(pass);
        worker.start();
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline && Long.parseLong(scalar("select count(*) from visit_message_dispatch where status = 'SENDING'")) == 0) {
            Thread.sleep(50);
        }
        Thread.sleep(300);
        worker.interrupt();
        worker.join(10_000);
        return worker;
    }

    @Test
    void aShutdownBeforeTheCallPutsTheMessageBackInTheQueue() throws Exception {
        Booking booking = book(phone(26), "en");
        bind(booking, 70_230);
        queue(booking);

        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            deliveries.runOnce();
        });
        worker.start();
        worker.join(10_000);

        assertThat(CALLS.get()).isZero();
        assertThat(queueStatus(booking, MessageKind.CONFIRMATION)).isEqualTo("PENDING");
    }

    @Test
    void aSecondStartOnTheSameLinkAsksForTheContactAgain() throws Exception {
        Booking booking = book(phone(27), "en");
        String token = startToken(booking);
        assertThat(webhook(SECRET, startUpdate(70_240, token))).isEqualTo(200);
        SENT.clear();

        assertThat(webhook(SECRET, startUpdate(70_240, token))).isEqualTo(200);
        assertThat(SENT).hasSize(1);
        assertThat(SENT.get(0)[1]).as("asks for the contact again (the sender's language)").contains("поделитесь").doesNotContain("устарела");
        SENT.clear();
        // Another chat pressing Start on the same link is refused.
        assertThat(webhook(SECRET, startUpdate(70_241, token))).isEqualTo(200);
        assertThat(SENT.get(0)[1]).contains("устарела");
    }

    @Test
    void aContactWithoutUserIdDoesNotBind() throws Exception {
        Booking booking = book(phone(28), "en");
        start(booking, 70_250);

        String noUserId = """
                {"update_id": 3, "message": {"chat": {"id": 70250, "type": "private"}, "from": {"id": 70250},
                 "contact": {"phone_number": "%s"}}}
                """.formatted(withoutPlus(booking.phone()));
        assertThat(webhook(SECRET, noUserId)).isEqualTo(200);
        assertThat(chatOf(booking.clientId())).isNull();
    }

    @Test
    void aVisitInThePastGetsNoMessageAndNoOwnerTask() throws Exception {
        Booking booking = book(phone(29), "en");
        bind(booking, 70_260);
        // Years back: no seeded or test visit lives there, so the no-overlap constraint cannot get in the way.
        update("update appointment set service_start = timestamp '2020-01-01 03:00', service_end = timestamp '2020-01-01 03:30',"
                + " occupied_start = timestamp '2020-01-01 03:00', occupied_end = timestamp '2020-01-01 03:30',"
                + " specialist_id = '00000000-0000-4000-8000-000000000013' where id = ?::uuid", booking.id());
        Booking past = new Booking(booking.id(), booking.token(), booking.clientId(), Instant.now().minusSeconds(7_200), booking.phone());
        String startNow = scalar("select to_char(service_start, 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') from appointment where id = ?::uuid", booking.id());
        Booking exact = new Booking(past.id(), past.token(), past.clientId(), Instant.parse(startNow), past.phone());

        send(exact, MessageKind.CONFIRMATION, true);

        assertThat(SENT).isEmpty();
        assertThat(manualTasks(exact)).isZero();
        assertThat(queueStatus(exact, MessageKind.CONFIRMATION)).isEqualTo("DONE");
    }

    @Test
    void aMessageLeftInFlightByADeadProcessIsNotSentAgainButFallsBack() throws Exception {
        Booking booking = book(phone(21), "en");
        bind(booking, 70_200);
        try (TenantContext.Scope ignored = TenantContext.open(DemoIds.LUMEN)) {
            ActorContext.set(ActorContext.system());
            clientMessages.enqueue(new VisitMessage(UUID.fromString(booking.id()), booking.clientId(), MessageKind.CONFIRMATION, "Haircut", booking.start(), true));
        } finally {
            ActorContext.clear();
        }
        update("update visit_message_dispatch set status = 'SENDING', claimed_at = (now() at time zone 'utc') - interval '1 hour' where appointment_id = ?::uuid", booking.id());

        deliveries.runOnce();

        assertThat(CALLS.get()).isZero();
        assertThat(delivery(booking, MessageKind.CONFIRMATION)).startsWith("FAILED|Delivery state unknown");
        assertThat(manualTasks(booking)).isEqualTo(1);
        assertThat(queueStatus(booking, MessageKind.CONFIRMATION)).isEqualTo("DONE");
    }

    @Test
    void aMessageThatCannotBePreparedDoesNotBlockTheQueueAndIsClosedAsFailedAfterTheAttempts() throws Exception {
        UUID clientB = UUID.randomUUID();
        UUID visitB = UUID.randomUUID();
        update("insert into client (id, tenant_id, normalized_phone, display_name, marketing_consent) values (?, ?, '+420777000111', 'Tz It', false)",
                clientB, DemoIds.AURA);
        update("""
                insert into appointment (id, tenant_id, specialist_id, service_id, client_id, status, service_start, service_end,
                    occupied_start, occupied_end, service_name_snapshot, price_snapshot, duration_snapshot, buffer_before_snapshot,
                    buffer_after_snapshot, currency_code, source)
                values (?, ?, ?, ?, ?, 'CONFIRMED', now() at time zone 'utc' + interval '3 days', now() at time zone 'utc' + interval '3 days 1 hour',
                    now() at time zone 'utc' + interval '3 days', now() at time zone 'utc' + interval '3 days 1 hour', 'Cut', 0, 60, 0, 0, 'CZK', 'TG_IT')
                """, visitB, DemoIds.AURA, DemoIds.AURA_MASTER, DemoIds.AURA_CUT, clientB);
        Instant startB = Instant.parse(scalar("select to_char(service_start, 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') from appointment where id = ?", visitB));
        Booking good = book(phone(40), "en");
        bind(good, 71_100);
        try {
            update("update tenant set timezone = 'Not/AZone' where id = ?", DemoIds.AURA);
            // The broken tenant's message is the oldest in the queue; it must not hold the other one back.
            try (TenantContext.Scope ignored = TenantContext.open(DemoIds.AURA)) {
                ActorContext.set(ActorContext.system());
                clientMessages.enqueue(new VisitMessage(visitB, clientB, MessageKind.CONFIRMATION, "Cut", startB, true));
            } finally {
                ActorContext.clear();
            }
            queue(good);

            deliveries.runOnce();

            assertThat(SENT).as("the healthy tenant's message went out").filteredOn(m -> m[0].equals("71100")).hasSize(1);
            assertThat(scalar("select status || ':' || attempts from visit_message_dispatch where appointment_id = ?", visitB)).isEqualTo("PENDING:1");
            int calls = CALLS.get();
            deliveries.runOnce();
            assertThat(scalar("select attempts from visit_message_dispatch where appointment_id = ?", visitB)).as("backed off, not retried at once").isEqualTo("1");
            assertThat(CALLS.get()).isEqualTo(calls);

            // Attempts used up: closed as FAILED, the owner gets a task.
            update("update visit_message_dispatch set attempts = 4, next_attempt_at = null where appointment_id = ?", visitB);
            deliveries.runOnce();
            assertThat(scalar("select status from visit_message_dispatch where appointment_id = ?", visitB)).isEqualTo("DONE");
            assertThat(scalar("select status || '|' || error from notification_delivery where appointment_id = ? and channel_id = 'telegram'", visitB))
                    .startsWith("FAILED|Could not be prepared");
            assertThat(scalar("select count(*) from owner_task where appointment_id = ? and kind = 'MANUAL_MESSAGE'", visitB)).isEqualTo("1");
        } finally {
            update("update tenant set timezone = 'Europe/Prague' where id = ?", DemoIds.AURA);
            update("delete from owner_task where appointment_id = ?", visitB);
            update("delete from notification_delivery where appointment_id = ?", visitB);
            update("delete from visit_message_dispatch where appointment_id = ?", visitB);
            update("delete from appointment where id = ?", visitB);
            update("delete from client where id = ?", clientB);
        }
    }

    @Test
    void purgeRemovesOnlyHandledRowsOlderThanTheCutoff() throws Exception {
        Booking booking = book(phone(41), "en");
        String sql = "insert into visit_message_dispatch (tenant_id, appointment_id, kind, service_start, client_id, service_name, manual_fallback, status, created_at)"
                + " values (?, ?::uuid, ?, timestamp '2030-02-01 10:00', ?, 'x', false, ?, ?::timestamp)";
        update(sql, DemoIds.LUMEN, booking.id(), "KEEP_PENDING_OLD", booking.clientId(), "PENDING", "2030-01-01 00:00:00");
        update(sql, DemoIds.LUMEN, booking.id(), "KEEP_SENDING_OLD", booking.clientId(), "SENDING", "2030-01-01 00:00:00");
        update(sql, DemoIds.LUMEN, booking.id(), "DROP_DONE_BEFORE", booking.clientId(), "DONE", "2030-01-09 23:59:59");
        update(sql, DemoIds.LUMEN, booking.id(), "KEEP_DONE_AFTER", booking.clientId(), "DONE", "2030-01-10 00:00:01");
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), APP_USER, APP_PASSWORD);
             PreparedStatement ps = c.prepareStatement("select visit_message_purge(timestamp '2030-01-10 00:00:00')");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(1);
        }
        assertThat(scalar("select string_agg(kind, ',' order by kind) from visit_message_dispatch where appointment_id = ?::uuid and kind like '%\\_%'", booking.id()))
                .isEqualTo("KEEP_DONE_AFTER,KEEP_PENDING_OLD,KEEP_SENDING_OLD");
        update("delete from visit_message_dispatch where appointment_id = ?::uuid and kind like 'KEEP%'", booking.id());
    }

    @Test
    void theWebhookChecksTheSecretAndAcceptsEverythingElse() throws Exception {
        Booking booking = book(phone(5), "en");
        String update = startUpdate(70_030, startToken(booking));

        assertThat(webhook(null, update)).isEqualTo(401);
        assertThat(webhook("wrong-secret", update)).isEqualTo(401);
        assertThat(SENT).isEmpty();

        assertThat(webhook(SECRET, "{\"update_id\": 2, \"edited_channel_post\": {}}")).as("unknown update").isEqualTo(200);
        assertThat(webhook(SECRET, "not json")).as("malformed body").isEqualTo(200);
        assertThat(webhook(SECRET, startUpdate(70_030, "unknown-token-value"))).isEqualTo(200);
        assertThat(chatOf(booking.clientId())).isNull();
    }

    @Test
    void thePublicLinkAnswersAlikeForALinkedAndAnUnlinkedClientAndOnlyTheOwnerCanRelink() throws Exception {
        Booking booking = book(phone(6), "en");
        mvc.perform(post("/api/public/bookings/" + booking.id() + "/telegram-link").param("token", booking.token())).andExpect(status().isOk());
        bind(booking, 70_040);

        mvc.perform(post("/api/public/bookings/" + booking.id() + "/telegram-link").param("token", booking.token()))
                .andExpect(status().isOk());
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

    /** One run of the reminder job for the demo tenant, then the delivery worker. Only the stub matters here. */
    private void run() {
        reminders.remindTenant(tenants.requireById(DemoIds.LUMEN), Instant.now());
        deliveries.runOnce();
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

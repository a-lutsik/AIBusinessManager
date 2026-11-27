package com.cadence.notification.internal;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The Bot API client against a local stub (no real Telegram call): what an interrupt means depends on what came before it. */
class TelegramBotClientTest {

    private HttpServer stub;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile long firstCallDelayMs;
    private volatile int status = 500;

    @BeforeEach
    void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.setExecutor(Executors.newCachedThreadPool());
        stub.createContext("/", exchange -> {
            int call = calls.incrementAndGet();
            try {
                if (call == 1 && firstCallDelayMs > 0) {
                    Thread.sleep(firstCallDelayMs);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.getRequestBody().readAllBytes();
            byte[] reply = ("{\"ok\":false,\"description\":\"stub\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        stub.start();
    }

    @AfterEach
    void stopStub() {
        stub.stop(0);
    }

    private TelegramBotClient client() {
        return new TelegramBotClient(new TelegramProperties(true, "123456789:STUB_token_for_the_stub_server_only", "cadence_bot", "secret",
                "http://127.0.0.1:" + stub.getAddress().getPort(), Duration.ofSeconds(1), Duration.ofMillis(300), 3, Duration.ofHours(1), 1000));
    }

    private TelegramBotClient.Result sendAndInterruptWhen(int callsSeen) throws InterruptedException {
        AtomicReference<TelegramBotClient.Result> result = new AtomicReference<>();
        Thread caller = new Thread(() -> result.set(client().sendMessage("1", "hi", null)));
        caller.start();
        // Interrupt only once the client is in its retry pause after the call it was waiting for. A fixed delay after the
        // stub saw the request raced on slow CI runners: the interrupt could land while the answer was still on its way,
        // i.e. inside the call, which is a different (unknown) outcome.
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (calls.get() < callsSeen || !inRetryPause(caller)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the client never reached its retry pause after call " + callsSeen);
            }
            Thread.sleep(5);
        }
        caller.interrupt();
        caller.join(10_000);
        return result.get();
    }

    /** Whether the thread is parked in {@code TelegramBotClient.sleep}, the pause between attempts. */
    private static boolean inRetryPause(Thread thread) {
        if (thread.getState() != Thread.State.TIMED_WAITING) {
            return false;
        }
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (frame.getClassName().equals(TelegramBotClient.class.getName()) && frame.getMethodName().equals("sleep")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void anInterruptWhileWaitingAfterARefusalIsClean() throws Exception {
        TelegramBotClient.Result result = sendAndInterruptWhen(1);

        assertThat(result.interrupted()).isTrue();
        assertThat(result.outcomeUnknown()).isFalse();
    }

    @Test
    void anInterruptAfterAnEarlierCallWithoutAnAnswerIsUnknownEvenIfTheLastCallWasRefused() throws Exception {
        firstCallDelayMs = 1_500;

        TelegramBotClient.Result result = sendAndInterruptWhen(2);

        assertThat(result.httpStatus()).isZero();
        assertThat(result.interrupted()).as("the first call may have reached Telegram").isFalse();
        assertThat(result.outcomeUnknown()).isTrue();
    }
}

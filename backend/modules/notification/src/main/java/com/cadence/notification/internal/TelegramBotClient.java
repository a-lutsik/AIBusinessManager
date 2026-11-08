package com.cadence.notification.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Bot API {@code sendMessage} over {@link HttpClient} with connect and read timeouts and a bounded retry (network error,
 * 5xx, 429 with Telegram's {@code retry_after}, at most {@code max-attempts} tries and a few seconds of waiting in all).
 * The bot token is part of the request URL, so no URL, request or exception message is ever logged or returned: failures
 * are reported by HTTP status and Telegram's {@code description} only.
 */
@Component
class TelegramBotClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotClient.class);
    private static final Duration MAX_WAIT = Duration.ofSeconds(2);
    private static final int MAX_ERROR_CHARS = 300;

    /** {@code httpStatus} 0 means no answer (network error or timeout). */
    record Result(boolean ok, int httpStatus, String description) {

        boolean forbidden() {
            return httpStatus == 403;
        }

        String reason() {
            String text = httpStatus == 0 ? "no answer: " + description : "HTTP " + httpStatus + ": " + description;
            return text.length() > MAX_ERROR_CHARS ? text.substring(0, MAX_ERROR_CHARS) : text;
        }
    }

    private final TelegramProperties props;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    TelegramBotClient(TelegramProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
    }

    Result sendMessage(String chatId, String text) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(props.apiBaseUrl() + "/bot" + props.botToken() + "/sendMessage"))
                    .timeout(props.readTimeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(Map.of("chat_id", chatId, "text", text, "disable_web_page_preview", true)),
                            StandardCharsets.UTF_8))
                    .build();
        } catch (IOException | IllegalArgumentException ex) {
            return new Result(false, 0, "request could not be built: " + ex.getClass().getSimpleName());
        }
        Result result = null;
        for (int attempt = 1; attempt <= props.maxAttempts(); attempt++) {
            Duration pause = Duration.ofMillis(250L * attempt);
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                result = parse(response);
                if (result.ok() || !retryable(result.httpStatus())) {
                    return result;
                }
                pause = retryAfter(response).orElse(pause);
            } catch (IOException ex) {
                result = new Result(false, 0, ex.getClass().getSimpleName());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return new Result(false, 0, "interrupted");
            }
            if (attempt < props.maxAttempts()) {
                log.debug("Telegram sendMessage attempt {} of {} failed ({}), retrying", attempt, props.maxAttempts(), result.reason());
                if (!sleep(pause.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : pause)) {
                    return result;
                }
            }
        }
        return result;
    }

    private Result parse(HttpResponse<String> response) {
        int status = response.statusCode();
        String description = "";
        try {
            JsonNode body = json.readTree(response.body());
            description = body.path("description").asText("");
            if (status == 200 && body.path("ok").asBoolean(false)) {
                return new Result(true, status, "");
            }
        } catch (IOException | RuntimeException ex) {
            description = "unreadable answer";
        }
        return new Result(false, status, description);
    }

    private Optional<Duration> retryAfter(HttpResponse<String> response) {
        if (response.statusCode() != 429) {
            return Optional.empty();
        }
        try {
            int seconds = json.readTree(response.body()).path("parameters").path("retry_after").asInt(0);
            return seconds > 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    private static boolean retryable(int status) {
        return status == 429 || status >= 500;
    }

    private static boolean sleep(Duration pause) {
        try {
            Thread.sleep(pause.toMillis());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

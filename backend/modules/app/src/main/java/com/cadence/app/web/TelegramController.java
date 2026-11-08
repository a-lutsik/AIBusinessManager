package com.cadence.app.web;

import com.cadence.app.ratelimit.ClientIpResolver;
import com.cadence.app.ratelimit.PublicRateLimiter;
import com.cadence.booking.api.BookingService;
import com.cadence.notification.api.TelegramLink;
import com.cadence.notification.api.TelegramLinkService;
import com.cadence.notification.api.TelegramWebhook;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.tenancy.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Telegram channel routes: the bot's webhook and the one-time links that bind a client's chat. The two public routes sit
 * inside the per-IP limits of the public API.
 */
@RestController
public class TelegramController {

    private final TelegramWebhook webhook;
    private final TelegramLinkService links;
    private final BookingService bookings;
    private final PublicRateLimiter rateLimiter;
    private final ClientIpResolver clientIps;

    public TelegramController(
            TelegramWebhook webhook,
            TelegramLinkService links,
            BookingService bookings,
            PublicRateLimiter rateLimiter,
            ClientIpResolver clientIps
    ) {
        this.webhook = webhook;
        this.links = links;
        this.bookings = bookings;
        this.rateLimiter = rateLimiter;
        this.clientIps = clientIps;
    }

    /** Bot API webhook: 200 for every update, 401 when the secret header is missing or wrong. */
    @PostMapping("/api/public/telegram/webhook")
    public ResponseEntity<Void> webhook(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secretToken,
            @RequestBody String update,
            HttpServletRequest http
    ) {
        rateLimiter.checkTelegramWebhook(clientIps.resolve(http));
        return webhook.handle(secretToken, update) ? ResponseEntity.ok().build() : ResponseEntity.status(401).build();
    }

    /** The booking-success page: a one-time Telegram link for the client behind a manage link. */
    @PostMapping("/api/public/bookings/{id}/telegram-link")
    public TelegramLink publicLink(@PathVariable UUID id, @RequestParam String token, HttpServletRequest http) {
        rateLimiter.checkTelegramLink(clientIps.resolve(http));
        return bookings.telegramLinkByAccessToken(id, token);
    }

    /** Owner-only: a one-time Telegram link for a client, to pass on by hand; also re-links a client who has a chat. */
    @PostMapping("/api/app/clients/{id}/telegram-link")
    public TelegramLink ownerLink(@PathVariable UUID id) {
        if (!ActorContext.current().map(ActorContext.Actor::isOwner).orElse(false)) {
            throw DomainException.forbidden("FORBIDDEN_RESOURCE", "Owner-only endpoint");
        }
        return links.issue(id);
    }
}

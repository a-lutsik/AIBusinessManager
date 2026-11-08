package com.cadence.notification.internal;

import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.notification.api.TelegramWebhook;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.i18n.MessageCatalog;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Receives Telegram updates. Only a private-chat message {@code /start <token>} does anything: a live token binds the chat to
 * its client (consent to service messages) and the bot answers in the client's language; a dead token gets a short "link
 * expired" answer in the sender's Telegram language. Any other update is acknowledged and ignored. Chat ids and tokens are
 * never logged.
 */
@Service
class TelegramWebhookService implements TelegramWebhook {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookService.class);
    private static final Pattern START = Pattern.compile("^/start(?:@\\w+)?\\s+([A-Za-z0-9_-]{1,64})\\s*$");

    private record Bound(String locale, String tenantName) {
    }

    private final TelegramProperties props;
    private final TelegramBotClient bot;
    private final LinkTokenStore tokens;
    private final CrmService crm;
    private final TenantDirectory tenants;
    private final MessageCatalog messages;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    TelegramWebhookService(
            TelegramProperties props,
            TelegramBotClient bot,
            LinkTokenStore tokens,
            CrmService crm,
            TenantDirectory tenants,
            MessageCatalog messages,
            PlatformTransactionManager transactionManager
    ) {
        this.props = props;
        this.bot = bot;
        this.tokens = tokens;
        this.crm = crm;
        this.tenants = tenants;
        this.messages = messages;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public boolean handle(String secretTokenHeader, String updateJson) {
        if (!props.enabled() || !secretMatches(secretTokenHeader)) {
            log.warn("Telegram webhook refused: secret token missing or wrong, or the channel is off");
            return false;
        }
        try {
            process(json.readTree(updateJson));
        } catch (Exception ex) {
            log.warn("Telegram update ignored: {}", ex.getClass().getSimpleName());
        }
        return true;
    }

    private void process(JsonNode update) {
        JsonNode message = update.path("message");
        JsonNode chat = message.path("chat");
        if (!"private".equals(chat.path("type").asText()) || !chat.path("id").canConvertToLong()) {
            log.debug("Telegram update ignored: not a private-chat message");
            return;
        }
        Matcher start = START.matcher(message.path("text").asText(""));
        if (!start.matches()) {
            log.debug("Telegram update ignored: not a /start with a token");
            return;
        }
        String chatId = String.valueOf(chat.path("id").asLong());
        Bound bound = bind(LinkTokenStore.hash(start.group(1)), chatId);
        if (bound == null) {
            String language = message.path("from").path("language_code").asText(null);
            reply(chatId, messages.forClient(language, "client.telegram.linkInvalid"));
            return;
        }
        reply(chatId, messages.forClient(bound.locale(), "client.telegram.linked", bound.tenantName()));
    }

    /** Resolves the token, spends it and stores the chat, all or nothing; null when the token is dead. */
    private Bound bind(String tokenHash, String chatId) {
        return tx.execute(status -> {
            Optional<LinkTokenStore.Target> target = tokens.lookup(tokenHash);
            if (target.isEmpty()) {
                log.info("Telegram link refused: token unknown, used or expired");
                return null;
            }
            try (TenantContext.Scope ignored = TenantContext.open(target.get().tenantId())) {
                if (!tokens.consume(tokenHash)) {
                    log.info("Telegram link refused: token was spent by a concurrent request");
                    status.setRollbackOnly();
                    return null;
                }
                ClientView client = crm.linkTelegram(target.get().clientId(), chatId, "telegram-link");
                log.info("Telegram chat bound: tenant={}, client={}", target.get().tenantId(), client.id());
                return new Bound(client.preferredLocale(), tenants.requireById(target.get().tenantId()).getDisplayName());
            } catch (DomainException ex) {
                log.info("Telegram link refused: client {} cannot be linked ({})", target.get().clientId(), ex.getMessage());
                status.setRollbackOnly();
                return null;
            }
        });
    }

    private void reply(String chatId, String text) {
        TelegramBotClient.Result result = bot.sendMessage(chatId, text);
        if (!result.ok()) {
            log.warn("Telegram reply not delivered: {}", result.reason());
        }
    }

    /** Constant-time comparison of the header with the configured secret. */
    private boolean secretMatches(String header) {
        return header != null && MessageDigest.isEqual(
                header.getBytes(StandardCharsets.UTF_8), props.webhookSecret().getBytes(StandardCharsets.UTF_8));
    }
}

package com.cadence.notification.internal;

import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.notification.api.TelegramWebhook;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.i18n.MessageCatalog;
import com.cadence.platform.phone.PhoneNormalizer;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.cadence.platform.jooq.tables.records.TenantRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Receives Telegram updates. Binding a chat to a client takes two steps, because a link only proves that someone holds it,
 * not that they own the client's phone number (anyone can book with any number and get a link):
 * <ol>
 *   <li>{@code /start <token>} in a private chat: the token is tied to that chat and the bot asks the person to share their
 *       contact with the Telegram button;</li>
 *   <li>the shared contact: it must be the sender's own contact ({@code contact.user_id} equals the sender) and its phone,
 *       normalized, must equal the client's phone. Only then the chat is stored. The token is spent by the contact either
 *       way, so a wrong contact needs a new link.</li>
 * </ol>
 * Any other update is acknowledged and ignored. Chat ids, phones and tokens are never logged.
 */
@Service
class TelegramWebhookService implements TelegramWebhook {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookService.class);
    private static final Pattern START = Pattern.compile("^/start(?:@\\w+)?\\s+([A-Za-z0-9_-]{1,64})\\s*$");

    private enum Result { LINKED, MISMATCH, NONE }

    private record Linked(Result result, String locale, String tenantName) {
    }

    private final TelegramProperties props;
    private final TelegramBotClient bot;
    private final LinkTokenStore tokens;
    private final CrmService crm;
    private final TenantDirectory tenants;
    private final PhoneNormalizer phones;
    private final MessageCatalog messages;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    TelegramWebhookService(
            TelegramProperties props,
            TelegramBotClient bot,
            LinkTokenStore tokens,
            CrmService crm,
            TenantDirectory tenants,
            PhoneNormalizer phones,
            MessageCatalog messages,
            PlatformTransactionManager transactionManager
    ) {
        this.props = props;
        this.bot = bot;
        this.tokens = tokens;
        this.crm = crm;
        this.tenants = tenants;
        this.phones = phones;
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
        String chatId = String.valueOf(chat.path("id").asLong());
        String language = message.path("from").path("language_code").asText(null);
        if (message.hasNonNull("contact")) {
            onContact(chatId, message, language);
            return;
        }
        Matcher start = START.matcher(message.path("text").asText(""));
        if (start.matches()) {
            onStart(LinkTokenStore.hash(start.group(1)), chatId, language);
        } else {
            log.debug("Telegram update ignored: neither /start with a token nor a contact");
        }
    }

    private void onStart(String tokenHash, String chatId, String language) {
        Boolean started = tx.execute(status -> {
            Optional<LinkTokenStore.Target> target = tokens.lookup(tokenHash);
            if (target.isEmpty()) {
                return false;
            }
            try (TenantContext.Scope ignored = TenantContext.open(target.get().tenantId())) {
                return tokens.attachChat(tokenHash, chatId);
            }
        });
        if (!Boolean.TRUE.equals(started)) {
            log.info("Telegram link refused: token unknown, used, expired or already started");
            reply(chatId, messages.forClient(language, "client.telegram.linkInvalid"), null);
            return;
        }
        log.info("Telegram link started, waiting for the contact");
        reply(chatId, messages.forClient(language, "client.telegram.shareContact"), Map.of(
                "keyboard", List.of(List.of(Map.of("text", messages.forClient(language, "client.telegram.shareContactButton"), "request_contact", true))),
                "resize_keyboard", true,
                "one_time_keyboard", true));
    }

    private void onContact(String chatId, JsonNode message, String language) {
        JsonNode contact = message.path("contact");
        boolean own = contact.path("user_id").canConvertToLong()
                && String.valueOf(contact.path("user_id").asLong()).equals(String.valueOf(message.path("from").path("id").asLong(-1)))
                && chatId.equals(String.valueOf(contact.path("user_id").asLong()));
        String phone = contact.path("phone_number").asText("");
        Linked linked = tx.execute(status -> {
            Optional<LinkTokenStore.Pending> pending = tokens.pendingOf(chatId);
            if (pending.isEmpty()) {
                return new Linked(Result.NONE, null, null);
            }
            try (TenantContext.Scope ignored = TenantContext.open(pending.get().tenantId())) {
                // Spent by the contact whatever it says: a wrong contact needs a new link.
                if (!tokens.consume(pending.get().tokenHash())) {
                    return new Linked(Result.NONE, null, null);
                }
                Optional<ClientView> client = crm.findActiveById(pending.get().clientId());
                TenantRecord tenant = tenants.requireById(pending.get().tenantId());
                if (client.isEmpty()) {
                    return new Linked(Result.MISMATCH, null, tenant.getDisplayName());
                }
                String locale = client.get().preferredLocale();
                if (!own || !samePhone(phone, client.get().normalizedPhone(), tenant.getCountryCode())) {
                    log.info("Telegram link refused: the shared contact is not the client's own number (own={})", own);
                    return new Linked(Result.MISMATCH, locale, tenant.getDisplayName());
                }
                try {
                    crm.linkTelegram(client.get().id(), chatId, "telegram-link");
                } catch (DomainException ex) {
                    status.setRollbackOnly();
                    return new Linked(Result.MISMATCH, locale, tenant.getDisplayName());
                }
                log.info("Telegram chat bound after contact proof: tenant={}, client={}", tenant.getId(), client.get().id());
                return new Linked(Result.LINKED, locale, tenant.getDisplayName());
            }
        });
        Map<String, Object> removeKeyboard = Map.of("remove_keyboard", true);
        switch (linked.result()) {
            case LINKED -> reply(chatId, messages.forClient(linked.locale(), "client.telegram.linked", linked.tenantName()), removeKeyboard);
            case MISMATCH -> reply(chatId, messages.forClient(linked.locale() != null ? linked.locale() : language, "client.telegram.contactMismatch"), removeKeyboard);
            case NONE -> log.debug("Telegram contact ignored: no started link for this chat");
        }
    }

    /** Telegram sends the number without a plus sign. */
    private boolean samePhone(String telegramPhone, String clientPhone, String region) {
        if (clientPhone == null || telegramPhone.isBlank()) {
            return false;
        }
        try {
            return clientPhone.equals(phones.toE164(telegramPhone.startsWith("+") ? telegramPhone : "+" + telegramPhone, region));
        } catch (DomainException ex) {
            return false;
        }
    }

    private void reply(String chatId, String text, Map<String, Object> replyMarkup) {
        TelegramBotClient.Result result = bot.sendMessage(chatId, text, replyMarkup);
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

package com.cadence.notification.internal;

import com.cadence.crm.api.CrmService;
import com.cadence.notification.api.TelegramLink;
import com.cadence.notification.api.TelegramLinkService;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.error.DomainException;
import com.cadence.platform.tenancy.ActorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
class TelegramLinkServiceImpl implements TelegramLinkService {

    private static final Logger log = LoggerFactory.getLogger(TelegramLinkServiceImpl.class);

    private final TelegramProperties props;
    private final LinkTokenStore tokens;
    private final CrmService crm;
    private final AuditLogger audit;
    private final Clock clock;

    TelegramLinkServiceImpl(TelegramProperties props, LinkTokenStore tokens, CrmService crm, AuditLogger audit, Clock clock) {
        this.props = props;
        this.tokens = tokens;
        this.crm = crm;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public boolean enabled() {
        return props.enabled();
    }

    @Override
    @Transactional
    public TelegramLink issue(UUID clientId) {
        if (!props.enabled()) {
            throw DomainException.conflict("TELEGRAM_DISABLED", "The Telegram channel is not switched on");
        }
        crm.requireActive(clientId);
        String raw = LinkTokenStore.newRaw();
        Instant expiresAt = clock.instant().plus(props.linkTtl());
        tokens.insert(clientId, LinkTokenStore.hash(raw), expiresAt);
        // The audit record carries no token: only that a link was issued and when it expires.
        audit.record(
                ActorContext.current().map(ActorContext.Actor::actorId).orElse("public"),
                ActorContext.current().map(a -> a.role().name()).orElse("PUBLIC"),
                "client.telegram.link_issue",
                "client",
                clientId,
                null,
                "{\"expiresAt\":\"" + expiresAt + "\"}",
                "telegram-link",
                null
        );
        log.info("Telegram link issued: client={}, expiresAt={}", clientId, expiresAt);
        return new TelegramLink("https://t.me/" + props.botUsername() + "?start=" + raw, expiresAt);
    }
}

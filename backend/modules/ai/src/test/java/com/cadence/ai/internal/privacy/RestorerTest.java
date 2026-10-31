package com.cadence.ai.internal.privacy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class RestorerTest {

    private final UUID convo = UUID.randomUUID();
    private PseudonymRegistry registry;
    private Restorer restorer;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        registry = new PseudonymRegistry(new InMemoryPseudonymStore());
        restorer = new Restorer(registry);
        logger = (Logger) LoggerFactory.getLogger(Restorer.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void restoresEveryKindFromTheConversationMap() {
        registry.refFor(convo, RefKind.CLIENT, UUID.randomUUID(), "Anna Ivanova");
        registry.refFor(convo, RefKind.MASTER, UUID.randomUUID(), "Nino Beridze");
        registry.refFor(convo, RefKind.SERVICE, UUID.randomUUID(), "Haircut");

        assertEquals("Anna Ivanova booked Haircut with Nino Beridze",
                restorer.restore(convo, "CLIENT_1 booked SERVICE_1 with MASTER_1"));
    }

    @Test
    void tokensInsidePunctuationAreRestored() {
        registry.refFor(convo, RefKind.CLIENT, UUID.randomUUID(), "Anna");
        registry.refFor(convo, RefKind.CLIENT, UUID.randomUUID(), "Maria");

        assertEquals("(Anna|Maria), «Anna», Anna's slot: Maria.",
                restorer.restore(convo, "(CLIENT_1|CLIENT_2), «CLIENT_1», CLIENT_1's slot: CLIENT_2."));
    }

    @Test
    void clientOneAndClientTenAreDifferentTokens() {
        for (int i = 1; i <= 10; i++) {
            registry.refFor(convo, RefKind.CLIENT, UUID.randomUUID(), "Client number " + i);
        }
        assertEquals("Client number 10 and Client number 1",
                restorer.restore(convo, "CLIENT_10 and CLIENT_1"));
    }

    @Test
    void displaysAreNotReinterpretedAsTokens() {
        registry.refFor(convo, RefKind.CLIENT, UUID.randomUUID(), "MASTER_1 fan");
        registry.refFor(convo, RefKind.MASTER, UUID.randomUUID(), "Nino");

        assertEquals("MASTER_1 fan likes Nino", restorer.restore(convo, "CLIENT_1 likes MASTER_1"));
    }

    @Test
    void unknownTokensStayAndAreLoggedWithoutContent() {
        registry.refFor(convo, RefKind.CLIENT, UUID.randomUUID(), "Anna");

        String restored = restorer.restore(convo, "Secret plan for CLIENT_1 and CLIENT_7, see VISIT_3");

        assertEquals("Secret plan for Anna and CLIENT_7, see VISIT_3", restored);
        assertEquals(1, logs.list.size());
        ILoggingEvent event = logs.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        String line = event.getFormattedMessage();
        assertFalse(line.contains("Secret") || line.contains("Anna"), line);
    }

    @Test
    void lookAlikesAreLeftAlone() {
        registry.refFor(convo, RefKind.CLIENT, UUID.randomUUID(), "Anna");

        String text = "XCLIENT_1, CLIENT_1x, client_1, CLIENT_01, CLIENT_";
        assertEquals(text, restorer.restore(convo, text));
        assertEquals(0, logs.list.size(), "look-alikes are not tokens, so nothing is unknown");
    }

    @Test
    void nullAndTokenFreeTextsPassThrough() {
        assertNull(restorer.restore(convo, null));
        assertEquals("Nothing to restore", restorer.restore(convo, "Nothing to restore"));
    }
}

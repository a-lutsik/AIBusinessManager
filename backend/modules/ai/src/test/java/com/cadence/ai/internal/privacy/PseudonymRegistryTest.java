package com.cadence.ai.internal.privacy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PseudonymRegistryTest {

    private InMemoryPseudonymStore store;
    private PseudonymRegistry registry;
    private final UUID convo = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        store = new InMemoryPseudonymStore();
        registry = new PseudonymRegistry(store);
    }

    @Test
    void sameEntityKeepsItsTokenAndFirstDisplay() {
        UUID anna = UUID.randomUUID();
        String first = registry.refFor(convo, RefKind.CLIENT, anna, "Anna Ivanova");
        String again = registry.refFor(convo, RefKind.CLIENT, anna, "Anna I.");

        assertEquals("CLIENT_1", first);
        assertEquals(first, again);
        assertEquals(Optional.of("Anna Ivanova"), registry.displayOf(convo, first));
    }

    @Test
    void numbersPerKindInOrderOfFirstAppearance() {
        assertEquals("CLIENT_1", registry.refFor(convo, RefKind.CLIENT, UUID.randomUUID(), "A"));
        assertEquals("MASTER_1", registry.refFor(convo, RefKind.MASTER, UUID.randomUUID(), "M"));
        assertEquals("CLIENT_2", registry.refFor(convo, RefKind.CLIENT, UUID.randomUUID(), "B"));
        assertEquals("VISIT_1", registry.refFor(convo, RefKind.VISIT, UUID.randomUUID(), "visit"));
        assertEquals("SERVICE_1", registry.refFor(convo, RefKind.SERVICE, UUID.randomUUID(), "Haircut"));
        assertEquals("DRAFT_1", registry.refFor(convo, RefKind.DRAFT, UUID.randomUUID(), "draft"));
    }

    @Test
    void conversationsNumberIndependently() {
        UUID other = UUID.randomUUID();
        UUID anna = UUID.randomUUID();
        registry.refFor(convo, RefKind.CLIENT, UUID.randomUUID(), "Someone");

        assertEquals("CLIENT_2", registry.refFor(convo, RefKind.CLIENT, anna, "Anna"));
        assertEquals("CLIENT_1", registry.refFor(other, RefKind.CLIENT, anna, "Anna"));
        assertEquals(Optional.empty(), registry.resolve(other, "CLIENT_2", RefKind.CLIENT));
    }

    @Test
    void namesWithEqualHashCodesNeverCollide() {
        assertEquals("Aa".hashCode(), "BB".hashCode(), "fixture precondition");
        UUID aa = UUID.randomUUID();
        UUID bb = UUID.randomUUID();

        String tokenAa = registry.refFor(convo, RefKind.CLIENT, aa, "Aa");
        String tokenBb = registry.refFor(convo, RefKind.CLIENT, bb, "BB");

        assertNotEquals(tokenAa, tokenBb);
        assertEquals(Optional.of(aa), registry.resolve(convo, tokenAa, RefKind.CLIENT));
        assertEquals(Optional.of(bb), registry.resolve(convo, tokenBb, RefKind.CLIENT));
        assertEquals(Map.of(tokenAa, "Aa", tokenBb, "BB"), registry.displays(convo));
    }

    @Test
    void aNewRegistryOverTheSameStoreResolvesEarlierTokens() {
        UUID anna = UUID.randomUUID();
        String token = registry.refFor(convo, RefKind.CLIENT, anna, "Anna");

        PseudonymRegistry afterRestart = new PseudonymRegistry(store);

        assertEquals(Optional.of(anna), afterRestart.resolve(convo, token, RefKind.CLIENT));
        assertEquals(Optional.of("Anna"), afterRestart.displayOf(convo, token));
        assertEquals(token, afterRestart.refFor(convo, RefKind.CLIENT, anna, "Anna"));
    }

    @Test
    void wrongKindUnknownOrMalformedTokensResolveToNothing() {
        UUID anna = UUID.randomUUID();
        String token = registry.refFor(convo, RefKind.CLIENT, anna, "Anna");

        assertEquals(Optional.empty(), registry.resolve(convo, token, RefKind.MASTER));
        assertEquals(Optional.empty(), registry.resolve(convo, "CLIENT_9", RefKind.CLIENT));
        int before = store.resolveCalls;
        for (String bad : new String[] {null, "", "client_1", "CLIENT_01", "CLIENT_", "CLIENT_1 ", anna.toString()}) {
            assertEquals(Optional.empty(), registry.resolve(convo, bad, RefKind.CLIENT), String.valueOf(bad));
        }
        assertEquals(before, store.resolveCalls, "malformed tokens never reach the store");
    }

    @Test
    void nameIndexHoldsOnlyClientsAndMasters() {
        registry.refFor(convo, RefKind.CLIENT, UUID.randomUUID(), "Anna Ivanova");
        registry.refFor(convo, RefKind.MASTER, UUID.randomUUID(), "Nino Beridze");
        registry.refFor(convo, RefKind.SERVICE, UUID.randomUUID(), "Haircut");

        String masked = registry.nameIndex(convo).apply("Anna Ivanova booked Haircut with Nino Beridze");

        assertEquals("CLIENT_1 booked Haircut with MASTER_1", masked);
    }
}

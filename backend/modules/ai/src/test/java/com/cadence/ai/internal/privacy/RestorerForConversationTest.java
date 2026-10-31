package com.cadence.ai.internal.privacy;

import com.cadence.platform.ai.AiPseudonymStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestorerForConversationTest {

    private final AiPseudonymStore store = mock(AiPseudonymStore.class);
    private final UUID conversation = UUID.randomUUID();
    private final UnaryOperator<String> restore = new Restorer(new PseudonymRegistry(store)).forConversation(conversation);

    @Test
    void manyTextsReadTheMapOnce() {
        when(store.all(conversation)).thenReturn(List.of(
                new AiPseudonymStore.Mapping(AiPseudonymStore.Kind.CLIENT, 1, "CLIENT_1", UUID.randomUUID(), "Anna")));

        assertEquals("Anna came", restore.apply("CLIENT_1 came"));
        assertEquals("with Anna and CLIENT_2", restore.apply("with CLIENT_1 and CLIENT_2"));

        verify(store, times(1)).all(conversation);
    }

    @Test
    void textsWithoutTokensReadNothing() {
        assertEquals("plain", restore.apply("plain"));
        assertNull(restore.apply(null));

        verify(store, never()).all(conversation);
    }
}

package com.cadence.ai.internal.drafts;

import com.cadence.ai.internal.privacy.InMemoryPseudonymStore;
import com.cadence.booking.api.AppointmentStatus;
import com.cadence.platform.ai.AiConversationStore;
import com.cadence.platform.ai.AiDraftStore;
import com.cadence.platform.ai.AiPseudonymStore;
import com.cadence.platform.audit.AuditLogger;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientDataScrubberTest {

    private static final UUID CLIENT = UUID.randomUUID();

    private final AiConversationStore conversations = mock(AiConversationStore.class);

    private static String cancelJson(UUID client) {
        return DraftPayloads.write(new DraftPayloads.Cancel(1, DraftPayloads.CANCEL, client, UUID.randomUUID(),
                AppointmentStatus.CANCELLED_BY_CLIENT, "Maria asked",
                new DraftPayloads.Expected("2026-11-03T06:00:00Z", AppointmentStatus.CONFIRMED),
                new DraftPayloads.CancelSummary("Maria Sokolova", "Nino", "Cut", "2026-11-03T10:00")));
    }

    @Test
    void forAuditKeepsIdsAndTimesButNoNameOrReason() {
        String audit = DraftPayloads.forAudit(cancelJson(CLIENT));

        assertFalse(audit.contains("Maria"), audit);
        assertFalse(audit.contains("Nino"), audit);
        assertFalse(audit.contains("reason"), audit);
        assertFalse(audit.contains("summary"), audit);
        assertTrue(audit.contains(CLIENT.toString()), audit);
        assertTrue(audit.contains("2026-11-03T06:00:00Z"), audit);
        assertEquals("{}", DraftPayloads.forAudit("not json"));
    }

    @Test
    void scrubbedPayloadStillParsesWithoutTheNameOrReason() {
        DraftPayloads.Cancel parsed = (DraftPayloads.Cancel) DraftPayloads.parse(DraftPayloads.CANCEL,
                DraftPayloads.scrubbed(cancelJson(CLIENT)));

        assertEquals(DraftPayloads.ANONYMIZED, parsed.summary().clientName());
        assertNull(parsed.reason());
        assertEquals("Nino", parsed.summary().specialistName());
    }

    @Test
    void theNameLeavesDraftsAndRefsAndOnlyPendingDraftsFail() {
        AiDraftStore drafts = mock(AiDraftStore.class);
        AuditLogger audit = mock(AuditLogger.class);
        InMemoryPseudonymStore pseudonyms = new InMemoryPseudonymStore();
        UUID conversation = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        pseudonyms.refFor(conversation, AiPseudonymStore.Kind.CLIENT, CLIENT, "Maria Sokolova");
        pseudonyms.refFor(conversation, AiPseudonymStore.Kind.CLIENT, other, "Olga Keeps");
        UUID pending = UUID.randomUUID();
        UUID confirmed = UUID.randomUUID();
        when(drafts.lockByClient(CLIENT)).thenReturn(List.of(record(pending), record(confirmed)));
        when(drafts.markFailed(pending, "DRAFT_STALE", "The client was anonymized")).thenReturn(true);

        new ClientDataScrubber(conversations, drafts, pseudonyms, audit).clientRetiring(CLIENT);
        verifyNoInteractions(conversations);

        verify(drafts).replacePayload(eq(pending), argThat(json -> !json.contains("Maria")));
        verify(drafts).replacePayload(eq(confirmed), argThat(json -> !json.contains("Maria")));
        verify(audit).record(any(), any(), eq("ai.draft.fail"), eq("ai_draft"), eq(pending), any(), any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), eq(confirmed), any(), any(), any(), any());
        assertEquals(DraftPayloads.ANONYMIZED, pseudonyms.displayOf(conversation, "CLIENT_1").orElseThrow());
        assertEquals("Olga Keeps", pseudonyms.displayOf(conversation, "CLIENT_2").orElseThrow());
    }

    private static AiDraftStore.DraftRecord record(UUID id) {
        return new AiDraftStore.DraftRecord(id, UUID.randomUUID(), null, DraftPayloads.CANCEL, cancelJson(CLIENT),
                AiDraftStore.Status.PENDING, Instant.now(), Instant.now(), null, null, null, null, null);
    }
}

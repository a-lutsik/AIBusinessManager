package com.cadence.crm.internal;

import com.cadence.crm.api.ClientView;
import com.cadence.platform.error.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrmServiceAbsorbTest {

    private static final UUID SURVIVOR = UUID.randomUUID();
    private static final UUID ABSORBED = UUID.randomUUID();

    private ClientRepository repository;
    private CrmServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(ClientRepository.class);
        service = new CrmServiceImpl(repository, mock(PlatformTransactionManager.class));
    }

    private static ClientView client(
            UUID id, String name, String notes, String tags, boolean consent,
            Instant first, Instant last, int completed, int noShow, int lateCancel, int value
    ) {
        return new ClientView(id, "+99555" + id.toString().substring(0, 6), name, notes, tags, "en", null, consent,
                first, last, completed, noShow, lateCancel, value);
    }

    private void state(UUID id, ClientView view, Instant anonymizedAt, UUID mergedInto) {
        when(repository.findState(id)).thenReturn(Optional.of(new ClientRepository.ClientState(view, anonymizedAt, mergedInto)));
    }

    @Test
    void sumsCountersAndTakesEarliestFirstAndLatestLastVisit() {
        Instant jan = Instant.parse("2026-01-10T10:00:00Z");
        Instant mar = Instant.parse("2026-03-10T10:00:00Z");
        Instant jun = Instant.parse("2026-06-10T10:00:00Z");
        Instant sep = Instant.parse("2026-09-10T10:00:00Z");
        state(SURVIVOR, client(SURVIVOR, "Mariam", null, null, false, mar, jun, 3, 1, 2, 24000), null, null);
        state(ABSORBED, client(ABSORBED, "Mariam K.", null, null, true, jan, sep, 4, 0, 1, 30000), null, null);
        ClientView merged = client(SURVIVOR, "Mariam", null, null, false, jan, sep, 7, 1, 3, 54000);
        when(repository.findById(SURVIVOR)).thenReturn(Optional.of(merged));

        ClientView result = service.absorb(SURVIVOR, ABSORBED);

        assertThat(result).isSameAs(merged);
        var order = inOrder(repository);
        order.verify(repository).applyMerge(SURVIVOR, 7, 1, 3, 54000, jan, sep, null, null);
        order.verify(repository).markMerged(ABSORBED, SURVIVOR);
    }

    @Test
    void nullVisitDatesAreHandledOnEitherSide() {
        Instant jun = Instant.parse("2026-06-10T10:00:00Z");
        state(SURVIVOR, client(SURVIVOR, "A", null, null, false, null, null, 0, 0, 0, 0), null, null);
        state(ABSORBED, client(ABSORBED, "B", null, null, false, jun, jun, 1, 0, 0, 100), null, null);
        when(repository.findById(SURVIVOR)).thenReturn(Optional.of(client(SURVIVOR, "A", null, null, false, jun, jun, 1, 0, 0, 100)));

        service.absorb(SURVIVOR, ABSORBED);

        verify(repository).applyMerge(SURVIVOR, 1, 0, 0, 100, jun, jun, null, null);
    }

    @Test
    void bothVisitDatesNullStayNull() {
        state(SURVIVOR, client(SURVIVOR, "A", null, null, false, null, null, 0, 0, 0, 0), null, null);
        state(ABSORBED, client(ABSORBED, "B", null, null, false, null, null, 0, 2, 0, 0), null, null);
        when(repository.findById(SURVIVOR)).thenReturn(Optional.of(client(SURVIVOR, "A", null, null, false, null, null, 0, 2, 0, 0)));

        service.absorb(SURVIVOR, ABSORBED);

        verify(repository).applyMerge(SURVIVOR, 0, 2, 0, 0, null, null, null, null);
    }

    @Test
    void notesAreAppendedUnderSeparatorNamingTheAbsorbedClient() {
        state(SURVIVOR, client(SURVIVOR, "A", "Prefers mornings", null, false, null, null, 0, 0, 0, 0), null, null);
        state(ABSORBED, client(ABSORBED, "Mariam K.", "Allergic to ammonia", null, false, null, null, 0, 0, 0, 0), null, null);
        when(repository.findById(SURVIVOR)).thenReturn(Optional.of(
                client(SURVIVOR, "A", "x", null, false, null, null, 0, 0, 0, 0)));

        service.absorb(SURVIVOR, ABSORBED);

        verify(repository).applyMerge(SURVIVOR, 0, 0, 0, 0, null, null,
                "Prefers mornings\n--- merged from Mariam K. ---\nAllergic to ammonia", null);
    }

    @Test
    void survivorConsentIsNeverTouched() {
        state(SURVIVOR, client(SURVIVOR, "A", null, null, false, null, null, 0, 0, 0, 0), null, null);
        state(ABSORBED, client(ABSORBED, "B", null, null, true, null, null, 0, 0, 0, 0), null, null);
        when(repository.findById(SURVIVOR)).thenReturn(Optional.of(client(SURVIVOR, "A", null, null, false, null, null, 0, 0, 0, 0)));

        ClientView result = service.absorb(SURVIVOR, ABSORBED);

        assertThat(result.marketingConsent()).isFalse();
        verify(repository, never()).updateProfile(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void mergeNotesKeepsSurvivorNotesWhenAbsorbedHasNone() {
        assertThat(CrmServiceImpl.mergeNotes("keep", null, "X")).isEqualTo("keep");
        assertThat(CrmServiceImpl.mergeNotes("keep", "  ", "X")).isEqualTo("keep");
        assertThat(CrmServiceImpl.mergeNotes(null, null, "X")).isNull();
    }

    @Test
    void mergeNotesWithoutSurvivorNotesStartsWithTheSeparator() {
        assertThat(CrmServiceImpl.mergeNotes(null, "note", "X")).isEqualTo("--- merged from X ---\nnote");
    }

    @Test
    void mergeNotesIsCappedToTheColumnLength() {
        String merged = CrmServiceImpl.mergeNotes("a".repeat(4000), "b".repeat(500), "X");
        assertThat(merged).hasSize(4096).startsWith("aaaa");
    }

    @Test
    void mergeTagsIsAnOrderPreservingCaseInsensitiveUnion() {
        assertThat(CrmServiceImpl.mergeTags("vip, regular", "VIP,new ,, referral")).isEqualTo("vip,regular,new,referral");
        assertThat(CrmServiceImpl.mergeTags(null, "a")).isEqualTo("a");
        assertThat(CrmServiceImpl.mergeTags("a", null)).isEqualTo("a");
        assertThat(CrmServiceImpl.mergeTags(null, null)).isNull();
    }

    @Test
    void selfMergeIsRejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.absorb(SURVIVOR, SURVIVOR))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(400);
                    assertThat(ex.code()).isEqualTo("INVALID_MERGE");
                });
        verify(repository, never()).findState(any());
    }

    @Test
    void missingSurvivorOrAbsorbedIs404() {
        state(SURVIVOR, client(SURVIVOR, "A", null, null, false, null, null, 0, 0, 0, 0), null, null);
        when(repository.findState(ABSORBED)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.absorb(SURVIVOR, ABSORBED))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(404);
                    assertThat(ex.code()).isEqualTo("CLIENT_NOT_FOUND");
                });
        assertThatThrownBy(() -> service.absorb(ABSORBED, SURVIVOR))
                .isInstanceOfSatisfying(DomainException.class, ex -> assertThat(ex.code()).isEqualTo("CLIENT_NOT_FOUND"));
        verify(repository, never()).applyMerge(any(), anyInt(), anyInt(), anyInt(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void alreadyAnonymizedOrMergedAbsorbedIs409() {
        ClientView plain = client(SURVIVOR, "A", null, null, false, null, null, 0, 0, 0, 0);
        state(SURVIVOR, plain, null, null);
        state(ABSORBED, plain, Instant.parse("2026-01-01T00:00:00Z"), SURVIVOR);

        assertThatThrownBy(() -> service.absorb(SURVIVOR, ABSORBED))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.code()).isEqualTo("CLIENT_ALREADY_MERGED");
                });
        verify(repository, never()).markMerged(any(), any());
    }

    @Test
    void anonymizedWithoutMergePointerIsAlsoAlreadyMerged() {
        ClientView plain = client(SURVIVOR, "A", null, null, false, null, null, 0, 0, 0, 0);
        state(SURVIVOR, plain, null, null);
        state(ABSORBED, plain, Instant.parse("2026-01-01T00:00:00Z"), null);

        assertThatThrownBy(() -> service.absorb(SURVIVOR, ABSORBED))
                .isInstanceOfSatisfying(DomainException.class, ex -> assertThat(ex.code()).isEqualTo("CLIENT_ALREADY_MERGED"));
    }

    @Test
    void retiredSurvivorIsRejectedToo() {
        ClientView plain = client(SURVIVOR, "A", null, null, false, null, null, 0, 0, 0, 0);
        state(SURVIVOR, plain, Instant.parse("2026-01-01T00:00:00Z"), UUID.randomUUID());
        state(ABSORBED, plain, null, null);

        assertThatThrownBy(() -> service.absorb(SURVIVOR, ABSORBED))
                .isInstanceOfSatisfying(DomainException.class, ex -> assertThat(ex.status()).isEqualTo(409));
        verify(repository, never()).markMerged(any(), any());
    }

    @Test
    void absorbLocksBothProfilesBeforeReadingTheirState() {
        ClientView plain = client(SURVIVOR, "A", null, null, false, null, null, 0, 0, 0, 0);
        state(SURVIVOR, plain, null, null);
        state(ABSORBED, plain, null, null);
        when(repository.findById(SURVIVOR)).thenReturn(Optional.of(plain));

        service.absorb(SURVIVOR, ABSORBED);

        var order = inOrder(repository);
        order.verify(repository).lockForUpdate(SURVIVOR, ABSORBED);
        order.verify(repository).findState(SURVIVOR);
        order.verify(repository).applyMerge(any(), anyInt(), anyInt(), anyInt(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void lockForMergeLocksThenRejectsRetiredOrMissingProfilesWithoutWriting() {
        ClientView plain = client(SURVIVOR, "A", null, null, false, null, null, 0, 0, 0, 0);
        state(SURVIVOR, plain, null, null);
        state(ABSORBED, plain, Instant.parse("2026-01-01T00:00:00Z"), SURVIVOR);

        assertThatThrownBy(() -> service.lockForMerge(SURVIVOR, ABSORBED))
                .isInstanceOfSatisfying(DomainException.class, ex -> assertThat(ex.code()).isEqualTo("CLIENT_ALREADY_MERGED"));
        verify(repository).lockForUpdate(SURVIVOR, ABSORBED);

        when(repository.findState(ABSORBED)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.lockForMerge(SURVIVOR, ABSORBED))
                .isInstanceOfSatisfying(DomainException.class, ex -> assertThat(ex.status()).isEqualTo(404));
        assertThatThrownBy(() -> service.lockForMerge(SURVIVOR, SURVIVOR))
                .isInstanceOfSatisfying(DomainException.class, ex -> assertThat(ex.status()).isEqualTo(400));
        verify(repository, never()).applyMerge(any(), anyInt(), anyInt(), anyInt(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void revertMethodsDelegateToTheRepository() {
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        Instant last = Instant.parse("2026-02-01T00:00:00Z");

        service.revertNoShow(SURVIVOR);
        service.revertCompletedVisit(SURVIVOR, 7500, first, last);

        verify(repository).revertNoShow(SURVIVOR);
        verify(repository).revertCompleted(SURVIVOR, 7500, first, last);
    }
}

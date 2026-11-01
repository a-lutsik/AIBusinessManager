package com.cadence.crm.internal;

import com.cadence.crm.api.ClientRetirementListener;
import com.cadence.crm.api.ClientView;
import com.cadence.platform.audit.AuditLogger;
import com.cadence.platform.error.DomainException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Modules are told before the client row is written or locked: their locks come first, see {@code DraftApplier}. */
class CrmServiceRetirementTest {

    private final ClientRepository repository = mock(ClientRepository.class);
    private final ClientRetirementListener listener = mock(ClientRetirementListener.class);
    private final CrmServiceImpl service = new CrmServiceImpl(
            repository, mock(PlatformTransactionManager.class), mock(AuditLogger.class), List.of(listener));
    private final UUID absorbed = UUID.randomUUID();
    private final UUID survivor = UUID.randomUUID();

    @Test
    void anonymizeTellsListenersFirst() {
        when(repository.findState(absorbed)).thenReturn(Optional.of(new ClientRepository.ClientState(mock(ClientView.class), null, null)));
        when(repository.findById(absorbed)).thenReturn(Optional.of(mock(ClientView.class)));

        service.anonymize(absorbed);

        InOrder order = inOrder(listener, repository);
        order.verify(listener).clientErasing(absorbed);
        order.verify(repository).lockForUpdate(absorbed, absorbed);
        order.verify(repository).anonymize(absorbed);
    }

    /** A into B into C, and a damaged row that points C's chain back at the start: everyone once, and it ends. */
    @Test
    void anonymizeErasesAlongTheMergeChainWithoutLoopingOnACycle() {
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(repository.absorbedBy(absorbed)).thenReturn(List.of(b));
        when(repository.absorbedBy(b)).thenReturn(List.of(c));
        when(repository.absorbedBy(c)).thenReturn(List.of(absorbed));
        when(repository.findState(absorbed)).thenReturn(Optional.of(new ClientRepository.ClientState(mock(ClientView.class), null, null)));
        when(repository.findById(absorbed)).thenReturn(Optional.of(mock(ClientView.class)));

        service.anonymize(absorbed);

        for (UUID id : List.of(absorbed, b, c)) {
            verify(listener).clientErasing(id);
        }
    }

    /** A merge committed between the erasure of the dialogues and the row lock: the absorbed one was missed, so all of it rolls back. */
    @Test
    void anonymizeRefusesWhenAMergeFinishedWhileItWaitedForTheLock() {
        UUID b = UUID.randomUUID();
        when(repository.absorbedBy(absorbed)).thenReturn(List.of(), List.of(b));

        assertThatThrownBy(() -> service.anonymize(absorbed))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("CONCURRENT_RETRY"));

        verify(repository, never()).anonymize(absorbed);
    }

    @Test
    void anonymizeRefusesARetiredClientOnTheLockedRowAndWritesNothing() {
        ClientRepository.ClientState merged = new ClientRepository.ClientState(mock(ClientView.class), null, survivor);
        when(repository.findState(absorbed)).thenReturn(Optional.of(merged));

        assertThatThrownBy(() -> service.anonymize(absorbed))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("CLIENT_ALREADY_ANONYMIZED"));

        verify(repository, never()).anonymize(absorbed);
    }

    @Test
    void mergeTellsListenersAboutTheAbsorbedClientBeforeLocking() {
        ClientRepository.ClientState active = new ClientRepository.ClientState(mock(ClientView.class), null, null);
        when(repository.findState(any())).thenReturn(Optional.of(active));

        service.lockForMerge(survivor, absorbed);

        InOrder order = inOrder(listener, repository);
        order.verify(listener).clientRetiring(absorbed);
        order.verify(repository).lockForUpdate(survivor, absorbed);
        verify(listener, never()).clientErasing(any());
    }
}

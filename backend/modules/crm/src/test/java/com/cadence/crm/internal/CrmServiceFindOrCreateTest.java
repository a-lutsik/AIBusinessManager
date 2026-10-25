package com.cadence.crm.internal;

import com.cadence.crm.api.ClientView;
import com.cadence.platform.error.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrmServiceFindOrCreateTest {

    private static final String PHONE = "+995555000111";

    /** Records how the service uses transactions: propagation per begin, and the final outcome of each. */
    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        final List<Integer> propagations = new ArrayList<>();
        final List<String> outcomes = new ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            propagations.add(definition.getPropagationBehavior());
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
            outcomes.add("commit");
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
            outcomes.add("rollback");
        }
    }

    private ClientRepository repository;
    private RecordingTransactionManager transactions;
    private CrmServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(ClientRepository.class);
        transactions = new RecordingTransactionManager();
        service = new CrmServiceImpl(repository, transactions, mock(com.cadence.platform.audit.AuditLogger.class));
    }

    private static ClientView client(UUID id, String name) {
        return new ClientView(id, PHONE, name, null, null, "en", null, false, null, null, 0, 0, 0, 0);
    }

    private static DuplicateKeyException phoneCollision() {
        return new DuplicateKeyException("jOOQ; SQL [insert into client ...]", new SQLException(
                "ERROR: duplicate key value violates unique constraint \"client_phone_uq\"", "23505"));
    }

    @Test
    void insertRunsInASavepointAndCreatesNewClient() {
        when(repository.findByPhone(PHONE)).thenReturn(Optional.empty());
        UUID[] createdId = new UUID[1];
        when(repository.insert(any())).thenAnswer(inv -> {
            createdId[0] = ((ClientView) inv.getArgument(0)).id();
            return inv.getArgument(0);
        });
        when(repository.findById(any())).thenAnswer(inv -> Optional.of(client(inv.getArgument(0), "Mariam")));

        ClientView result = service.findOrCreate(PHONE, "Mariam", "en", true, "v1");

        assertThat(result.id()).isEqualTo(createdId[0]);
        assertThat(transactions.propagations).containsExactly(TransactionDefinition.PROPAGATION_NESTED);
        assertThat(transactions.outcomes).containsExactly("commit");
    }

    @Test
    void phoneCollisionRollsBackToSavepointAndReusesTheWinner() {
        UUID winnerId = UUID.randomUUID();
        ClientView winner = client(winnerId, "Mariam K.");
        when(repository.findByPhone(PHONE)).thenReturn(Optional.empty(), Optional.of(winner));
        doThrow(phoneCollision()).when(repository).insert(any());
        when(repository.findById(winnerId)).thenReturn(Optional.of(winner));

        ClientView result = service.findOrCreate(PHONE, "Mariam", "ka", true, "v1");

        assertThat(result).isSameAs(winner);
        assertThat(transactions.propagations).containsExactly(TransactionDefinition.PROPAGATION_NESTED);
        assertThat(transactions.outcomes).containsExactly("rollback");
        // the winner keeps its name and its locale ("en"); consent is only added, as for any returning client
        verify(repository).updateProfile(winnerId, "Mariam K.", null, null, null, true, "v1");
    }

    @Test
    void otherIntegrityViolationsPropagate() {
        when(repository.findByPhone(PHONE)).thenReturn(Optional.empty());
        var other = new DataIntegrityViolationException("jOOQ", new SQLException(
                "ERROR: null value in column \"display_name\" violates not-null constraint", "23502"));
        doThrow(other).when(repository).insert(any());

        assertThatThrownBy(() -> service.findOrCreate(PHONE, null, "en", false, null)).isSameAs(other);
        verify(repository, never()).updateProfile(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void uniqueViolationOfAnotherConstraintPropagates() {
        when(repository.findByPhone(PHONE)).thenReturn(Optional.empty());
        var other = new DuplicateKeyException("jOOQ", new SQLException(
                "ERROR: duplicate key value violates unique constraint \"client_pkey\"", "23505"));
        doThrow(other).when(repository).insert(any());

        assertThatThrownBy(() -> service.findOrCreate(PHONE, "X", "en", false, null)).isSameAs(other);
    }

    @Test
    void vanishedWinnerIsReportedAsConflictNotServerError() {
        when(repository.findByPhone(PHONE)).thenReturn(Optional.empty(), Optional.empty());
        doThrow(phoneCollision()).when(repository).insert(any());

        assertThatThrownBy(() -> service.findOrCreate(PHONE, "X", "en", false, null))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void existingClientIsRefreshedWithoutInsert() {
        UUID id = UUID.randomUUID();
        ClientView existing = client(id, "Mariam");
        when(repository.findByPhone(PHONE)).thenReturn(Optional.of(existing));
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        ClientView result = service.findOrCreate(PHONE, "Other name", "en", true, "v1");

        assertThat(result).isSameAs(existing);
        verify(repository, never()).insert(any());
        // name and locale stay; consent is added with the new version (decision D)
        verify(repository).updateProfile(eq(id), eq("Mariam"), isNull(), isNull(), isNull(), eq(true), eq("v1"));
        assertThat(transactions.propagations).isEmpty();
    }
}

package com.cadence.platform.outbox;

import com.cadence.platform.jooq.tables.records.OutboxEventRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.cadence.platform.jooq.Tables.OUTBOX_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * A failure of one event, whatever its type, is recorded against that event and the next events of the batch are still
 * delivered; only a {@link VirtualMachineError} aborts the poll. The database is a jOOQ mock connection: the claim
 * returns canned rows and every other statement is recorded.
 */
class OutboxPollerFailureIsolationTest {

    private static final UUID TENANT = UUID.randomUUID();

    private final List<String> statements = new ArrayList<>();
    private final List<String> handled = new ArrayList<>();

    private static OutboxEventRecord event(String type) {
        OutboxEventRecord r = new OutboxEventRecord();
        r.setId(UUID.randomUUID());
        r.setTenantId(TENANT);
        r.setType(type);
        r.setAggregateType("appointment");
        r.setAggregateId(UUID.randomUUID());
        r.setPayload("{}");
        r.setCreatedAt(LocalDateTime.now());
        r.setAttempts(0);
        return r;
    }

    private OutboxPoller poller(List<OutboxEventRecord> claimed, OutboxHandler... handlers) {
        DSLContext create = DSL.using(SQLDialect.POSTGRES);
        DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            if (ctx.sql().contains("outbox_claim")) {
                Result<OutboxEventRecord> rows = create.newResult(OUTBOX_EVENT);
                rows.addAll(claimed);
                return new MockResult[]{new MockResult(rows.size(), rows)};
            }
            statements.add(ctx.sql());
            return new MockResult[]{new MockResult(1)};
        }), SQLDialect.POSTGRES);
        @SuppressWarnings("unchecked")
        ObjectProvider<OutboxHandler> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.stream()).thenAnswer(inv -> java.util.stream.Stream.of(handlers));
        return new OutboxPoller(dsl, provider, mock(PlatformTransactionManager.class), 10);
    }

    private OutboxHandler handler(String type, Runnable action) {
        return new OutboxHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void handle(Record event) {
                handled.add(type);
                action.run();
            }
        };
    }

    @Test
    void anErrorFromOneHandlerIsRecordedAndTheNextEventIsStillDelivered() {
        OutboxHandler broken = handler("BROKEN", () -> {
            throw new NoClassDefFoundError("boom");
        });
        OutboxHandler fine = handler("FINE", () -> { });

        poller(List.of(event("BROKEN"), event("FINE")), broken, fine).poll();

        assertThat(handled).containsExactly("BROKEN", "FINE");
        // BROKEN: attempts + last_error update; FINE: processed_at update.
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("attempts").contains("last_error");
        assertThat(statements.get(1)).contains("processed_at");
    }

    @Test
    void aRuntimeExceptionIsStillRecordedAndIsolated() {
        OutboxHandler broken = handler("BROKEN", () -> {
            throw new IllegalStateException("bad");
        });
        OutboxHandler fine = handler("FINE", () -> { });

        poller(List.of(event("BROKEN"), event("FINE")), broken, fine).poll();

        assertThat(handled).containsExactly("BROKEN", "FINE");
        assertThat(statements.get(0)).contains("attempts");
    }

    @Test
    void aVirtualMachineErrorAbortsThePollInsteadOfBeingSwallowed() {
        OutboxHandler oom = handler("OOM", () -> {
            throw new OutOfMemoryError("heap");
        });
        OutboxHandler fine = handler("FINE", () -> { });

        assertThatThrownBy(() -> poller(List.of(event("OOM"), event("FINE")), oom, fine).poll())
                .isInstanceOf(OutOfMemoryError.class);

        assertThat(handled).containsExactly("OOM");
        assertThat(statements).isEmpty();
    }
}

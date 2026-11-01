package com.cadence.app.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.sql.SQLException;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerSqlTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void aDeadlockAnswers409AskingToRetry() {
        ResponseEntity<ProblemDetail> answer = handler.onConcurrencyFailure(new CannotAcquireLockException("x", new SQLException("deadlock detected", "40P01")));

        assertThat(answer.getStatusCode().value()).isEqualTo(409);
        ProblemDetail body = Objects.requireNonNull(answer.getBody());
        assertThat(body.getProperties()).containsEntry("code", "CONCURRENT_RETRY");
        assertThat(body.getDetail()).contains("retry");
    }

    /** PostgreSQL's DETAIL quotes the row, a phone among its values. */
    @Test
    void anUnmappedViolationIsLoggedWithoutItsDetail(CapturedOutput output) {
        handler.onDataIntegrity(new DataIntegrityViolationException("sql", new SQLException(
                "ERROR: duplicate key value violates unique constraint \"client_phone_uq\"\n  Detail: Key (normalized_phone)=(+995555123456) already exists.", "23505")));

        assertThat(output.getOut()).contains("client_phone_uq").contains("23505").doesNotContain("+995555123456");
    }
}

package com.cadence.app.web;

import com.cadence.app.ratelimit.RateLimitedException;
import com.cadence.platform.error.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> onDomain(DomainException ex) {
        return problem(ex.status(), ex.code(), ex.getMessage());
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ProblemDetail> onRateLimited(RateLimitedException ex) {
        HttpStatus http = HttpStatus.valueOf(ex.status());
        return ResponseEntity.status(http)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemBody(http, ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ProblemDetail> onMissing(NoSuchElementException ex) {
        return problem(404, "NOT_FOUND", ex.getMessage() == null ? "Not found" : ex.getMessage());
    }

    /** Malformed JSON or a wrong type in the body. Neither the detail nor the log holds the parser's message: it quotes the input. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> onUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Request body refused: {}", ex.getClass().getSimpleName());
        return problem(400, "INVALID_REQUEST_BODY", "The request body cannot be read.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> onBadParameter(MethodArgumentTypeMismatchException ex) {
        return problem(400, "INVALID_PARAMETER", "Parameter " + ex.getName() + " has an invalid value.");
    }

    /**
     * Safety net for constraint violations that no service translated (known races are mapped to their own codes,
     * e.g. SLOT_OCCUPIED). The detail is generic on purpose: the exception message holds SQL and constraint text.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> onDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Unmapped data integrity violation, answering 409 DATA_CONFLICT", ex);
        return problem(409, "DATA_CONFLICT", "The request conflicts with the current state of the data. Reload and retry.");
    }

    private static ResponseEntity<ProblemDetail> problem(int status, String code, String detail) {
        HttpStatus http = HttpStatus.valueOf(status);
        return ResponseEntity.status(http)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemBody(http, code, detail));
    }

    private static ProblemDetail problemBody(HttpStatus http, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(http, detail);
        body.setTitle(code);
        body.setType(URI.create("https://cadence.dev/problems/" + code));
        body.setProperty("code", code);
        return body;
    }
}

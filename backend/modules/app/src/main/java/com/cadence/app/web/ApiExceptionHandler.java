package com.cadence.app.web;

import com.cadence.platform.error.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> onDomain(DomainException ex) {
        return problem(ex.status(), ex.code(), ex.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ProblemDetail> onMissing(NoSuchElementException ex) {
        return problem(404, "NOT_FOUND", ex.getMessage() == null ? "Not found" : ex.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(int status, String code, String detail) {
        HttpStatus http = HttpStatus.valueOf(status);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(http, detail);
        body.setTitle(code);
        body.setType(URI.create("https://cadence.dev/problems/" + code));
        body.setProperty("code", code);
        return ResponseEntity.status(http)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}

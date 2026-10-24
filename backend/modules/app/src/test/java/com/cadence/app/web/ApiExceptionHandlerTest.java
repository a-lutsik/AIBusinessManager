package com.cadence.app.web;

import com.cadence.app.ratelimit.RateLimitedException;
import com.cadence.platform.error.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {

    private static final String SQL_TEXT = "insert into appointment (id, tenant_id) values (?, ?)";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    private static DataIntegrityViolationException leaky() {
        return new DataIntegrityViolationException(
                "jOOQ; SQL [" + SQL_TEXT + "]; ERROR: conflicting key value violates exclusion constraint "
                        + "\"no_overlapping_active_appointments\"",
                new SQLException("ERROR: conflicting key value violates exclusion constraint", "23P01"));
    }

    @Test
    void dataIntegrityViolationBecomesGenericConflict() {
        ResponseEntity<ProblemDetail> response = handler.onDataIntegrity(leaky());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getProperties()).containsEntry("code", "DATA_CONFLICT");
        assertThat(body.getTitle()).isEqualTo("DATA_CONFLICT");
        assertThat(body.getDetail())
                .doesNotContain("SQL").doesNotContain("insert").doesNotContain("appointment").doesNotContain("constraint");
    }

    @Test
    void domainExceptionMappingIsUnchanged() {
        ResponseEntity<ProblemDetail> response = handler.onDomain(DomainException.conflict("SLOT_OCCUPIED", "Slot is not available"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getProperties()).containsEntry("code", "SLOT_OCCUPIED");
    }

    @RestController
    static class Boom {
        @GetMapping("/boom/integrity")
        String integrity() {
            throw leaky();
        }

        @GetMapping("/boom/duplicate")
        String duplicate() {
            throw new DuplicateKeyException("jOOQ; SQL [" + SQL_TEXT + "]", new SQLException("dup", "23505"));
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Boom()).setControllerAdvice(handler).build();

    @Test
    void servletLevelResponseIsProblemJsonWithoutSqlText() throws Exception {
        mvc.perform(get("/boom/integrity"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code", is("DATA_CONFLICT")))
                .andExpect(content().string(not(containsString("insert into"))))
                .andExpect(content().string(not(containsString("no_overlapping_active_appointments"))));
    }

    @Test
    void duplicateKeySubclassIsCoveredToo() throws Exception {
        mvc.perform(get("/boom/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DATA_CONFLICT")))
                .andExpect(content().string(not(containsString("insert into"))));
    }

    @Test
    void rateLimitedAnswers429ProblemJsonWithRetryAfter() {
        ResponseEntity<ProblemDetail> response =
                handler.onRateLimited(new RateLimitedException("Please wait a minute and try again.", 42));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("42");
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getProperties()).containsEntry("code", "RATE_LIMITED");
        assertThat(body.getTitle()).isEqualTo("RATE_LIMITED");
        assertThat(body.getDetail()).isEqualTo("Please wait a minute and try again.");
    }
}

package com.cadence.app.web;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.catalog.api.ServiceView;
import com.cadence.platform.tenancy.ActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The central MASTER response policy: which responses it touches and what it does to them. */
class MasterViewPolicyTest {

    private static final UUID NINO = UUID.randomUUID();

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final MasterViewPolicy policy = new MasterViewPolicy(mapper);

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private static AppointmentView visit() {
        return new AppointmentView(
                UUID.randomUUID(), NINO, UUID.randomUUID(), UUID.randomUUID(), AppointmentStatus.COMPLETED,
                Instant.parse("2026-10-24T10:00:00Z"), Instant.parse("2026-10-24T11:00:00Z"),
                Instant.parse("2026-10-24T09:50:00Z"), Instant.parse("2026-10-24T11:10:00Z"),
                "Haircut", 8000, 60, 10, 10, "GEL", 500, 7500,
                "Client 1", "note", "CONSOLE", "Client 1", "+995555000001", "OK"
        );
    }

    private static ServiceView service() {
        return new ServiceView(UUID.randomUUID(), "Haircut", "d", 60, 8000, 10, 10, "#000", true, true);
    }

    private Object write(Object body, String path, Class<?> converter) {
        return write(body, new MockHttpServletRequest("GET", path), converter);
    }

    private Object write(Object body, MockHttpServletRequest request, Class<?> converter) {
        return policy.beforeBodyWrite(
                body,
                (MethodParameter) null,
                MediaType.APPLICATION_JSON,
                (Class) converter,
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse())
        );
    }

    @Test
    void moneyKeysAreNulledAtAnyDepthAndOtherFieldsSurvive() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("history", List.of(visit()));
        body.put("services", List.of(service()));
        body.put("nested", Map.of("totalRevenue", 120, "packages", List.of(Map.of("valueMinor", 30000, "name", "5x"))));

        JsonNode redacted = MasterViewPolicy.redact(mapper.valueToTree(body));

        JsonNode visit = redacted.get("history").get(0);
        for (String money : List.of("priceSnapshot", "discountAmount", "amountReceived")) {
            assertThat(visit.has(money)).as("%s keeps its key", money).isTrue();
            assertThat(visit.get(money).isNull()).as("%s is null, never 0", money).isTrue();
        }
        assertThat(visit.get("durationSnapshot").asInt()).isEqualTo(60);
        assertThat(visit.get("bufferBeforeSnapshot").asInt()).isEqualTo(10);
        assertThat(visit.get("currencyCode").asString()).isEqualTo("GEL");
        assertThat(visit.get("clientPhone").asString()).isEqualTo("+995555000001");
        assertThat(visit.get("specialistId").asString()).isEqualTo(NINO.toString());

        JsonNode service = redacted.get("services").get(0);
        assertThat(service.get("priceMinor").isNull()).isTrue();
        assertThat(service.get("durationMinutes").asInt()).isEqualTo(60);

        assertThat(redacted.get("nested").get("totalRevenue").isNull()).isTrue();
        JsonNode pkg = redacted.get("nested").get("packages").get(0);
        assertThat(pkg.get("valueMinor").isNull()).isTrue();
        assertThat(pkg.get("name").asString()).isEqualTo("5x");
    }

    @Test
    void moneyKeyMatchIsCaseInsensitiveSubstring() {
        for (String key : List.of("price", "priceMinorOverride", "defaultPriceMinor", "AMOUNT", "completedValueMinor",
                "revenueMinor", "discountAmount", "valueMinor", "value")) {
            assertThat(MasterViewPolicy.isMoneyKey(key)).as(key).isTrue();
        }
        for (String key : List.of("durationMinutes", "minNoticeMinutes", "currencyCode", "clientId", "level",
                "bufferAfterMinutesOverride", "noShowCount")) {
            assertThat(MasterViewPolicy.isMoneyKey(key)).as(key).isFalse();
        }
    }

    @Test
    void masterResponseUnderAppApiIsRedacted() {
        ActorContext.set(new ActorContext.Actor("m", ActorContext.Role.MASTER, NINO));

        Object out = write(List.of(service()), "/api/app/services", JacksonJsonHttpMessageConverter.class);

        assertThat(out).isInstanceOf(JsonNode.class);
        assertThat(((JsonNode) out).get(0).get("priceMinor").isNull()).isTrue();
        assertThat(((JsonNode) out).get(0).get("name").asString()).isEqualTo("Haircut");
    }

    @Test
    void ownerResponseIsUntouched() {
        ActorContext.set(new ActorContext.Actor("o", ActorContext.Role.OWNER, null));
        List<ServiceView> body = List.of(service());

        assertThat(write(body, "/api/app/services", JacksonJsonHttpMessageConverter.class)).isSameAs(body);
    }

    @Test
    void anyNonOwnerOrMissingActorIsRedactedFailClosed() {
        ActorContext.clear();
        Object anonymous = write(List.of(service()), "/api/app/services", JacksonJsonHttpMessageConverter.class);
        assertThat(((JsonNode) anonymous).get(0).get("priceMinor").isNull()).isTrue();

        ActorContext.set(ActorContext.publicActor());
        Object publicActor = write(List.of(service()), "/api/app/services", JacksonJsonHttpMessageConverter.class);
        assertThat(((JsonNode) publicActor).get(0).get("priceMinor").isNull()).isTrue();
    }

    @Test
    void responsesOutsideAppApiAreUntouched() {
        ActorContext.set(new ActorContext.Actor("m", ActorContext.Role.MASTER, NINO));
        List<ServiceView> body = List.of(service());

        assertThat(write(body, "/api/public/tenants/lumen-studio/services", JacksonJsonHttpMessageConverter.class))
                .isSameAs(body);
        assertThat(write(body, "/api/application", JacksonJsonHttpMessageConverter.class)).isSameAs(body);
    }

    @Test
    void routeIsTakenFromTheDispatchedPatternNotTheRawUri() {
        ActorContext.set(new ActorContext.Actor("m", ActorContext.Role.MASTER, NINO));
        MockHttpServletRequest tricky = new MockHttpServletRequest("GET", "/api;x=1/app/services");
        tricky.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/app/services");

        Object out = write(List.of(service()), tricky, JacksonJsonHttpMessageConverter.class);

        assertThat(((JsonNode) out).get(0).get("priceMinor").isNull()).isTrue();
    }

    @Test
    void problemDetailsAndEmptyBodiesPassThrough() {
        ActorContext.set(new ActorContext.Actor("m", ActorContext.Role.MASTER, NINO));
        ProblemDetail problem = ProblemDetail.forStatus(403);

        assertThat(write(problem, "/api/app/rules", JacksonJsonHttpMessageConverter.class)).isSameAs(problem);
        assertThat(write(null, "/api/app/tasks/1/complete", JacksonJsonHttpMessageConverter.class)).isNull();
    }

    @Test
    void nonJsonConverterForAMasterIsRefusedRatherThanLeaking() {
        ActorContext.set(new ActorContext.Actor("m", ActorContext.Role.MASTER, NINO));

        assertThatThrownBy(() -> write("priceMinor=8000", "/api/app/services", StringHttpMessageConverter.class))
                .isInstanceOf(IllegalStateException.class);
    }
}

package com.cadence.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Free public slots of the demo tenant, searched over the whole booking horizon instead of assuming that a fixed few
 * days have openings. A demo schedule without any slot in that window is a broken fixture and fails the test; it is
 * never skipped.
 *
 * <p>Callers run with {@link RelaxedPublicRateLimits}: all MockMvc requests share one peer address, and
 * {@code X-Forwarded-For} from an untrusted peer is ignored by the rate limiter, so it cannot be used to dodge the
 * per-IP slot-read limit.
 */
final class TestSlots {

    static final ZoneId TBILISI = ZoneId.of("Asia/Tbilisi");
    /** Demo booking rules use a 28-day horizon; slots beyond it are never offered. */
    static final int HORIZON_DAYS = 28;

    private TestSlots() {
    }

    /** All free slots (ISO instants, ascending) from {@code fromDaysAhead} to the end of the horizon. */
    static List<String> free(MockMvc mvc, ObjectMapper mapper, UUID serviceId, UUID specialistId, int fromDaysAhead)
            throws Exception {
        LocalDate today = LocalDate.now(TBILISI);
        MvcResult res = mvc.perform(get("/api/public/tenants/lumen-studio/slots")
                        .param("serviceId", serviceId.toString())
                        .param("specialistId", specialistId.toString())
                        .param("from", today.plusDays(fromDaysAhead).toString())
                        .param("to", today.plusDays(HORIZON_DAYS).toString()))
                .andReturn();
        assertThat(res.getResponse().getStatus()).as("slots request").isEqualTo(200);
        JsonNode array = mapper.readTree(res.getResponse().getContentAsByteArray());
        List<String> slots = new ArrayList<>();
        array.forEach(n -> slots.add(n.asText()));
        return slots;
    }

    /** The earliest free slot at or after {@code fromDaysAhead}; fails with a clear message when there is none. */
    static String firstFree(MockMvc mvc, ObjectMapper mapper, UUID serviceId, UUID specialistId, int fromDaysAhead)
            throws Exception {
        List<String> slots = free(mvc, mapper, serviceId, specialistId, fromDaysAhead);
        assertThat(slots)
                .as("demo schedule must offer a free slot for service %s with specialist %s between +%d and +%d days",
                        serviceId, specialistId, fromDaysAhead, HORIZON_DAYS)
                .isNotEmpty();
        return slots.get(0);
    }
}

package com.cadence.app;

import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.BookingService;
import com.cadence.booking.api.CreateBookingCommand;
import com.cadence.catalog.api.BookingRulesView;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.MasterServiceView;
import com.cadence.catalog.api.ServiceView;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;

import static com.cadence.app.TestJwt.owner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class DashboardSignalsIT extends AbstractTimescaleIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;
    @Autowired
    TenantDirectory tenants;
    @Autowired
    DSLContext dsl;
    @Autowired
    CatalogService catalog;
    @Autowired
    BookingService booking;
    @Autowired
    PlatformTransactionManager transactions;

    private JsonNode read(UUID tenant, String path) throws Exception {
        return JSON.readTree(mvc.perform(get(path).with(owner(tenant))).andReturn().getResponse().getContentAsByteArray());
    }

    /** With metrics calculated and nothing to report, the screen gets no card claiming that metrics are missing. */
    @Test
    void noSignalsMeansAnEmptyListBeforeAndAfterTheRecalculation() throws Exception {
        UUID tenant = UUID.randomUUID();
        tenants.upsert(tenant, "signals-it-" + tenant, "Signals IT", "Asia/Tbilisi", "GEL", "GE");
        assertThat(read(tenant, "/api/app/dashboard").path("signals")).isEmpty();

        assertThat(mvc.perform(post("/api/app/metrics/recalculate").with(owner(tenant))).andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(read(tenant, "/api/app/signals")).isEmpty();
        JsonNode metrics = read(tenant, "/api/app/dashboard").path("metrics");
        assertThat(metrics).hasSize(6);
        for (JsonNode metric : metrics) {
            assertThat(metric.path("explanation").asText()).as(metric.path("key").asText())
                    .isNotBlank().doesNotContain("Will appear after");
        }
    }

    /** What the stack really throws on SQLSTATE 40P01 is what {@code ApiExceptionHandler} answers as a retry. */
    @Test
    void aDeadlockReachesTheHandlerAsAConcurrencyFailure() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                dsl.execute("do $$ begin raise exception 'deadlock' using errcode = '40P01'; end $$")))
                .isInstanceOf(ConcurrencyFailureException.class);
    }

    /** Today as the tenant counts it: past ones in, ordered; tomorrow out, even when its buffer reaches back across midnight. */
    @Test
    void todayIsTheDayOfTheTenantForTheOwnerAndTheMaster() throws Exception {
        UUID tenant = UUID.randomUUID();
        tenants.upsert(tenant, "today-it-" + tenant, "Today IT", "Pacific/Kiritimati", "GEL", "GE");
        ZoneId zone = ZoneId.of("Pacific/Kiritimati");
        LocalDate day = LocalDate.now(zone);
        UUID[] catalogIds = as(tenant, ActorContext.system(), () -> {
            catalog.saveRules(new BookingRulesView(15, 0, 30, true, 2, false, false, 1, true));
            UUID service = catalog.upsertService(new ServiceView(null, "Cut", "Cut", 30, 5000, 15, 0, "#0369a1", true, true)).id();
            UUID master = catalog.upsertSpecialist(new SpecialistView(null, "Tamar", null, "#0f766e", true)).id();
            catalog.upsertMatrix(new MasterServiceView(UUID.randomUUID(), master, service, true, null, null, null, null));
            return new UUID[]{service, master};
        });
        UUID late = visit(tenant, catalogIds, day.atTime(23, 0).atZone(zone).toInstant(), "+995591100001");
        UUID early = visit(tenant, catalogIds, day.atTime(1, 0).atZone(zone).toInstant(), "+995591100002");
        visit(tenant, catalogIds, day.minusDays(1).atTime(23, 50).atZone(zone).toInstant(), "+995591100003");
        visit(tenant, catalogIds, day.plusDays(1).atTime(0, 10).atZone(zone).toInstant(), "+995591100004");

        JsonNode today = read(tenant, "/api/app/dashboard").path("today");
        assertThat(today).extracting(visit -> visit.path("id").asText()).containsExactly(early.toString(), late.toString());

        JsonNode master = JSON.readTree(mvc.perform(get("/api/app/masters/me/today").with(TestJwt.master(tenant, catalogIds[1])))
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(master.path("appointments")).extracting(item -> item.path("appointment").path("id").asText())
                .containsExactly(early.toString(), late.toString());
    }

    private UUID visit(UUID tenant, UUID[] catalogIds, Instant start, String phone) {
        return as(tenant, new ActorContext.Actor("owner-it", ActorContext.Role.OWNER, null), () -> booking.create(new CreateBookingCommand(
                catalogIds[1], catalogIds[0], start, null, phone, "Today Client", "en", false, null, null, "CONSOLE", AppointmentStatus.CONFIRMED, true))).id();
    }

    private <T> T as(UUID tenant, ActorContext.Actor actor, Supplier<T> call) {
        try (TenantContext.Scope ignored = TenantContext.open(tenant)) {
            ActorContext.set(actor);
            return call.get();
        } finally {
            ActorContext.clear();
        }
    }
}

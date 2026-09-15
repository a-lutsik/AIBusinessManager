package com.aibusinessmanager.app.seed;

import com.aibusinessmanager.booking.api.AppointmentStatus;
import com.aibusinessmanager.booking.api.BookingService;
import com.aibusinessmanager.booking.api.CreateBookingCommand;
import com.aibusinessmanager.catalog.api.BookingRulesView;
import com.aibusinessmanager.catalog.api.CatalogService;
import com.aibusinessmanager.catalog.api.MasterServiceView;
import com.aibusinessmanager.catalog.api.ScheduleExceptionView;
import com.aibusinessmanager.catalog.api.ServiceView;
import com.aibusinessmanager.catalog.api.SpecialistView;
import com.aibusinessmanager.catalog.api.WeeklyInterval;
import com.aibusinessmanager.crm.api.ClientView;
import com.aibusinessmanager.crm.api.CrmService;
import com.aibusinessmanager.growth.api.GrowthService;
import com.aibusinessmanager.platform.tenancy.ActorContext;
import com.aibusinessmanager.platform.tenancy.TenantContext;
import com.aibusinessmanager.platform.tenant.TenantDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final TenantDirectory tenants;
    private final CatalogService catalog;
    private final CrmService crm;
    private final BookingService booking;
    private final GrowthService growth;

    public DemoDataSeeder(
            TenantDirectory tenants,
            CatalogService catalog,
            CrmService crm,
            BookingService booking,
            GrowthService growth
    ) {
        this.tenants = tenants;
        this.catalog = catalog;
        this.crm = crm;
        this.booking = booking;
        this.growth = growth;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    @Transactional
    public void seed() {
        if (tenants.findById(DemoIds.LUMEN).isPresent() && !catalogEmpty()) {
            log.info("Demo data already present, skipping seed");
            return;
        }
        seedLumen();
        seedAura();
        log.info("Demo tenants Lumen Studio and Aura Atelier seeded");
    }

    private boolean catalogEmpty() {
        try (var ignored = TenantContext.open(DemoIds.LUMEN)) {
            ActorContext.set(ActorContext.system());
            return catalog.listSpecialists(false).isEmpty();
        } finally {
            ActorContext.clear();
        }
    }

    private void seedLumen() {
        tenants.upsert(DemoIds.LUMEN, "lumen-studio", "Lumen Studio", "Asia/Tbilisi", "GEL", "GE");
        try (var ignored = TenantContext.open(DemoIds.LUMEN)) {
            ActorContext.set(ActorContext.system());
            catalog.saveRules(new BookingRulesView(15, 60, 28, true, 2, false, false, 1, true));
            upsertService(DemoIds.HAIRCUT, "Haircut", 45, 8000, 5, 10, "#0f766e");
            upsertService(DemoIds.BLOWOUT, "Blowout", 30, 5000, 5, 5, "#0369a1");
            upsertService(DemoIds.COLOR, "Color", 120, 20000, 10, 15, "#7c3aed");
            upsertService(DemoIds.HIGHLIGHTS, "Highlights", 150, 25000, 10, 15, "#c026d3");
            upsertService(DemoIds.MANICURE, "Manicure", 60, 7000, 5, 10, "#be123c");
            upsertService(DemoIds.PEDICURE, "Pedicure", 75, 9000, 5, 10, "#b45309");
            upsertService(DemoIds.BROW, "Brow Shaping", 20, 3500, 0, 5, "#0f766e");
            upsertService(DemoIds.CONSULT, "Consultation", 15, 0, 0, 0, "#57534e");
            catalog.upsertSpecialist(new SpecialistView(DemoIds.NINO, "Nino Beridze", "nino@lumen.studio", "#0f766e", true));
            catalog.upsertSpecialist(new SpecialistView(DemoIds.ANNA, "Anna Novakova", "anna@lumen.studio", "#7c3aed", true));
            catalog.upsertSpecialist(new SpecialistView(DemoIds.ELENA, "Elena Kravets", "elena@lumen.studio", "#be123c", true));
            offer(DemoIds.NINO, DemoIds.HAIRCUT, null, null);
            offer(DemoIds.NINO, DemoIds.BLOWOUT, null, null);
            offer(DemoIds.NINO, DemoIds.COLOR, 135, 15);
            offer(DemoIds.NINO, DemoIds.HIGHLIGHTS, null, null);
            offer(DemoIds.NINO, DemoIds.CONSULT, null, null);
            offer(DemoIds.ANNA, DemoIds.HAIRCUT, 50, 10);
            offer(DemoIds.ANNA, DemoIds.BLOWOUT, null, null);
            offer(DemoIds.ANNA, DemoIds.COLOR, null, null);
            offer(DemoIds.ANNA, DemoIds.CONSULT, null, null);
            offer(DemoIds.ELENA, DemoIds.MANICURE, null, null);
            offer(DemoIds.ELENA, DemoIds.PEDICURE, null, null);
            offer(DemoIds.ELENA, DemoIds.BROW, null, null);
            offer(DemoIds.ELENA, DemoIds.CONSULT, null, null);
            weekdays(DemoIds.NINO);
            weekdays(DemoIds.ANNA);
            weekdays(DemoIds.ELENA);
            catalog.upsertException(new ScheduleExceptionView(
                    UUID.randomUUID(), DemoIds.NINO, LocalDate.now().plusDays(10), "DAY_OFF", null, null, "Training day"
            ));
            List<ClientView> clients = new ArrayList<>();
            for (int i = 1; i <= 40; i++) {
                String phone = "+99555500" + String.format("%04d", i);
                clients.add(crm.findOrCreate(phone, "Client " + i, i % 2 == 0 ? "en" : "ru", true, "seed"));
            }
            ZoneId zone = ZoneId.of("Asia/Tbilisi");
            UUID[] masters = {DemoIds.NINO, DemoIds.ANNA, DemoIds.ELENA};
            UUID[][] offerings = {
                    {DemoIds.HAIRCUT, DemoIds.BLOWOUT, DemoIds.COLOR, DemoIds.CONSULT},
                    {DemoIds.HAIRCUT, DemoIds.BLOWOUT, DemoIds.COLOR, DemoIds.CONSULT},
                    {DemoIds.MANICURE, DemoIds.PEDICURE, DemoIds.BROW, DemoIds.CONSULT}
            };
            AppointmentStatus[] statuses = {
                    AppointmentStatus.COMPLETED, AppointmentStatus.COMPLETED, AppointmentStatus.COMPLETED,
                    AppointmentStatus.CONFIRMED, AppointmentStatus.NO_SHOW, AppointmentStatus.CANCELLED_BY_CLIENT,
                    AppointmentStatus.PENDING, AppointmentStatus.COMPLETED
            };
            int created = 0;
            LocalDate startDay = LocalDate.now(zone).minusDays(80);
            for (int i = 0; i < 240 && created < 200; i++) {
                int masterIdx = i % 3;
                UUID master = masters[masterIdx];
                UUID service = offerings[masterIdx][(i / 3) % offerings[masterIdx].length];
                LocalDate day = startDay.plusDays(i % 95);
                if (day.getDayOfWeek().getValue() == 7) {
                    continue;
                }
                int startMinute = 10 * 60 + (i % 4) * 90;
                var start = LocalDateTime.of(day, java.time.LocalTime.of(startMinute / 60, startMinute % 60))
                        .atZone(zone).toInstant();
                ClientView client = clients.get(i % clients.size());
                AppointmentStatus status = start.isAfter(java.time.Instant.now())
                        ? (i % 5 == 0 ? AppointmentStatus.PENDING : AppointmentStatus.CONFIRMED)
                        : statuses[i % statuses.length];
                try {
                    booking.create(new CreateBookingCommand(
                            master,
                            service,
                            start,
                            client.id(),
                            null,
                            client.displayName(),
                            client.preferredLocale(),
                            true,
                            "seed",
                            null,
                            "SEED",
                            status,
                            true
                    ));
                    created++;
                } catch (Exception ex) {
                    log.debug("Skip overlapping seed visit {}", ex.getMessage());
                }
            }
            try {
                growth.recalculate(LocalDate.now(zone).minusDays(90), LocalDate.now(zone));
            } catch (Exception ex) {
                log.warn("Metrics seed recalculation skipped: {}", ex.getMessage());
            }
        } finally {
            ActorContext.clear();
        }
    }

    private void seedAura() {
        tenants.upsert(DemoIds.AURA, "aura-atelier", "Aura Atelier", "Europe/Prague", "CZK", "CZ");
        try (var ignored = TenantContext.open(DemoIds.AURA)) {
            ActorContext.set(ActorContext.system());
            catalog.saveRules(new BookingRulesView(15, 120, 21, false, 4, true, false, 1, true));
            catalog.upsertService(new ServiceView(DemoIds.AURA_CUT, "Střih", "Cut", 40, 90000, 5, 10, "#0369a1", true, true));
            catalog.upsertSpecialist(new SpecialistView(DemoIds.AURA_MASTER, "Klára Svobodová", null, "#0369a1", true));
            offer(DemoIds.AURA_MASTER, DemoIds.AURA_CUT, null, null);
            weekdays(DemoIds.AURA_MASTER);
            crm.findOrCreate("+420777000001", "Jana Nováková", "cs", false, "seed");
        } finally {
            ActorContext.clear();
        }
    }

    private void upsertService(UUID id, String name, int duration, int price, int before, int after, String color) {
        catalog.upsertService(new ServiceView(id, name, name, duration, price, before, after, color, true, true));
    }

    private void offer(UUID specialist, UUID service, Integer durationOverride, Integer bufferAfterOverride) {
        catalog.upsertMatrix(new MasterServiceView(
                UUID.randomUUID(), specialist, service, true, durationOverride, null, null, bufferAfterOverride
        ));
    }

    private void weekdays(UUID specialist) {
        List<WeeklyInterval> intervals = new ArrayList<>();
        for (int day = 1; day <= 6; day++) {
            intervals.add(new WeeklyInterval(UUID.randomUUID(), specialist, day, 10 * 60, 19 * 60));
        }
        catalog.replaceWeeklySchedule(specialist, intervals);
    }
}

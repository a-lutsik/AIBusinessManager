package com.cadence.app.web;

import com.cadence.ai.api.AiAssistantService;
import com.cadence.ai.api.ChatResponse;
import com.cadence.booking.api.AppointmentStatus;
import com.cadence.booking.api.AppointmentView;
import com.cadence.booking.api.BookingService;
import com.cadence.booking.api.CreateBookingCommand;
import com.cadence.catalog.api.BookingRulesView;
import com.cadence.catalog.api.CatalogService;
import com.cadence.catalog.api.MasterServiceView;
import com.cadence.catalog.api.ScheduleExceptionView;
import com.cadence.catalog.api.ServiceView;
import com.cadence.catalog.api.SpecialistView;
import com.cadence.catalog.api.WeeklyInterval;
import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.growth.api.GrowthService;
import com.cadence.growth.api.MetricView;
import com.cadence.notification.api.OwnerTaskService;
import com.cadence.notification.api.OwnerTaskView;
import com.cadence.platform.tenancy.ActorContext;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import com.cadence.retention.api.RetentionService;
import com.cadence.retention.api.TrustView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/app")
public class AppApiController {

    private final CatalogService catalogService;
    private final BookingService bookingService;
    private final CrmService crmService;
    private final RetentionService retentionService;
    private final GrowthService growthService;
    private final AiAssistantService aiAssistantService;
    private final OwnerTaskService ownerTaskService;
    private final TenantDirectory tenants;

    public AppApiController(
            CatalogService catalogService,
            BookingService bookingService,
            CrmService crmService,
            RetentionService retentionService,
            GrowthService growthService,
            AiAssistantService aiAssistantService,
            OwnerTaskService ownerTaskService,
            TenantDirectory tenants
    ) {
        this.catalogService = catalogService;
        this.bookingService = bookingService;
        this.crmService = crmService;
        this.retentionService = retentionService;
        this.growthService = growthService;
        this.aiAssistantService = aiAssistantService;
        this.ownerTaskService = ownerTaskService;
        this.tenants = tenants;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("crm", crmService.moduleName());
        body.put("tenant", TenantContext.current().map(Object::toString).orElse(null));
        return body;
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        ActorContext.Actor actor = ActorContext.current().orElse(new ActorContext.Actor("anon", ActorContext.Role.OWNER, null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actorId", actor.actorId());
        body.put("role", actor.role().name());
        body.put("specialistId", actor.specialistId());
        body.put("tenantId", TenantContext.current().orElse(null));
        TenantContext.current().flatMap(tenants::findById).ifPresent(t -> {
            body.put("displayName", t.getDisplayName());
            body.put("timezone", t.getTimezone());
            body.put("currencyCode", t.getCurrencyCode());
            body.put("countryCode", t.getCountryCode());
            body.put("slug", t.getSlug());
        });
        return body;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        Instant from = Instant.now().minusSeconds(3600);
        Instant to = Instant.now().plusSeconds(86400);
        List<AppointmentView> today = bookingService.calendar(from, to, null);
        List<MetricView> metrics = growthService.currentMetrics(null);
        List<OwnerTaskView> tasks = ownerTaskService.listOpen();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("today", today);
        body.put("metrics", metrics);
        body.put("tasks", tasks);
        body.put("signals", growthService.signals());
        return body;
    }

    @GetMapping("/services")
    public List<ServiceView> services() {
        return catalogService.listServices(false);
    }

    @PutMapping("/services")
    public ServiceView saveService(@RequestBody ServiceView body) {
        assertOwner();
        return catalogService.upsertService(body);
    }

    @GetMapping("/specialists")
    public List<SpecialistView> specialists() {
        if (ActorContext.current().map(ActorContext.Actor::isMaster).orElse(false)) {
            UUID id = ActorContext.require().specialistId();
            return catalogService.findSpecialist(id).stream().toList();
        }
        return catalogService.listSpecialists(false);
    }

    @PutMapping("/specialists")
    public SpecialistView saveSpecialist(@RequestBody SpecialistView body) {
        assertOwner();
        return catalogService.upsertSpecialist(body);
    }

    @GetMapping("/specialists/{id}/matrix")
    public List<MasterServiceView> matrix(@PathVariable UUID id) {
        return catalogService.listMatrix(id);
    }

    @PutMapping("/specialists/{id}/matrix")
    public MasterServiceView saveMatrix(@PathVariable UUID id, @RequestBody MasterServiceView body) {
        assertOwner();
        return catalogService.upsertMatrix(new MasterServiceView(
                body.id(),
                id,
                body.serviceId(),
                body.offered(),
                body.durationMinutesOverride(),
                body.priceMinorOverride(),
                body.bufferBeforeMinutesOverride(),
                body.bufferAfterMinutesOverride()
        ));
    }

    @GetMapping("/specialists/{id}/schedule")
    public List<WeeklyInterval> schedule(@PathVariable UUID id) {
        return catalogService.weeklySchedule(id);
    }

    @PutMapping("/specialists/{id}/schedule")
    public List<WeeklyInterval> saveSchedule(@PathVariable UUID id, @RequestBody List<WeeklyInterval> body) {
        assertOwner();
        catalogService.replaceWeeklySchedule(id, body);
        return catalogService.weeklySchedule(id);
    }

    @PutMapping("/specialists/{id}/exceptions")
    public ScheduleExceptionView saveException(@PathVariable UUID id, @RequestBody ScheduleExceptionView body) {
        assertOwner();
        return catalogService.upsertException(new ScheduleExceptionView(
                body.id(), id, body.date(), body.kind(), body.startMinute(), body.endMinute(), body.note()
        ));
    }

    @GetMapping("/rules")
    public BookingRulesView rules() {
        return catalogService.rules();
    }

    @PutMapping("/rules")
    public BookingRulesView saveRules(@RequestBody BookingRulesView body) {
        assertOwner();
        return catalogService.saveRules(body);
    }

    @GetMapping("/calendar")
    public List<AppointmentView> calendar(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) UUID specialistId
    ) {
        return bookingService.calendar(from, to, specialistId);
    }

    @GetMapping("/appointments/{id}")
    public AppointmentView appointment(@PathVariable UUID id) {
        return bookingService.find(id).orElseThrow();
    }

    @PostMapping("/appointments")
    public AppointmentView create(@RequestBody CreateBookingCommand command) {
        return bookingService.create(command);
    }

    @PostMapping("/appointments/{id}/transition")
    public AppointmentView transition(@PathVariable UUID id, @RequestParam AppointmentStatus to, @RequestParam(required = false) String note) {
        return bookingService.transition(id, to, note);
    }

    @PostMapping("/appointments/{id}/reschedule")
    public AppointmentView reschedule(@PathVariable UUID id, @RequestParam Instant start) {
        return bookingService.reschedule(id, start);
    }

    @GetMapping("/clients")
    public List<ClientView> clients(@RequestParam(required = false) String q) {
        List<ClientView> all = crmService.search(q);
        ActorContext.Actor actor = ActorContext.current().orElse(null);
        if (actor != null && actor.isMaster()) {
            Instant from = Instant.now().minusSeconds(365L * 86400);
            List<UUID> mine = bookingService.calendar(from, Instant.now().plusSeconds(365L * 86400), actor.specialistId())
                    .stream().map(AppointmentView::clientId).distinct().toList();
            return all.stream().filter(c -> mine.contains(c.id())).toList();
        }
        return all;
    }

    @GetMapping("/clients/{id}")
    public Map<String, Object> client(@PathVariable UUID id) {
        ClientView client = crmService.findById(id).orElseThrow();
        TrustView trust = retentionService.assess(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", client);
        body.put("trust", trust);
        Instant from = Instant.parse("2020-01-01T00:00:00Z");
        List<AppointmentView> history = bookingService.calendar(from, Instant.now().plusSeconds(365L * 86400), null)
                .stream()
                .filter(a -> a.clientId().equals(id))
                .toList();
        ActorContext.Actor actor = ActorContext.current().orElse(null);
        if (actor != null && actor.isMaster()) {
            history = history.stream().filter(a -> a.specialistId().equals(actor.specialistId())).toList();
        }
        body.put("history", history);
        return body;
    }

    @PostMapping("/clients/{id}/trust")
    public TrustView overrideTrust(@PathVariable UUID id, @RequestParam String level, @RequestParam String reason) {
        assertOwner();
        return retentionService.override(id, level, reason);
    }

    @PostMapping("/clients/{id}/packages")
    public RetentionService.PackageView sellPackage(
            @PathVariable UUID id,
            @RequestParam String name,
            @RequestParam int sessions,
            @RequestParam int valueMinor
    ) {
        assertOwner();
        String currency = tenants.requireById(TenantContext.require()).getCurrencyCode();
        return retentionService.sellPackage(id, name, sessions, valueMinor, currency);
    }

    @GetMapping("/tasks")
    public List<OwnerTaskView> tasks() {
        assertOwner();
        return ownerTaskService.listOpen();
    }

    @PostMapping("/tasks/{id}/complete")
    public void completeTask(@PathVariable UUID id) {
        assertOwner();
        ownerTaskService.complete(id);
    }

    @GetMapping("/metrics")
    public List<MetricView> metrics() {
        assertOwner();
        return growthService.currentMetrics(null);
    }

    @PostMapping("/metrics/recalculate")
    public List<MetricView> recalculate() {
        assertOwner();
        LocalDate end = LocalDate.now();
        return growthService.recalculate(end.minusDays(90), end);
    }

    @PostMapping("/ai/chat")
    public ChatResponse chat(@RequestBody Map<String, String> body) {
        assertOwner();
        UUID convo = body.get("conversationId") == null ? null : UUID.fromString(body.get("conversationId"));
        return aiAssistantService.chat(convo, body.getOrDefault("locale", "en"), body.getOrDefault("message", ""));
    }

    @PostMapping("/ai/drafts/{id}/confirm")
    public ChatResponse confirm(@PathVariable UUID id) {
        assertOwner();
        return aiAssistantService.confirmDraft(id);
    }

    private void assertOwner() {
        ActorContext.Actor actor = ActorContext.current().orElse(null);
        if (actor != null && actor.isMaster()) {
            throw com.cadence.platform.error.DomainException.forbidden(
                    "FORBIDDEN_RESOURCE", "Owner-only endpoint"
            );
        }
    }
}

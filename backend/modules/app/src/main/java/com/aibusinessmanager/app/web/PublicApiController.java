package com.aibusinessmanager.app.web;

import com.aibusinessmanager.booking.api.AppointmentView;
import com.aibusinessmanager.booking.api.BookingService;
import com.aibusinessmanager.booking.api.CreateBookingCommand;
import com.aibusinessmanager.booking.api.PublicBookingResult;
import com.aibusinessmanager.catalog.api.CatalogService;
import com.aibusinessmanager.catalog.api.Offering;
import com.aibusinessmanager.catalog.api.ServiceView;
import com.aibusinessmanager.catalog.api.SpecialistView;
import com.aibusinessmanager.app.web.dto.CreatePublicBookingRequest;
import com.aibusinessmanager.platform.error.DomainException;
import com.aibusinessmanager.platform.tenancy.TenantContext;
import com.aibusinessmanager.platform.tenant.TenantDirectory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
@RequestMapping("/api/public")
public class PublicApiController {

    private final CatalogService catalogService;
    private final BookingService bookingService;
    private final TenantDirectory tenants;

    public PublicApiController(CatalogService catalogService, BookingService bookingService, TenantDirectory tenants) {
        this.catalogService = catalogService;
        this.bookingService = bookingService;
        this.tenants = tenants;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("catalog", catalogService.moduleName());
        body.put("booking", bookingService.moduleName());
        body.put("tenant", TenantContext.current().map(Object::toString).orElse(null));
        return body;
    }

    @GetMapping("/tenants/{slug}")
    public Map<String, Object> tenant(@PathVariable String slug) {
        var tenant = tenants.requireBySlug(slug);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", tenant.getId());
        body.put("slug", tenant.getSlug());
        body.put("displayName", tenant.getDisplayName());
        body.put("timezone", tenant.getTimezone());
        body.put("currencyCode", tenant.getCurrencyCode());
        body.put("countryCode", tenant.getCountryCode());
        return body;
    }

    @GetMapping("/tenants/{slug}/services")
    public List<ServiceView> services(@PathVariable String slug) {
        tenants.requireBySlug(slug);
        return catalogService.listServices(true);
    }

    @GetMapping("/tenants/{slug}/services/{serviceId}/specialists")
    public List<Map<String, Object>> specialists(@PathVariable String slug, @PathVariable UUID serviceId) {
        tenants.requireBySlug(slug);
        List<Offering> offerings = catalogService.offeringsForService(serviceId);
        return offerings.stream().map(o -> {
            SpecialistView specialist = catalogService.findSpecialist(o.specialistId()).orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", o.specialistId());
            row.put("displayName", specialist == null ? "" : specialist.displayName());
            row.put("durationMinutes", o.durationMinutes());
            row.put("priceMinor", o.priceMinor());
            row.put("color", specialist == null ? o.color() : specialist.calendarColor());
            return row;
        }).toList();
    }

    @GetMapping("/tenants/{slug}/slots")
    public List<Instant> slots(
            @PathVariable String slug,
            @RequestParam UUID serviceId,
            @RequestParam UUID specialistId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to
    ) {
        tenants.requireBySlug(slug);
        return bookingService.freeSlots(specialistId, serviceId, from, to);
    }

    @PostMapping("/tenants/{slug}/bookings")
    public Map<String, Object> book(@PathVariable String slug, @RequestBody CreatePublicBookingRequest request) {
        tenants.requireBySlug(slug);
        PublicBookingResult result = bookingService.createPublic(new CreateBookingCommand(
                request.specialistId(),
                request.serviceId(),
                request.serviceStart(),
                null,
                request.phone(),
                request.name(),
                request.locale(),
                request.marketingConsent(),
                "public-v1",
                null,
                "PUBLIC",
                null,
                false
        ));
        AppointmentView a = result.appointment();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", a.id());
        body.put("status", a.status().name());
        body.put("serviceStart", a.serviceStart());
        body.put("serviceEnd", a.serviceEnd());
        body.put("serviceName", a.serviceNameSnapshot());
        body.put("priceMinor", a.priceSnapshot());
        body.put("currencyCode", a.currencyCode());
        body.put("accessToken", result.accessToken());
        body.put("managePath", result.managePath());
        return body;
    }

    @GetMapping("/bookings/{id}")
    public AppointmentView booking(@PathVariable UUID id, @RequestParam String token) {
        AppointmentView view = bookingService.findByAccessToken(token)
                .orElseThrow(() -> DomainException.notFound("TOKEN_INVALID", "Booking link is invalid"));
        if (!view.id().equals(id)) {
            throw DomainException.forbidden("TOKEN_INVALID", "Token does not match this visit");
        }
        return view;
    }

    @PostMapping("/bookings/{id}/cancel")
    public AppointmentView cancel(@PathVariable UUID id, @RequestParam String token) {
        AppointmentView view = bookingService.cancelByAccessToken(token, true);
        if (!view.id().equals(id)) {
            throw DomainException.forbidden("TOKEN_INVALID", "Token does not match this visit");
        }
        return view;
    }

    @PostMapping("/bookings/{id}/reschedule")
    public AppointmentView reschedule(
            @PathVariable UUID id,
            @RequestParam String token,
            @RequestParam Instant start
    ) {
        AppointmentView view = bookingService.rescheduleByAccessToken(token, start);
        if (!view.id().equals(id)) {
            throw DomainException.forbidden("TOKEN_INVALID", "Token does not match this visit");
        }
        return view;
    }

    @GetMapping("/bookings/{id}/calendar.ics")
    public ResponseEntity<String> ics(@PathVariable UUID id, @RequestParam String token) {
        AppointmentView view = bookingService.findByAccessToken(token)
                .orElseThrow(() -> DomainException.notFound("TOKEN_INVALID", "Booking link is invalid"));
        if (!view.id().equals(id)) {
            throw DomainException.forbidden("TOKEN_INVALID", "Token does not match this visit");
        }
        String ics = bookingService.icsByAccessToken(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"visit.ics\"")
                .contentType(MediaType.parseMediaType("text/calendar"))
                .body(ics);
    }
}

package com.cadence.growth.internal;

import com.cadence.growth.api.MetricView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Signal rules over the tenant metrics of the current month (ADR 0001 §5), pure. Only metrics with enough data
 * fire; thresholds are ratios, never currency amounts; sums of money appear only in the action payload.
 */
final class SignalRules {

    static final double NO_SHOW_SPIKE_AT = 0.15;
    static final double UTILIZATION_GAP_BELOW = 0.40;
    static final int LOW_VALUE_MIN_VISITS = 3;
    static final double LOW_VALUE_SHARE_BELOW = 0.5;
    private static final int MAX_SERVICES_LISTED = 5;
    private static final String TENANT = "tenant";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SignalRules() {
    }

    static String dedupeKey(String signalKey, String scope, YearMonth period) {
        return signalKey + ":" + scope + ":" + period;
    }

    static List<GrowthSignalRepository.Draft> evaluate(MetricsCalculator.Result tenant, YearMonth period) {
        List<GrowthSignalRepository.Draft> drafts = new ArrayList<>();
        sufficient(tenant, "NO_SHOW_RATE")
                .filter(m -> m.value() >= NO_SHOW_SPIKE_AT)
                .ifPresent(m -> drafts.add(draft("NO_SHOW_SPIKE", period,
                        "No-show rate is elevated",
                        tenant.noShows() + " of " + tenant.dueVisits() + " visits that reached their start were no-shows",
                        "Tighten Trust for new clients and send the 24h reminder",
                        "WARNING", "OPEN_RULES", "{\"focus\":\"newClientRequiresConfirmation\"}")));
        sufficient(tenant, "SLOT_UTILIZATION")
                .filter(m -> m.value() < UTILIZATION_GAP_BELOW)
                .ifPresent(m -> drafts.add(draft("UTILIZATION_GAP", period,
                        "Low slot utilization",
                        "Booked " + tenant.bookedMinutes() + " / " + tenant.availableMinutes() + " available minutes",
                        "Open a reactivation campaign for clients past their usual interval",
                        "WARNING", "OPEN_CLIENTS", null)));
        sufficient(tenant, "REVENUE_PER_HOUR")
                .filter(m -> m.value() > 0)
                .ifPresent(m -> lowValueServices(tenant, m.value(), period).ifPresent(drafts::add));
        return drafts;
    }

    private record LowValue(MetricsCalculator.ServiceHours service, double perHour, double share) {
    }

    private static Optional<GrowthSignalRepository.Draft> lowValueServices(MetricsCalculator.Result tenant,
                                                                            double tenantPerHour, YearMonth period) {
        List<LowValue> low = tenant.services().stream()
                .filter(s -> s.completedVisits() >= LOW_VALUE_MIN_VISITS && s.minutes() > 0)
                .map(s -> {
                    double perHour = s.valueMinor() / (s.minutes() / 60.0);
                    return new LowValue(s, perHour, perHour / tenantPerHour);
                })
                .filter(l -> l.share() < LOW_VALUE_SHARE_BELOW)
                .sorted(Comparator.comparingDouble(LowValue::share).thenComparing(l -> l.service().serviceName()))
                .limit(MAX_SERVICES_LISTED)
                .toList();
        if (low.isEmpty()) {
            return Optional.empty();
        }
        List<String> parts = new ArrayList<>();
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("tenantPerHourMinor", tenantPerHour);
        ArrayNode services = payload.putArray("services");
        for (LowValue l : low) {
            parts.add(l.service().serviceName() + " earns " + Math.round(l.share() * 100) + "% of your average per hour over "
                    + l.service().completedVisits() + " visits");
            services.addObject()
                    .put("serviceId", l.service().serviceId().toString())
                    .put("serviceName", l.service().serviceName())
                    .put("visits", l.service().completedVisits())
                    .put("valueMinor", l.service().valueMinor())
                    .put("minutes", l.service().minutes())
                    .put("perHourMinor", l.perHour())
                    .put("share", l.share());
        }
        String json;
        try {
            json = MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        return Optional.of(draft("LOW_VALUE_SERVICES", period,
                "Some services earn little per hour",
                String.join("; ", parts),
                "Review price or duration on those offerings",
                "INFO", "OPEN_SERVICES", json));
    }

    private static Optional<MetricView> sufficient(MetricsCalculator.Result result, String key) {
        return result.metrics().stream()
                .filter(m -> m.key().equals(key) && !m.insufficientData() && m.value() != null)
                .findFirst();
    }

    private static GrowthSignalRepository.Draft draft(String key, YearMonth period, String title, String evidence,
                                                      String action, String severity, String actionType,
                                                      String actionPayload) {
        return new GrowthSignalRepository.Draft(dedupeKey(key, TENANT, period), key, null, period.toString(), title,
                evidence, action, severity, actionType, actionPayload);
    }
}

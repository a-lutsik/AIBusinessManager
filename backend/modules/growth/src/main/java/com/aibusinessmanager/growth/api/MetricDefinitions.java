package com.aibusinessmanager.growth.api;

import java.util.List;

/** Static formula text for the six Metrics Core KPIs (PRODUCT_PLAN §4.5). */
public final class MetricDefinitions {

    private MetricDefinitions() {
    }

    public static List<MetricDefinitionView> all() {
        return List.of(
                new MetricDefinitionView(
                        "SLOT_UTILIZATION",
                        "Slot utilization",
                        "RATIO",
                        "Booked service minutes (occupying visits) / available schedule minutes in the window",
                        1
                ),
                new MetricDefinitionView(
                        "REPEAT_RATE",
                        "Repeat rate",
                        "RATIO",
                        "Clients with ≥2 COMPLETED visits / clients with ≥1 COMPLETED visit in the window",
                        10
                ),
                new MetricDefinitionView(
                        "RETURN_INTERVAL",
                        "Return interval",
                        "DAYS",
                        "Median across clients of each client's median gap between consecutive COMPLETED visits",
                        3
                ),
                new MetricDefinitionView(
                        "NO_SHOW_RATE",
                        "No-show rate",
                        "RATIO",
                        "NO_SHOW / visits that reached start as COMPLETED or NO_SHOW (cancellations excluded)",
                        5
                ),
                new MetricDefinitionView(
                        "REVENUE_PER_HOUR",
                        "Revenue per hour",
                        "CURRENCY_PER_HOUR",
                        "Sum(price_snapshot − discount) over COMPLETED / sum(duration hours) of COMPLETED",
                        1
                ),
                new MetricDefinitionView(
                        "CHURN_RISK",
                        "Churn risk",
                        "RATIO",
                        "Clients past 2× their typical return interval / clients with completed history (rule-based, no ML)",
                        5
                )
        );
    }

    public static String unitFor(String key) {
        return all().stream()
                .filter(d -> d.key().equals(key))
                .map(MetricDefinitionView::unit)
                .findFirst()
                .orElse("NUMBER");
    }
}

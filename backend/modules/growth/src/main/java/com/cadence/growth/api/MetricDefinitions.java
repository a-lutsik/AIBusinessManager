package com.cadence.growth.api;

import java.util.List;

/**
 * Formal definitions of the six Metrics Core KPIs (PRODUCT_PLAN §4.5, ADR 0001 §4). The formula texts are the
 * contract the owner can check a dashboard number against by hand; {@code minSampleSize} is the threshold the
 * calculator compares the sample with ("not enough data" below it).
 *
 * <p>Notation: W = the window's dates in the tenant zone, Wi = [start of the first date, start of the day after the
 * last); "in W" = service start in Wi; T = the as-of instant of the snapshot (its {@code created_at}); facts = visits
 * as the booking journal says at T. A specialist slice applies the same definitions to that specialist's visits.
 */
public final class MetricDefinitions {

    private MetricDefinitions() {
    }

    public static List<MetricDefinitionView> all() {
        return List.of(
                new MetricDefinitionView(
                        "SLOT_UTILIZATION",
                        "Slot utilization",
                        "RATIO",
                        "Minutes of occupying visits (pending, confirmed, completed, no-show) of specialists active at T "
                                + "that fall inside Wi / working minutes of those specialists over the dates of W "
                                + "(weekly schedule; a DAY_OFF exception removes the day, a SHIFT replaces it). "
                                + "Sample: available minutes",
                        1
                ),
                new MetricDefinitionView(
                        "REPEAT_RATE",
                        "Repeat rate",
                        "RATIO",
                        "Clients with ≥1 COMPLETED visit in W who have COMPLETED visits on ≥2 different days (tenant zone) "
                                + "starting before the end of Wi (whole history) / clients with ≥1 COMPLETED visit in W. "
                                + "Visits of one day count once. Sample: those clients",
                        10
                ),
                new MetricDefinitionView(
                        "RETURN_INTERVAL",
                        "Return interval",
                        "DAYS",
                        "For clients with a COMPLETED visit in W and COMPLETED visits on ≥2 different days before the end "
                                + "of Wi: median of the gaps in days between consecutive visit days (tenant-zone dates, "
                                + "visits of one day count once); the value is the median of those per-client medians. "
                                + "Sample: those clients",
                        3
                ),
                new MetricDefinitionView(
                        "NO_SHOW_RATE",
                        "No-show rate",
                        "RATIO",
                        "NO_SHOW / visits in W that started before T in an occupying status (pending, confirmed, "
                                + "completed, no-show; not yet marked visits included, cancellations excluded). "
                                + "Sample: that denominator",
                        5
                ),
                new MetricDefinitionView(
                        "REVENUE_PER_HOUR",
                        "Revenue per hour",
                        "CURRENCY_PER_HOUR",
                        "Sum(price − discount) of COMPLETED visits in W, in minor units of the tenant currency / their "
                                + "summed duration in hours. Sample: completed minutes",
                        1
                ),
                new MetricDefinitionView(
                        "CHURN_RISK",
                        "Churn risk",
                        "RATIO",
                        "At T: clients with a COMPLETED visit in [T − 365 days, T] whose days since the last COMPLETED "
                                + "visit reach 2× their typical interval (median gap between their COMPLETED visit days, visits "
                                + "of one day counting once, or the tenant median of those medians for clients with one "
                                + "visit day; 1.5× is a warning) / "
                                + "those clients, excluding clients without a typical interval. Rule-based, no ML. "
                                + "Sample: clients evaluated",
                        5
                )
        );
    }

    public static String unitFor(String key) {
        return find(key).map(MetricDefinitionView::unit).orElse("NUMBER");
    }

    /** The minimum sample of {@code key}; an unknown key is a programming error. */
    public static int minSampleSize(String key) {
        return find(key).map(MetricDefinitionView::minSampleSize)
                .orElseThrow(() -> new IllegalArgumentException("Unknown metric " + key));
    }

    private static java.util.Optional<MetricDefinitionView> find(String key) {
        return all().stream().filter(d -> d.key().equals(key)).findFirst();
    }
}

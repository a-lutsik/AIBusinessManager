package com.cadence.ai.internal.tools;

import com.cadence.ai.internal.llm.ToolSpec;
import com.cadence.ai.internal.privacy.RefKind;
import com.cadence.growth.api.GrowthService;
import com.cadence.growth.api.MetricView;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Numbers straight from {@link GrowthService}, {@code insufficientData} included. {@code explanation} is built by
 * {@code MetricsCalculator} from counts only, so it goes as is: masking would turn "4800 / 16000 minutes" into a phone.
 */
@Component
class GetMetricsTool implements AiTool {

    private static final ToolSpec SPEC = ToolArgs.spec("get_metrics",
            "Current Metrics Core KPIs of the business or of one master, with window, sample size and per-master breakdown."
                    + " The only source of business numbers. REVENUE_PER_HOUR is in minor units of the currency per hour.",
            """
            {"type":"object","additionalProperties":false,"properties":{
              "masterRef":{"type":"string","description":"MASTER_n"}}}
            """);

    private final GrowthService growth;

    GetMetricsTool(GrowthService growth) {
        this.growth = growth;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResults.Result execute(ToolContext context, JsonNode args) {
        UUID master = context.resolveOptional(args, "masterRef", RefKind.MASTER);
        List<MetricView> metrics = growth.currentMetrics(master);
        return new ToolResults.Metrics(context.inOneTransaction(() -> metrics.stream()
                .map(metric -> new ToolResults.Metric(
                        metric.key(),
                        metric.value(),
                        metric.unit(),
                        metric.sampleSize(),
                        metric.insufficientData(),
                        metric.explanation(),
                        text(metric.windowStart()),
                        text(metric.windowEnd()),
                        metric.deltaMoM(),
                        breakdown(context, metric)))
                .toList()));
    }

    private static List<ToolResults.MasterValue> breakdown(ToolContext context, MetricView metric) {
        List<MetricView.SpecialistBreakdown> rows = metric.breakdownBySpecialist();
        return rows == null ? List.of() : rows.stream()
                .map(row -> new ToolResults.MasterValue(
                        context.masterRef(row.specialistId(), row.specialistName()), row.value(), row.insufficientData()))
                .toList();
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }
}

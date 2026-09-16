package com.cadence.growth.api;

public record MetricDefinitionView(
        String key,
        String title,
        String unit,
        String formula,
        int minSampleSize
) {
}

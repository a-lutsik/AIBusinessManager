package com.aibusinessmanager.growth.api;

public record MetricDefinitionView(
        String key,
        String title,
        String unit,
        String formula,
        int minSampleSize
) {
}

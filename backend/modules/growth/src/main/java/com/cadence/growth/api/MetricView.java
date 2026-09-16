package com.cadence.growth.api;

public record MetricView(
        String key,
        Double value,
        int sampleSize,
        boolean insufficientData,
        String explanation,
        java.util.UUID specialistId
) {
}

package com.cadence.growth.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record MetricView(
        String key,
        Double value,
        int sampleSize,
        boolean insufficientData,
        String explanation,
        UUID specialistId,
        LocalDate windowStart,
        LocalDate windowEnd,
        String unit,
        Double deltaMoM,
        List<SpecialistBreakdown> breakdownBySpecialist
) {
    public record SpecialistBreakdown(
            UUID specialistId,
            String specialistName,
            Double value,
            boolean insufficientData
    ) {
    }

    public static MetricView basic(
            String key,
            Double value,
            int sampleSize,
            boolean insufficientData,
            String explanation,
            UUID specialistId
    ) {
        return new MetricView(
                key,
                value,
                sampleSize,
                insufficientData,
                explanation,
                specialistId,
                null,
                null,
                MetricDefinitions.unitFor(key),
                null,
                List.of()
        );
    }

    public MetricView withWindow(LocalDate start, LocalDate end) {
        return new MetricView(
                key, value, sampleSize, insufficientData, explanation, specialistId,
                start, end, unit != null ? unit : MetricDefinitions.unitFor(key), deltaMoM, breakdownBySpecialist
        );
    }

    public MetricView withDeltaMoM(Double delta) {
        return new MetricView(
                key, value, sampleSize, insufficientData, explanation, specialistId,
                windowStart, windowEnd, unit, delta, breakdownBySpecialist
        );
    }

    public MetricView withBreakdown(List<SpecialistBreakdown> breakdown) {
        return new MetricView(
                key, value, sampleSize, insufficientData, explanation, specialistId,
                windowStart, windowEnd, unit, deltaMoM, breakdown == null ? List.of() : List.copyOf(breakdown)
        );
    }
}

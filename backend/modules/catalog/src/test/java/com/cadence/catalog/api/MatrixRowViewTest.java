package com.cadence.catalog.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MatrixRowViewTest {

    @Test
    void exposesDefaultsAndOverrides() {
        UUID specialistId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        MatrixRowView row = new MatrixRowView(
                null,
                specialistId,
                serviceId,
                "Cut",
                "#059669",
                false,
                60,
                5000,
                5,
                10,
                45,
                null,
                null,
                15
        );
        assertEquals("Cut", row.serviceName());
        assertEquals(60, row.defaultDurationMinutes());
        assertEquals(45, row.durationMinutesOverride());
        assertFalse(row.offered());
    }
}

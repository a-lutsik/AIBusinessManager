package com.aibusinessmanager.app.web.dto;

import com.aibusinessmanager.booking.api.AppointmentView;
import com.aibusinessmanager.retention.api.RetentionService;
import com.aibusinessmanager.retention.api.TrustView;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record MasterTodayView(
        Instant asOf,
        LocalDate day,
        UUID specialistId,
        List<Item> appointments
) {
    public record Item(
            AppointmentView appointment,
            TrustView trust,
            List<RetentionService.PackageView> packages
    ) {
    }
}

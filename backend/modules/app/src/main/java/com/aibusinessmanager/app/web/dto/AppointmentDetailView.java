package com.aibusinessmanager.app.web.dto;

import com.aibusinessmanager.booking.api.AppointmentView;
import com.aibusinessmanager.retention.api.TrustView;

public record AppointmentDetailView(
        AppointmentView appointment,
        TrustView trust
) {
}

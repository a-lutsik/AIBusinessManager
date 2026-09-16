package com.cadence.booking.api;

import java.util.UUID;

public record PublicBookingResult(
        AppointmentView appointment,
        String accessToken,
        String managePath
) {
}

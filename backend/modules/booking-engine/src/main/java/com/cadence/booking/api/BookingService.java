package com.cadence.booking.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public booking-engine port — sole source of truth for time occupancy. */
public interface BookingService {

    String moduleName();

    List<Instant> freeSlots(UUID specialistId, UUID serviceId, LocalDate from, LocalDate to);

    AppointmentView create(CreateBookingCommand command);

    PublicBookingResult createPublic(CreateBookingCommand command);

    List<AppointmentView> calendar(Instant from, Instant to, UUID specialistId);

    Optional<AppointmentView> find(UUID appointmentId);

    AppointmentView transition(UUID appointmentId, AppointmentStatus target, String note);

    AppointmentView reschedule(UUID appointmentId, Instant newStart);

    Optional<AppointmentView> findByAccessToken(String rawToken);

    AppointmentView cancelByAccessToken(String rawToken, boolean byClient);

    AppointmentView rescheduleByAccessToken(String rawToken, Instant newStart);

    String ics(UUID appointmentId);

    String icsByAccessToken(String rawToken);

    /**
     * Issues a fresh client manage link for a visit (OWNER only). Each call mints a new token; only its hash is
     * stored, so the raw value is returned here once and cannot be recovered later.
     */
    AccessLinkView issueAccessLink(UUID appointmentId);

    /**
     * Moves every visit of client {@code from} to client {@code to} within the current tenant (client merge) and
     * returns how many visits moved. Touches only booking-engine tables.
     */
    int reassignClient(UUID from, UUID to);
}

package com.aibusinessmanager.booking.api;

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

    Optional<String> rawTokenFor(UUID appointmentId);
}

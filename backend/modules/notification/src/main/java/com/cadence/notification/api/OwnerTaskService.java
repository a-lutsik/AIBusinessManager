package com.cadence.notification.api;

import java.util.List;
import java.util.UUID;

public interface OwnerTaskService {

    OwnerTaskView create(
            String kind,
            String title,
            String body,
            String copyText,
            String link,
            UUID appointmentId,
            UUID clientId
    );

    List<OwnerTaskView> listOpen();

    void complete(UUID taskId);

    /**
     * Completes the open tasks of {@code kind} about one visit (e.g. {@code MASTER_PHONE_BOOKING} once the owner has
     * confirmed or anyone has cancelled that visit). Returns how many were closed; none open is not an error.
     */
    int completeForAppointment(UUID appointmentId, String kind);

    /**
     * Client merge: points the owner tasks of client {@code from} at client {@code to}. Returns how many moved.
     */
    int reassignClient(UUID from, UUID to);
}

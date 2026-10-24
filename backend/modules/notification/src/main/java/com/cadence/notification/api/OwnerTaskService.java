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
     * Client merge: points the owner tasks of client {@code from} at client {@code to}. Returns how many moved.
     */
    int reassignClient(UUID from, UUID to);
}

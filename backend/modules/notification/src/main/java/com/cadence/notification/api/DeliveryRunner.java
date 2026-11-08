package com.cadence.notification.api;

/** One pass of the delivery worker; the scheduler calls it, tests call it to run deliveries on demand. */
public interface DeliveryRunner {

    /** Delivers up to one batch of queued messages and returns how many were handled. */
    int runOnce();
}

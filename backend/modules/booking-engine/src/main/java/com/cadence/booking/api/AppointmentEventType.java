package com.cadence.booking.api;

/**
 * Types of the booking journal ({@code appointment_event.event_type}, stored as {@link #name()}). ADR 0001: the
 * journal is self-sufficient, every change of a visit fact is one event. {@code MASTER_CHANGED},
 * {@code SERVICE_CHANGED} and {@code PRICE_CHANGED} are reserved (no write path emits them yet, the fold refuses
 * them); {@code REMINDER_SENT} does not change any fact.
 */
public enum AppointmentEventType {
    CREATED,
    CONFIRMED,
    RESCHEDULED,
    CLIENT_CHANGED,
    COMPLETED,
    NO_SHOW,
    CANCELLED,
    MASTER_CHANGED,
    SERVICE_CHANGED,
    PRICE_CHANGED,
    REMINDER_SENT
}

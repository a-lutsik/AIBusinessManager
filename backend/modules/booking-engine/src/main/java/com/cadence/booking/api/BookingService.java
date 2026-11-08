package com.cadence.booking.api;

import com.cadence.notification.api.TelegramLink;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public booking-engine port — sole source of truth for time occupancy. */
public interface BookingService {

    String moduleName();

    /** {@code INVALID_SLOT_RANGE}, 400, when {@code to} is before {@code from} or the range spans more than 62 days. */
    List<Instant> freeSlots(UUID specialistId, UUID serviceId, LocalDate from, LocalDate to);

    /**
     * Creates a console visit; a {@code clientId} of an anonymized client is {@code CLIENT_ANONYMIZED}, 409. No role creates a visit already COMPLETED or NO_SHOW before its start
     * ({@code VISIT_NOT_STARTED}, 409). A MASTER books a {@code clientId} only within their scope (a non-cancelled
     * visit with them that is not hidden from them; otherwise {@code FORBIDDEN_RESOURCE}, 403). When a master books by
     * phone, the response carries the name the master typed and no stored name, phone or trust; if the client behind
     * the phone is not already in the master's scope (an existing client or one created now, the answer is the same)
     * the visit is PENDING whatever was asked for, {@code clientHiddenFromMaster}, and an owner task
     * {@code MASTER_PHONE_BOOKING} asks the owner to confirm it (decision A). The visit stores the typed name only.
     */
    AppointmentView create(CreateBookingCommand command);

    /** Public booking; the visit in the result carries no stored client name, phone or trust (as the token methods). */
    PublicBookingResult createPublic(CreateBookingCommand command);

    /**
     * Console views of visits ({@link #find} too): the stored client name, phone and trust, and for anyone but a MASTER
     * the "typed / stored" hint in {@code enteredName} when the typed name differs. A MASTER gets a visit hidden from
     * them with the typed name only, no stored name, phone or trust, until the owner confirms it.
     */
    List<AppointmentView> calendar(Instant from, Instant to, UUID specialistId);

    Optional<AppointmentView> find(UUID appointmentId);

    /**
     * Distinct clients with at least one non-cancelled visit with {@code specialistId} that is not hidden from the
     * master: neither a cancelled visit nor an unconfirmed phone booking of the master gives access to the client. A
     * MASTER always gets their own clients, whatever id is passed.
     */
    List<UUID> clientIdsOf(UUID specialistId);

    /**
     * All visits of a client, oldest first, without a time window. A MASTER sees only their own non-cancelled visits
     * with the client that are not hidden from them, so an empty list means the client is outside that master's scope.
     */
    List<AppointmentView> clientHistory(UUID clientId);

    /**
     * Moves a visit through the status machine; COMPLETED and NO_SHOW only once it has started, an owner correction
     * of a row settled before its start included ({@code VISIT_NOT_STARTED}, 409, with an explicit message for that
     * legacy case). A MASTER cannot confirm a visit hidden from them (403); the OWNER's confirmation clears the flag,
     * a cancellation keeps it. On a visit of an anonymized client the transition counts as usual and {@code note} is dropped.
     */
    AppointmentView transition(UUID appointmentId, AppointmentStatus target, String note);

    /** {@code CLIENT_ANONYMIZED}, 409, for a visit of an anonymized client. */
    AppointmentView reschedule(UUID appointmentId, Instant newStart);

    /**
     * The visit behind a public manage link. The three token methods answer with visit facts and what the visitor
     * typed ({@code enteredName}, also as {@code clientDisplayName}); never the stored client name, phone or trust.
     */
    Optional<AppointmentView> findByAccessToken(String rawToken);

    /**
     * The manage page: the visit as {@link #findByAccessToken} shows it and the tenant's reschedule rule, for the page
     * to know before the client picks a slot. Like every token method with an {@code appointmentId}: an unknown or
     * expired token is 404, a token of another visit is {@code TOKEN_INVALID}, 403, and nothing is written.
     */
    PublicVisit publicVisitByAccessToken(UUID appointmentId, String rawToken);

    PublicVisit cancelByAccessToken(UUID appointmentId, String rawToken, boolean byClient);

    /** {@code RESCHEDULE_DISABLED}, 403, when the tenant's rule forbids it. */
    PublicVisit rescheduleByAccessToken(UUID appointmentId, String rawToken, Instant newStart);

    /**
     * Withdraws the marketing consent of the client behind a manage link (PRODUCT_PLAN §10), audited with source
     * {@code public-link}; returns the visit as the other token methods do. The token must belong to
     * {@code appointmentId} ({@code TOKEN_INVALID}, 403, nothing written); an unknown or expired token is 404.
     */
    AppointmentView withdrawConsentByAccessToken(UUID appointmentId, String rawToken);

    /**
     * The facts a client message about a visit needs, read under a share lock so the visit cannot change until the caller's
     * transaction ends, with no actor check (system callers: outbox handler, delivery guard); empty when the visit does not
     * exist in the current tenant. Needs a read-write transaction.
     */
    Optional<VisitNotice> visitNotice(UUID appointmentId);

    /** CONFIRMED visits whose service start lies in {@code (from, to]}, soonest first, for the reminder job (system caller). */
    List<VisitNotice> confirmedVisitsStartingBetween(Instant from, Instant to);

    /**
     * One-time Telegram deep link for the client behind a manage link, for the booking-success page. The token must belong to
     * {@code appointmentId} ({@code TOKEN_INVALID}, 403); an unknown or expired token is 404. The answer is the same whether
     * or not the client already has a chat (no oracle). A link alone binds nothing: the bot makes the person prove they own
     * the client's phone number by sharing their contact. {@code TELEGRAM_DISABLED}, 409, when the channel is off.
     */
    TelegramLink telegramLinkByAccessToken(UUID appointmentId, String rawToken);

    String ics(UUID appointmentId);

    String icsByAccessToken(String rawToken);

    /**
     * Issues a fresh client manage link for a visit (OWNER only). Each call mints a new token; only its hash is
     * stored, so the raw value is returned here once and cannot be recovered later. {@code CLIENT_ANONYMIZED}, 409, for a
     * visit of an anonymized client.
     */
    AccessLinkView issueAccessLink(UUID appointmentId);

    /**
     * Booking half of erasing a client: refuses while a pending or confirmed visit of the client has not ended
     * ({@code CLIENT_HAS_UPCOMING_VISITS}, 409), then clears the typed name and the note of every visit and revokes all their
     * manage links. The visit facts and the journal stay. Runs after the client row is locked. Returns the visits touched.
     */
    int eraseClientVisits(UUID clientId);

    /**
     * Moves every visit of client {@code from} to client {@code to} within the current tenant (client merge) and
     * returns how many visits moved. Touches only booking-engine tables.
     */
    int reassignClient(UUID from, UUID to);
}

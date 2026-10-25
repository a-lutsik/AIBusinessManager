package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientMessagesTest {

    private final ClientMessages messages = new ClientMessages(TestMessages.catalog());

    @Test
    void confirmedMessageEndsWithPlaceholder() {
        String text = messages.bookingConfirmation(AppointmentStatus.CONFIRMED, "en");
        assertEquals("Your visit is confirmed. Manage it here: {manageLink}", text);
    }

    @Test
    void pendingMessageEndsWithPlaceholderAndDiffers() {
        String pending = messages.bookingConfirmation(AppointmentStatus.PENDING, "en");
        assertTrue(pending.endsWith(ClientMessages.MANAGE_LINK_PLACEHOLDER));
        assertTrue(pending.contains("waiting for confirmation"));
        assertNotEquals(pending, messages.bookingConfirmation(AppointmentStatus.CONFIRMED, "en"));
    }

    @Test
    void textFollowsTheClientLocaleAndKeepsThePlaceholder() {
        String ru = messages.bookingConfirmation(AppointmentStatus.CONFIRMED, "ru");
        assertEquals("Ваш визит подтверждён. Управление: {manageLink}", ru);
        // A regional tag selects the language.
        assertEquals(ru, messages.bookingConfirmation(AppointmentStatus.CONFIRMED, "ru-RU"));
    }

    @Test
    void unknownMissingOrUnsupportedLocaleFallsBackToEnglish() {
        String english = messages.bookingConfirmation(AppointmentStatus.CONFIRMED, "en");
        assertEquals(english, messages.bookingConfirmation(AppointmentStatus.CONFIRMED, null));
        assertEquals(english, messages.bookingConfirmation(AppointmentStatus.CONFIRMED, ""));
        assertEquals(english, messages.bookingConfirmation(AppointmentStatus.CONFIRMED, "de"));
        // Supported language without a catalog entry in this fixture (ka) also ends on English.
        assertEquals(english, messages.bookingConfirmation(AppointmentStatus.CONFIRMED, "ka"));
    }

    @Test
    void messagesNeverCarryATokenOrPath() {
        for (AppointmentStatus status : AppointmentStatus.values()) {
            for (String locale : new String[] {"en", "ru", null}) {
                String text = messages.bookingConfirmation(status, locale);
                assertFalse(text.contains("token="), text);
                assertFalse(text.contains("/b/"), text);
            }
        }
    }

    @Test
    void placeholderLiteralIsStableContractWithConsole() {
        assertEquals("{manageLink}", ClientMessages.MANAGE_LINK_PLACEHOLDER);
    }
}

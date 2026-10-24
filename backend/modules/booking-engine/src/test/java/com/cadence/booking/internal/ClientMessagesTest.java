package com.cadence.booking.internal;

import com.cadence.booking.api.AppointmentStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientMessagesTest {

    @Test
    void confirmedMessageEndsWithPlaceholder() {
        String text = ClientMessages.bookingConfirmation(AppointmentStatus.CONFIRMED);
        assertEquals("Your visit is confirmed. Manage it here: {manageLink}", text);
    }

    @Test
    void pendingMessageEndsWithPlaceholderAndDiffers() {
        String pending = ClientMessages.bookingConfirmation(AppointmentStatus.PENDING);
        assertTrue(pending.endsWith(ClientMessages.MANAGE_LINK_PLACEHOLDER));
        assertTrue(pending.contains("waiting for confirmation"));
        assertNotEquals(pending, ClientMessages.bookingConfirmation(AppointmentStatus.CONFIRMED));
    }

    @Test
    void messagesNeverCarryATokenOrPath() {
        for (AppointmentStatus status : AppointmentStatus.values()) {
            String text = ClientMessages.bookingConfirmation(status);
            assertFalse(text.contains("token="), text);
            assertFalse(text.contains("/b/"), text);
        }
    }

    @Test
    void placeholderLiteralIsStableContractWithConsole() {
        assertEquals("{manageLink}", ClientMessages.MANAGE_LINK_PLACEHOLDER);
    }
}

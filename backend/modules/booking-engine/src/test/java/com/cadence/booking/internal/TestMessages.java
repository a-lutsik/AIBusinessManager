package com.cadence.booking.internal;

import com.cadence.platform.i18n.MessageCatalog;
import org.springframework.context.support.StaticMessageSource;

import java.util.Locale;

/**
 * Message catalog for booking-engine unit tests. The real {@code messages_*.properties} live in the app module (their
 * parity and wording are tested there); these fixtures only need the keys the booking code asks for, with English and
 * Russian texts so locale selection is observable.
 */
final class TestMessages {

    private TestMessages() {
    }

    static MessageCatalog catalog() {
        return catalog("en");
    }

    static MessageCatalog catalog(String ownerLocale) {
        StaticMessageSource source = new StaticMessageSource();
        source.setAlwaysUseMessageFormat(true);
        add(source, Locale.ENGLISH, "Your visit is confirmed. Manage it here: {0}",
                "Your visit is waiting for confirmation. Manage it here: {0}", "New booking", "Master {0} booked", "Body");
        add(source, Locale.of("ru"), "Ваш визит подтверждён. Управление: {0}",
                "Ваш визит ожидает подтверждения. Управление: {0}", "Новая запись", "Мастер {0} записал", "Текст");
        return new MessageCatalog(source, ownerLocale);
    }

    private static void add(StaticMessageSource source, Locale locale, String confirmed, String pending, String newBooking,
                            String masterTitle, String masterBody) {
        source.addMessage("client.booking.confirmed", locale, confirmed);
        source.addMessage("client.booking.pending", locale, pending);
        source.addMessage("owner.task.newBooking.title", locale, newBooking);
        source.addMessage("owner.task.masterPhoneBooking.title", locale, masterTitle);
        source.addMessage("owner.task.masterPhoneBooking.body", locale, masterBody);
    }
}

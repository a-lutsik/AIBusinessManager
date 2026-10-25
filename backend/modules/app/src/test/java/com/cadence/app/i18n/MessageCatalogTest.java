package com.cadence.app.i18n;

import com.cadence.platform.i18n.MessageCatalog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real {@code messages_*.properties} catalogs resolved through {@link MessageCatalog}. */
class MessageCatalogTest {

    private static final String PLACEHOLDER = "{manageLink}";
    private static final Pattern ARGUMENT = Pattern.compile("\\{(\\d+)}");

    private final MessageCatalog catalog = new MessageCatalog(MessageCatalog.newMessageSource(), "en");

    @Test
    void clientConfirmationIsWrittenInTheClientsLocale() {
        String en = catalog.forClient("en", "client.booking.confirmed", PLACEHOLDER);
        String ru = catalog.forClient("ru", "client.booking.confirmed", PLACEHOLDER);
        String ka = catalog.forClient("ka", "client.booking.confirmed", PLACEHOLDER);
        String uk = catalog.forClient("uk", "client.booking.confirmed", PLACEHOLDER);
        String cs = catalog.forClient("cs", "client.booking.confirmed", PLACEHOLDER);

        assertEquals("Your visit is confirmed. Manage it here: {manageLink}", en);
        assertEquals("Ваш визит подтверждён. Управление записью: {manageLink}", ru);
        assertEquals(5, Set.of(en, ru, ka, uk, cs).size(), "five locales, five different texts");
        for (String text : Set.of(en, ru, ka, uk, cs)) {
            assertTrue(text.endsWith(PLACEHOLDER), text);
        }
    }

    @Test
    void regionalTagsAndCaseSelectTheLanguage() {
        String ru = catalog.forClient("ru", "client.booking.pending", PLACEHOLDER);
        assertEquals(ru, catalog.forClient("ru-RU", "client.booking.pending", PLACEHOLDER));
        assertEquals(ru, catalog.forClient("RU", "client.booking.pending", PLACEHOLDER));
        assertEquals(ru, catalog.forClient("ru_RU", "client.booking.pending", PLACEHOLDER));
    }

    @Test
    void missingOrUnsupportedClientLocaleFallsBackToEnglish() {
        String en = catalog.forClient("en", "client.booking.pending", PLACEHOLDER);
        assertEquals(en, catalog.forClient(null, "client.booking.pending", PLACEHOLDER));
        assertEquals(en, catalog.forClient("  ", "client.booking.pending", PLACEHOLDER));
        assertEquals(en, catalog.forClient("de", "client.booking.pending", PLACEHOLDER));
        assertEquals(en, catalog.forClient("xx-YY", "client.booking.pending", PLACEHOLDER));
    }

    @Test
    void ownerTextsUseTheConfiguredOwnerLocaleNotTheClientsOne() {
        MessageCatalog english = new MessageCatalog(MessageCatalog.newMessageSource(), "en");
        MessageCatalog russian = new MessageCatalog(MessageCatalog.newMessageSource(), "ru");

        assertEquals("New booking — send confirmation manually", english.forOwner("owner.task.newBooking.title"));
        assertEquals("Новая запись — отправьте подтверждение вручную", russian.forOwner("owner.task.newBooking.title"));
        assertEquals("Master Nino booked a client by phone — confirm",
                english.forOwner("owner.task.masterPhoneBooking.title", "Nino"));
        assertEquals("Мастер Nino записал клиента по телефону — подтвердите",
                russian.forOwner("owner.task.masterPhoneBooking.title", "Nino"));
        assertEquals("Send message to client", english.forOwner("owner.task.manualMessage.title"));
    }

    @Test
    void ownerLocaleMustBeASupportedLanguage() {
        assertThrows(IllegalStateException.class, () -> new MessageCatalog(MessageCatalog.newMessageSource(), "de"));
        assertThrows(IllegalStateException.class, () -> new MessageCatalog(MessageCatalog.newMessageSource(), ""));
        assertEquals("Pošlete zprávu klientovi",
                new MessageCatalog(MessageCatalog.newMessageSource(), "cs-CZ").forOwner("owner.task.manualMessage.title"));
    }

    @Test
    void aKeyMissingFromEveryCatalogFailsFast() {
        assertThrows(IllegalStateException.class, () -> catalog.forClient("ru", "no.such.key"));
        assertThrows(IllegalStateException.class, () -> catalog.forOwner("no.such.key"));
    }

    @Test
    void apostropheInATextSurvivesMessageFormat() {
        MessageCatalog ukrainian = new MessageCatalog(MessageCatalog.newMessageSource(), "uk");
        String body = ukrainian.forOwner("owner.task.masterPhoneBooking.body");
        assertTrue(body.contains("ім'я"), body);
    }

    @Test
    void everyLocaleCarriesTheSameKeysAndTheSameArguments() throws IOException {
        Map<String, Properties> byLocale = new java.util.LinkedHashMap<>();
        for (String language : MessageCatalog.SUPPORTED_LANGUAGES) {
            byLocale.put(language, load(language));
        }
        Set<String> english = new TreeSet<>(byLocale.get("en").stringPropertyNames());
        for (Map.Entry<String, Properties> entry : byLocale.entrySet()) {
            assertEquals(english, new TreeSet<>(entry.getValue().stringPropertyNames()), "keys of " + entry.getKey());
            for (String key : english) {
                assertEquals(arguments(byLocale.get("en").getProperty(key)), arguments(entry.getValue().getProperty(key)),
                        "arguments of " + key + " in " + entry.getKey());
            }
        }
    }

    private static Set<String> arguments(String template) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = ARGUMENT.matcher(template);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static Properties load(String language) throws IOException {
        try (InputStream in = MessageCatalogTest.class.getResourceAsStream("/messages_" + language + ".properties")) {
            assertTrue(in != null, "messages_" + language + ".properties is on the classpath");
            Properties properties = new Properties();
            properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        }
    }
}

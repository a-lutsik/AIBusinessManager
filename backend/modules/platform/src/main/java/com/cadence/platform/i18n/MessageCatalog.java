package com.cadence.platform.i18n;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Server-side texts that leave the system or land on an owner task, resolved from {@code messages_<locale>.properties}.
 *
 * <p>Two audiences, two locale rules (product plan section 9):
 * <ul>
 *   <li>client-facing text uses the client's {@code preferred_locale};</li>
 *   <li>owner-facing text (task titles, hints) uses the configured owner locale ({@code app.i18n.owner-locale},
 *       default {@code en}), because no owner locale exists yet and the interface locale of the console is a
 *       client-side setting the backend does not see.</li>
 * </ul>
 * Lookup chain: requested locale, then {@link #DEFAULT_LOCALE}; a key missing from the default catalog is a
 * programming error and fails fast. Every message goes through {@link java.text.MessageFormat} (also without
 * arguments), so a literal apostrophe is written {@code ''} and arguments are {@code {0}}, {@code {1}}.
 * Counts are not formatted here: a message with a count needs a catalog design of its own (CLDR plural rules), and
 * no current server text carries one.
 */
@Component
public class MessageCatalog {

    public static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

    /** The five product locales; adding one is a new {@code messages_xx.properties}, not a code change. */
    public static final List<String> SUPPORTED_LANGUAGES = List.of("en", "ru", "ka", "uk", "cs");

    private final MessageSource messages;
    private final Locale ownerLocale;

    public MessageCatalog(MessageSource messageSource, @Value("${app.i18n.owner-locale:en}") String ownerLocale) {
        this.messages = messageSource;
        Locale parsed = parse(ownerLocale);
        if (parsed == null) {
            throw new IllegalStateException(
                    "app.i18n.owner-locale '" + ownerLocale + "' is not one of " + SUPPORTED_LANGUAGES);
        }
        this.ownerLocale = parsed;
    }

    /** The shared bundle definition: UTF-8 files, no fallback to the server's OS locale. */
    public static ResourceBundleMessageSource newMessageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setAlwaysUseMessageFormat(true);
        return source;
    }

    /** Text for a client, in the client's preferred locale; unknown or absent locale falls back to English. */
    public String forClient(String localeTag, String key, Object... args) {
        return resolve(localeOrDefault(localeTag), key, args);
    }

    /** Text for the owner (task titles and hints), in the configured owner locale. */
    public String forOwner(String key, Object... args) {
        return resolve(ownerLocale, key, args);
    }

    /** Language of a locale tag ({@code ru-RU}, {@code uk_UA}, {@code KA}) when it is a supported one, else English. */
    public static Locale localeOrDefault(String localeTag) {
        Locale parsed = parse(localeTag);
        return parsed == null ? DEFAULT_LOCALE : parsed;
    }

    private static Locale parse(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        String language = Locale.forLanguageTag(tag.trim().replace('_', '-')).getLanguage();
        return SUPPORTED_LANGUAGES.contains(language) ? Locale.of(language) : null;
    }

    private String resolve(Locale locale, String key, Object[] args) {
        String text = messages.getMessage(key, args, null, locale);
        if (text == null && !DEFAULT_LOCALE.equals(locale)) {
            text = messages.getMessage(key, args, null, DEFAULT_LOCALE);
        }
        if (text == null) {
            throw new IllegalStateException("Message '" + key + "' is missing from the default catalog");
        }
        return text;
    }
}

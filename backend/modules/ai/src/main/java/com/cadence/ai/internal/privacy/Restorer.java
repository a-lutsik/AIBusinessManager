package com.cadence.ai.internal.privacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts display names back into a masked text for the owner (ADR 0002 §3.4): every {@code KIND_n} token of the
 * conversation becomes its display. Stored texts stay masked; this runs on every read. A token the conversation does
 * not know stays as it is and is logged (kinds and count only, never the text).
 */
@Component
public class Restorer {

    private static final Logger log = LoggerFactory.getLogger(Restorer.class);
    /** {@code \b(KIND)_(\d+)\b} with ASCII boundaries, so "CLIENT_1" never matches inside "CLIENT_10" or "XCLIENT_1". */
    private static final Pattern TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_])(CLIENT|MASTER|VISIT|SERVICE|DRAFT)_[1-9][0-9]{0,8}(?![A-Za-z0-9_])");

    private final PseudonymRegistry registry;

    public Restorer(PseudonymRegistry registry) {
        this.registry = registry;
    }

    public String restore(UUID conversationId, String text) {
        if (text == null) {
            return null;
        }
        Matcher m = TOKEN.matcher(text);
        if (!m.find()) {
            return text;
        }
        return restore(conversationId, text, m, registry.displays(conversationId));
    }

    /** {@link #restore} for many texts of one conversation with a single read of its map (the first time it is needed). */
    public UnaryOperator<String> forConversation(UUID conversationId) {
        return new UnaryOperator<>() {
            private Map<String, String> displays;

            @Override
            public String apply(String text) {
                if (text == null) {
                    return null;
                }
                Matcher m = TOKEN.matcher(text);
                if (!m.find()) {
                    return text;
                }
                if (displays == null) {
                    displays = registry.displays(conversationId);
                }
                return restore(conversationId, text, m, displays);
            }
        };
    }

    /** {@code m} stands on the first token of {@code text}. */
    private static String restore(UUID conversationId, String text, Matcher m, Map<String, String> displays) {
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        int unknown = 0;
        TreeSet<String> unknownKinds = new TreeSet<>();
        do {
            String display = displays.get(m.group());
            if (display == null) {
                unknown++;
                unknownKinds.add(m.group(1));
                continue;
            }
            out.append(text, copied, m.start()).append(display);
            copied = m.end();
        } while (m.find());
        if (unknown > 0) {
            log.warn("Restorer kept {} unknown pseudonym token(s) of kind(s) {} in conversation {}",
                    unknown, unknownKinds, conversationId);
        }
        return out.append(text, copied, text.length()).toString();
    }
}

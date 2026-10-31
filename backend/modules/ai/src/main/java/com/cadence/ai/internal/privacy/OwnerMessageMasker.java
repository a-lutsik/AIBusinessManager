package com.cadence.ai.internal.privacy;

import com.cadence.crm.api.CrmService;
import com.cadence.crm.api.NameWords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;

/**
 * Masks the owner's chat message before it is stored or sent to the LLM (ADR 0002 §3.3).
 *
 * <p>A word (maximal run of Unicode letters) names a person when it matches ({@link NameWords#matches}: ignoring case and
 * accents, inflected forms included) a word of the display name of a master ({@link Masters}, every master of the tenant) or client ({@link Clients}, one query with all
 * the words of the message). Adjacent words (only spaces, a hyphen or an underscore between) are one span while they keep
 * naming a common person, so "Anna Petrova" is one client even when "Anna" alone is ambiguous. A word of one or two letters
 * counts alone only when capitalised ("Спроси Ли" but not "пришла ли она"); inside a full name it counts in any case ("ли
 * ян"). A span naming one person becomes its ref ({@code CLIENT_7}), two to five people {@code (MASTER_1|CLIENT_9)}
 * (masters first), more than five {@code [name:many]} (no refs created). A name that matches nobody, such as an alternating form
 * ("Льва" for "Лев"), stays as typed (accepted risk TD-36).
 *
 * <p>Fails closed: a result of {@link CrmService#NAME_WORD_MATCH_LIMIT} rows may hide clients, so every name-like word of
 * that message becomes {@code [name:many]}.
 *
 * <p>Refs of this conversation typed by the owner stay, other token-shaped text becomes {@code [ref]}. The patterns of
 * {@link FreeTextMasker#maskPatterns} (phones, emails, URLs, addresses) run before the query, so no contact detail reaches
 * the CRM and no ref is made from the letters of an email; the stop-word pass is NOT applied: the owner must be able to ask
 * "which clients have an allergy note?" and their own words are not a client record. Refs are created through
 * {@link PseudonymRegistry#refFor}, each in its own short transaction.
 */
@Component
public class OwnerMessageMasker {

    private static final Logger log = LoggerFactory.getLogger(OwnerMessageMasker.class);

    public record Person(UUID id, String displayName) {
    }

    /** Every master of the tenant whose name may appear in a message. */
    @FunctionalInterface
    public interface Masters {
        List<Person> all();
    }

    /** {@link CrmService#findByNameWords}: only the id and name of the clients bearing one of the words. */
    @FunctionalInterface
    public interface Clients {
        List<Person> findByNameWords(Set<String> words);
    }

    private record Candidates(Map<UUID, Person> masters, Map<UUID, Person> clients) {
        boolean isEmpty() {
            return masters.isEmpty() && clients.isEmpty();
        }

        Candidates bearing(Candidates other) {
            Map<UUID, Person> m = new LinkedHashMap<>(masters);
            m.keySet().retainAll(other.masters.keySet());
            Map<UUID, Person> c = new LinkedHashMap<>(clients);
            c.keySet().retainAll(other.clients.keySet());
            return new Candidates(m, c);
        }
    }

    private static final Candidates NONE = new Candidates(Map.of(), Map.of());

    private final Masters masters;
    private final Clients clients;
    private final PseudonymRegistry registry;
    private final FreeTextMasker freeText;

    public OwnerMessageMasker(Masters masters, Clients clients, PseudonymRegistry registry, FreeTextMasker freeText) {
        this.masters = masters;
        this.clients = clients;
        this.registry = registry;
        this.freeText = freeText;
    }

    public String mask(UUID conversationId, String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        String text = freeText.maskPatterns(neutraliseForeignRefs(conversationId, message));
        List<Words.Word> words = Words.words(text);
        if (words.isEmpty()) {
            return text;
        }
        Set<String> keys = new LinkedHashSet<>();
        words.forEach(word -> keys.add(word.key()));
        List<Person> rows = clients.findByNameWords(keys);
        boolean truncated = rows.size() >= CrmService.NAME_WORD_MATCH_LIMIT;
        if (truncated) {
            log.warn("Client name matches reached the cap: conversation={}, cap={}, words={}; name-like words fail closed",
                    conversationId, CrmService.NAME_WORD_MATCH_LIMIT, keys.size());
        }
        // A key typed capitalised anywhere in the message gets the wider match everywhere: over-masking is the safe side.
        Map<String, Boolean> typed = new LinkedHashMap<>();
        words.forEach(word -> typed.merge(word.key(), word.capitalised(), Boolean::logicalOr));
        Map<String, Candidates> bearers = bearers(typed, masters.all(), rows);
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        int spans = 0;
        int i = 0;
        while (i < words.size()) {
            int last = i;
            String rendered = null;
            if (truncated) {
                if (nameLikeAlone(words.get(i))) {
                    while (last + 1 < words.size() && Words.adjacent(text, words.get(last), words.get(last + 1))
                            && nameLikeAlone(words.get(last + 1))) {
                        last++;
                    }
                    rendered = Words.MANY;
                }
            } else {
                Candidates span = bearers.getOrDefault(words.get(i).key(), NONE);
                while (!span.isEmpty() && last + 1 < words.size() && Words.adjacent(text, words.get(last), words.get(last + 1))) {
                    Candidates narrowed = span.bearing(bearers.getOrDefault(words.get(last + 1).key(), NONE));
                    if (narrowed.isEmpty()) {
                        break;
                    }
                    span = narrowed;
                    last++;
                }
                if (!span.isEmpty() && (last > i || nameLikeAlone(words.get(i)))) {
                    rendered = render(conversationId, span);
                }
            }
            if (rendered != null) {
                out.append(text, copied, words.get(i).start());
                Words.appendRef(out, rendered, text, words.get(i).start(), words.get(last).end());
                copied = words.get(last).end();
                spans++;
            }
            i = last + 1;
        }
        String masked = out.append(text, copied, text.length()).toString();
        if (log.isDebugEnabled()) {
            log.debug("Owner message masked: conversation={}, words={}, clientRows={}, nameSpans={}, placeholders={}",
                    conversationId, keys.size(), rows.size(), spans, Words.placeholderCounts(masked));
        }
        return masked;
    }

    private String neutraliseForeignRefs(UUID conversationId, String message) {
        Matcher refs = Words.REF_SHAPED.matcher(message);
        if (!refs.find()) {
            return message;
        }
        Set<String> known = registry.displays(conversationId).keySet();
        return refs.replaceAll(ref -> {
            String token = ref.group().toUpperCase(Locale.ROOT);
            return known.contains(token) ? token : "[ref]";
        });
    }

    private static boolean nameLikeAlone(Words.Word word) {
        return Words.longEnough(word.key()) || word.capitalised();
    }

    /** For every word of the message, the people whose name has a matching word, inflected forms included. */
    private static Map<String, Candidates> bearers(Map<String, Boolean> typed, List<Person> allMasters, List<Person> rows) {
        Map<String, Candidates> out = new HashMap<>();
        for (Person master : allMasters) {
            addBearer(out, typed, master, Candidates::masters);
        }
        for (Person client : rows) {
            addBearer(out, typed, client, Candidates::clients);
        }
        return out;
    }

    private static void addBearer(
            Map<String, Candidates> out,
            Map<String, Boolean> typed,
            Person person,
            Function<Candidates, Map<UUID, Person>> side
    ) {
        for (String word : Words.nameWords(person.displayName())) {
            for (Map.Entry<String, Boolean> entry : typed.entrySet()) {
                if (NameWords.matches(entry.getKey(), word, entry.getValue())) {
                    side.apply(out.computeIfAbsent(entry.getKey(), k -> new Candidates(new LinkedHashMap<>(), new LinkedHashMap<>())))
                            .put(person.id(), person);
                }
            }
        }
    }

    private String render(UUID conversationId, Candidates span) {
        if (span.masters().size() + span.clients().size() > Words.MAX_LISTED_CANDIDATES) {
            return Words.MANY;
        }
        List<String> tokens = new ArrayList<>();
        for (Person master : span.masters().values()) {
            tokens.add(registry.refFor(conversationId, RefKind.MASTER, master.id(), master.displayName()));
        }
        for (Person client : span.clients().values()) {
            tokens.add(registry.refFor(conversationId, RefKind.CLIENT, client.id(), client.displayName()));
        }
        return Words.render(tokens);
    }
}

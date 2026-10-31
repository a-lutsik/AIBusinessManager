package com.cadence.ai.internal.privacy;

import com.cadence.crm.api.NameWords;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The client and master names already known in a conversation (token to display), for the last pass of
 * {@link FreeTextMasker} (ADR 0002 §3.2 step 3). A full display name ("Anna Ivanova") and every name word of at least
 * three letters ("Anna") are matched as whole words by {@link NameWords#matches}, inflected forms included, longest first; a
 * phrase shared by several people becomes {@code (CLIENT_1|CLIENT_2)}, by more than five {@code [name:many]}.
 */
public final class NameIndex {

    private static final NameIndex EMPTY = new NameIndex(List.of(), 0, 0);

    private record Phrase(List<String> words, String token) {
    }

    private final List<Phrase> phrases;
    private final int maxWords;
    private final int size;

    private NameIndex(List<Phrase> phrases, int maxWords, int size) {
        this.phrases = phrases;
        this.maxWords = maxWords;
        this.size = size;
    }

    public static NameIndex empty() {
        return EMPTY;
    }

    public static NameIndex of(Map<String, String> tokenToDisplay) {
        List<Phrase> phrases = new ArrayList<>();
        int maxWords = 0;
        for (Map.Entry<String, String> entry : tokenToDisplay.entrySet()) {
            List<String> words = Words.nameWords(entry.getValue());
            if (words.isEmpty()) {
                continue;
            }
            maxWords = Math.max(maxWords, words.size());
            phrases.add(new Phrase(words, entry.getKey()));
            if (words.size() == 2) {
                phrases.add(new Phrase(List.of(words.get(1), words.get(0)), entry.getKey()));
            }
            for (String word : words) {
                if (Words.longEnough(word) && words.size() > 1) {
                    phrases.add(new Phrase(List.of(word), entry.getKey()));
                }
            }
        }
        return new NameIndex(phrases, maxWords, tokenToDisplay.size());
    }

    /** Number of people in the index. */
    public int size() {
        return size;
    }

    public String apply(String text) {
        if (text == null || phrases.isEmpty()) {
            return text;
        }
        List<Words.Word> words = Words.words(text);
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        int i = 0;
        while (i < words.size()) {
            int run = 1;
            while (run < maxWords && i + run < words.size() && Words.adjacent(text, words.get(i + run - 1), words.get(i + run))) {
                run++;
            }
            Set<String> matched = Set.of();
            while (run > 0 && (matched = tokens(words, i, run)).isEmpty()) {
                run--;
            }
            if (matched.isEmpty()) {
                i++;
                continue;
            }
            int last = i + run - 1;
            out.append(text, copied, words.get(i).start());
            Words.appendRef(out, Words.render(new ArrayList<>(matched)), text, words.get(i).start(), words.get(last).end());
            copied = words.get(last).end();
            i = last + 1;
        }
        return out.append(text, copied, text.length()).toString();
    }

    /** The people whose phrase of {@code length} words matches the words from {@code from}. */
    private Set<String> tokens(List<Words.Word> words, int from, int length) {
        Set<String> tokens = new LinkedHashSet<>();
        for (Phrase phrase : phrases) {
            if (phrase.words().size() == length && matches(phrase.words(), words, from)) {
                tokens.add(phrase.token());
            }
        }
        return tokens;
    }

    private static boolean matches(List<String> phrase, List<Words.Word> words, int from) {
        for (int j = 0; j < phrase.size(); j++) {
            Words.Word word = words.get(from + j);
            if (!NameWords.matches(word.key(), phrase.get(j), word.capitalised())) {
                return false;
            }
        }
        return true;
    }
}

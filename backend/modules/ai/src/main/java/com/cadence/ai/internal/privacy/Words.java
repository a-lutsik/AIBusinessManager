package com.cadence.ai.internal.privacy;

import com.cadence.crm.api.NameWords;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Word-level helpers shared by the maskers: Unicode letter runs, the spans they must not touch, ref rendering. */
final class Words {

    /**
     * A word: a maximal run of Unicode letters and combining marks, apostrophes inside it included ({@code Мар'яну}); those at
     * its ends are cut in {@link #wordsOf}. A character class, as a group loop recurses per apostrophe and overflows the stack.
     */
    private static final Pattern LETTERS = Pattern.compile("[\\p{L}\\p{M}" + NameWords.APOSTROPHES + "]+");
    /** Placeholders and refs already in a text; their letters ("phone", "CLIENT") are never names. */
    private static final Pattern MASKED = Pattern.compile(
            "\\[(?:phone|email|url|address|sensitive|ref|name:many)]"
                    + "|(?<![A-Za-z0-9_])(?:CLIENT|MASTER|VISIT|SERVICE|DRAFT)_[0-9]+(?![A-Za-z0-9_])");
    /** Token-shaped text in any case; only the masking itself may put a live ref into a text. */
    static final Pattern REF_SHAPED = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:CLIENT|MASTER|VISIT|SERVICE|DRAFT)_[0-9]+(?![A-Za-z0-9_])");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[(phone|email|url|address|sensitive|ref|name:many)]");
    /** Between two words of one name: spaces, tabs, a hyphen or an underscore (a comma or a new line separates names). */
    private static final Pattern NAME_GAP = Pattern.compile("[\\p{Zs}\\t_-]+");

    static final int MIN_NAME_LETTERS = 3;
    static final int MAX_LISTED_CANDIDATES = 5;
    static final String MANY = "[name:many]";

    record Word(int start, int end, String key, boolean capitalised) {
    }

    private Words() {
    }

    /** The comparison form of a word, {@link NameWords#key}. */
    static String key(String word) {
        return NameWords.key(word);
    }

    /** The words of {@code text} outside placeholders and refs, in order. */
    static List<Word> words(String text) {
        List<int[]> masked = new ArrayList<>();
        Matcher m = MASKED.matcher(text);
        while (m.find()) {
            masked.add(new int[] {m.start(), m.end()});
        }
        List<Word> out = new ArrayList<>();
        for (Word word : wordsOf(text)) {
            if (!inside(masked, word.start(), word.end())) {
                out.add(word);
            }
        }
        return out;
    }

    /** The comparison forms of the words of a display name. */
    static List<String> nameWords(String display) {
        List<String> out = new ArrayList<>();
        if (display == null) {
            return out;
        }
        wordsOf(display).forEach(word -> out.add(word.key()));
        return out;
    }

    private static List<Word> wordsOf(String text) {
        List<Word> out = new ArrayList<>();
        Matcher w = LETTERS.matcher(text);
        while (w.find()) {
            int start = w.start();
            int end = w.end();
            while (start < end && NameWords.APOSTROPHES.indexOf(text.charAt(start)) >= 0) {
                start++;
            }
            while (end > start && NameWords.APOSTROPHES.indexOf(text.charAt(end - 1)) >= 0) {
                end--;
            }
            if (start < end) {
                String word = text.substring(start, end);
                out.add(new Word(start, end, key(word), NameWords.capitalised(word)));
            }
        }
        return out;
    }

    static boolean longEnough(String key) {
        return key.codePointCount(0, key.length()) >= MIN_NAME_LETTERS;
    }

    /** Whether only name-internal separators stand between {@code a} and {@code b}. */
    static boolean adjacent(String text, Word a, Word b) {
        return b.start() > a.end() && NAME_GAP.matcher(text).region(a.end(), b.start()).matches();
    }

    /** One ref as is, 2-5 as {@code (A|B)}, more as {@link #MANY}. */
    static String render(List<String> tokens) {
        if (tokens.size() == 1) {
            return tokens.get(0);
        }
        if (tokens.size() > MAX_LISTED_CANDIDATES) {
            return MANY;
        }
        return "(" + String.join("|", tokens) + ")";
    }

    /**
     * Appends a ref where a name stood in {@code text} ({@code start..end}). A digit or underscore next to a ref would
     * make another token ("CLIENT_1" + "990") or hide it from the Restorer, so a space keeps them apart.
     */
    static void appendRef(StringBuilder out, String ref, String text, int start, int end) {
        if (start > 0 && gluesToRef(text.charAt(start - 1))) {
            out.append(' ');
        }
        out.append(ref);
        if (end < text.length() && gluesToRef(text.charAt(end))) {
            out.append(' ');
        }
    }

    /** Placeholders of a masked text by kind, for DEBUG logs (never the text). */
    static Map<String, Integer> placeholderCounts(String masked) {
        Map<String, Integer> counts = new TreeMap<>();
        Matcher m = PLACEHOLDER.matcher(masked);
        while (m.find()) {
            counts.merge(m.group(1), 1, Integer::sum);
        }
        return counts;
    }

    private static boolean gluesToRef(char ch) {
        return ch == '_' || (ch >= '0' && ch <= '9');
    }

    private static boolean inside(List<int[]> spans, int start, int end) {
        for (int[] span : spans) {
            if (start < span[1] && end > span[0]) {
                return true;
            }
        }
        return false;
    }
}

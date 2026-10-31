package com.cadence.crm.api;

import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How a word of a message is compared with a word of a person's stored name. The CRM query and the AI maskers share it, so what
 * the database finds is never narrower than what the masker recognises.
 *
 * <p>{@link #key} folds case, Unicode composition, accents (so ё is е and й is и), apostrophes ({@code Мар'яна} is one word) and
 * the Turkish and Greek special letters.
 *
 * <p>{@link #matches}: equal keys always match. Otherwise the two words must share a stem (any prefix of their common prefix, not
 * only the longest) and differ only in real case or
 * surname endings of ru, uk, cs and ka ({@code ENDINGS}, in one list): {@code Анну}/{@code Анна}, {@code Петровой}/{@code Петрова},
 * {@code Иваном}/{@code Иван}, {@code Novákové}/{@code Nováková}, {@code ანას}/{@code ანა}. So {@code верно}/{@code Вера},
 * {@code лень}/{@code Лена}, {@code market}/{@code Mark}, {@code завтрак}/{@code Завтра} and {@code дано}/{@code Дана} do not match;
 * {@code верю}/{@code Вера} does (accepted). The surname suffixes {@code ов}, {@code ева}, {@code ova} are no endings, so
 * {@code Петр}/{@code Петрова} and {@code Petra}/{@code Petrova} stay different people while {@code Иванов}/{@code Иванова} match. The stem has at least three letters and both words at least
 * {@value #MIN_INFLECTED_LETTERS}. A stored name of exactly {@value #SHORT_NAME_LETTERS} letters also matches through a stem of two
 * when the typed word has at least three letters and starts with a capital ({@code Олю}/{@code Оля}, {@code Евой}/{@code Ева}), as a
 * lower-case word must stay exact there to limit false hits. Georgian has no capitals, so its words count as capitalised.
 *
 * <p>{@code о} is not a typed ending: it would make {@code дано} match {@code Дана}, so vocatives ({@code Яно}, {@code Анно}) are not
 * recognised. It is a stored ending ({@code Шевченко}/{@code Шевченка}), like {@code ь} ({@code Игорь}/{@code Игоря}). Alternations
 * ({@code Павла}/{@code Павел}, {@code Льва}/{@code Лев}) stay out of scope by the user's decision (TD-36).
 */
public final class NameWords {

    public static final int MIN_INFLECTED_LETTERS = 4;
    public static final int SHORT_NAME_LETTERS = 3;
    /** Combining marks, in syntax both {@link Pattern} and Postgres regular expressions read. */
    public static final String MARK_RANGES = "\\u0300-\\u036F\\u1AB0-\\u1AFF\\u1DC0-\\u1DFF\\u20D0-\\u20FF\\uFE20-\\uFE2F";
    /** Dropped from a word; the SQL twin deletes the same three. */
    public static final String APOSTROPHES = "'’ʼ";

    private static final Pattern MARKS = Pattern.compile("[" + MARK_RANGES + "]");
    private static final int MIN_STEM_LETTERS = 3;
    private static final int MIN_SHORT_STEM_LETTERS = 2;
    /** Endings of either word, as written; {@link #key} of each is what is compared. */
    private static final Set<String> ENDINGS = keys(
            // ru, uk: nouns, first names, surnames
            "а", "я", "у", "ю", "е", "є", "ы", "и", "і", "ї", "ой", "ей", "ою", "ею", "єю", "ом", "ем", "ём", "єм", "ым", "им", "ій",
            "ий", "ый", "ая", "яя", "ую", "юю", "ое", "ее", "ого", "его", "ому", "ему", "их", "ых", "ої", "ові", "еві", "єві", "ью", "ья", "ье", "ьи",
            "ьей", "ям", "ам",
            // cs
            "á", "é", "í", "ě", "ou", "ově", "ovi", "ové", "ího", "ímu", "ím", "ého", "ému", "ém", "ým", "em", "a", "e", "i", "y", "u", "s",
            // ka
            "ა", "ი", "ს", "მ", "მა", "ის", "ით", "ად", "სთვის", "ისთვის", "სთან", "თან", "სგან", "ისგან", "გან", "ზე");
    /** Endings only a stored name may have, which would be false stems in a typed word. */
    private static final Set<String> STORED_ONLY_ENDINGS = keys("ь", "о", "o");

    private NameWords() {
    }

    /** The comparison form; the SQL twin is {@code ClientRepository.KEY_SQL}, and a startup check proves they agree. */
    public static String key(String word) {
        String lower = word.replace("'", "").replace("’", "").replace("ʼ", "")
                .replace('İ', 'i').replace('ı', 'i').replace('Σ', 'σ').replace('ς', 'σ')
                .toLowerCase(Locale.ROOT);
        return MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
    }

    /** Whether {@code word} as written starts with a capital; a Georgian letter counts as one, the script having none in names. */
    public static boolean capitalised(String word) {
        int first = word.codePointAt(0);
        return Character.isUpperCase(first) || Character.isTitleCase(first)
                || Character.UnicodeScript.of(first) == Character.UnicodeScript.GEORGIAN;
    }

    /** Whether the typed word names the stored word, see the class comment; both are {@link #key} forms. */
    public static boolean matches(String typed, String stored, boolean typedCapitalised) {
        if (typed.equals(stored)) {
            return true;
        }
        int typedLetters = letters(typed);
        int storedLetters = letters(stored);
        int minStem;
        if (Math.min(typedLetters, storedLetters) >= MIN_INFLECTED_LETTERS) {
            minStem = MIN_STEM_LETTERS;
        } else if (typedCapitalised && storedLetters == SHORT_NAME_LETTERS && typedLetters >= SHORT_NAME_LETTERS) {
            minStem = MIN_SHORT_STEM_LETTERS;
        } else {
            return false;
        }
        // From the longest common prefix down: Шевченком/Шевченко have the stem шевченк, shorter than their common prefix.
        int cut = commonPrefixChars(typed, stored);
        for (int stem = letters(typed.substring(0, cut)); stem >= minStem; stem--) {
            if (isTail(typed.substring(cut), stored.substring(cut))) {
                return true;
            }
            cut = typed.offsetByCodePoints(cut, -1);
        }
        return false;
    }

    private static boolean isTail(String typedTail, String storedTail) {
        return (typedTail.isEmpty() || ENDINGS.contains(typedTail))
                && (storedTail.isEmpty() || ENDINGS.contains(storedTail) || STORED_ONLY_ENDINGS.contains(storedTail));
    }

    /**
     * What a stored word must equal, alone or plus one of {@link #storedEndings}, to possibly match {@code typed}. Whatever
     * {@link #matches} accepts, in either case of the first letter, is covered, so a query built on this is a prefilter that is
     * never narrower; the caller decides with {@link #matches}.
     */
    public static Set<String> stems(String typed) {
        Set<String> stems = new LinkedHashSet<>();
        stems.add(typed);
        for (String ending : ENDINGS) {
            if (typed.endsWith(ending) && letters(typed) - letters(ending) >= MIN_SHORT_STEM_LETTERS) {
                stems.add(typed.substring(0, typed.length() - ending.length()));
            }
        }
        return stems;
    }

    public static Set<String> storedEndings() {
        Set<String> all = new LinkedHashSet<>(ENDINGS);
        all.addAll(STORED_ONLY_ENDINGS);
        return all;
    }

    private static Set<String> keys(String... endings) {
        Set<String> out = new LinkedHashSet<>();
        for (String ending : endings) {
            out.add(key(ending));
        }
        return Collections.unmodifiableSet(out);
    }

    /** Length in Unicode code points, as Postgres counts. */
    public static int letters(String key) {
        return key.codePointCount(0, key.length());
    }

    private static int commonPrefixChars(String a, String b) {
        int common = 0;
        int max = Math.min(a.length(), b.length());
        while (common < max && a.charAt(common) == b.charAt(common)) {
            common++;
        }
        return common > 0 && common < max && Character.isHighSurrogate(a.charAt(common - 1)) ? common - 1 : common;
    }
}

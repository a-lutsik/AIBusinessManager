package com.cadence.ai.internal.privacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks free text before it reaches the LLM (ADR 0002 §3.2): client notes and tags (when the owner allows notes to
 * go to the LLM) and, patterns only, the owner's own message (see {@link OwnerMessageMasker}).
 *
 * <ol>
 *   <li>token-shaped text becomes {@code [ref]}, so a note can never name a live ref;</li>
 *   <li>{@link #maskPatterns patterns}: emails {@code [email]}, URLs and Telegram handles {@code [url]}, phones
 *   {@code [phone]}, addresses and postal codes {@code [address]};</li>
 *   <li>stop-words: the text is cut into sentences on {@code ;}, new lines and {@code .} before a space. A sentence
 *   holding a word that starts with a stop-word stem (health, debts, convictions; {@code ai-masking/stopwords_*.txt},
 *   every locale applied to every text) becomes {@code [sensitive]} whole, as details surround the diagnosis ("Латекс и
 *   никель, аллергия"). A detail in another sentence ("Никель. Аллергия") stays, and a denylist is never complete;</li>
 *   <li>names already known in the conversation become their refs ({@link NameIndex}).</li>
 * </ol>
 * The name of an unknown third person without a stop-word stays (accepted risk TD-37). Deterministic, stateless and
 * thread-safe; the lists are loaded once and a missing list fails the start.
 */
@Component
public class FreeTextMasker {

    private static final Logger log = LoggerFactory.getLogger(FreeTextMasker.class);

    static final List<String> LOCALES = List.of("ru", "en", "uk", "cs", "ka");
    private static final int MAX_LABEL_CHARS = 120;

    // --- 1. patterns -------------------------------------------------------------------------------------------

    private static final Pattern EMAIL = Pattern.compile(
            "(?<![\\p{L}\\p{M}\\p{N}._%+'’-])[\\p{L}\\p{M}\\p{N}._%+'’-]+(?:@|(?i:\\(at\\)|\\[at]))"
                    + "[\\p{L}\\p{M}\\p{N}-]+(?:\\.[\\p{L}\\p{M}\\p{N}-]+){0,8}");
    private static final String URL_TAIL = "[^\\s<>\"'«»]*";
    private static final String HOST_START = "(?<![\\p{L}\\p{N}.@_-])";
    private static final String LABEL = "[\\p{L}\\p{N}-]{1,63}\\.";
    /** A scheme or www; a known top-level domain; any host with a path; any host of three or more labels. */
    private static final Pattern URL = Pattern.compile(
            "(?i)(?:(?<![\\p{L}\\p{N}])(?:https?|ftp)://" + URL_TAIL
                    + "|" + HOST_START + "www\\." + URL_TAIL
                    + "|" + HOST_START + "(?:" + LABEL + "){1,8}"
                    + "(?:com|net|org|info|biz|ru|ge|cz|sk|pl|ua|uk|eu|io|me|app|dev|site|online|shop|store|link|ly|gl"
                    + "|co|de|by|kz|am|az|рф|укр)(?![\\p{L}\\p{N}])(?:/" + URL_TAIL + ")?"
                    + "|" + HOST_START + "(?:" + LABEL + "){1,8}[a-z]{2,24}/" + URL_TAIL
                    + "|" + HOST_START + "(?:" + LABEL + "){2,8}[a-z]{2,24}(?![\\p{L}\\p{N}]))");
    /** A social handle ({@code @anna.petrova}) is a contact, so it goes as {@code [url]}. */
    private static final Pattern HANDLE = Pattern.compile(
            "(?<![\\p{L}\\p{N}_@.])@[\\p{L}_][\\p{L}\\p{M}\\p{N}_.-]{2,31}");
    private static final String URL_TRAILING_PUNCTUATION = ".,;:!?)]}'\"»-";

    /**
     * A phone is a run of digits ({@link Character#isDigit}, any script) with an optional leading {@code +} and only
     * phone separators between them: at most {@value #MAX_GAP} characters of Unicode spaces, tabs, format characters
     * (zero-width), balanced parentheses and at most {@value #MAX_HARD_SEPARATORS} of {@code - . _ /} or dashes, or a
     * single comma with digits on both sides. A run with at least {@value #MIN_PHONE_DIGITS} digits is a phone. Scanned
     * by hand ({@link #maskStrictRuns}): a regex group loop recurses per digit and overflows the stack on long digit runs.
     */
    static final int MIN_PHONE_DIGITS = 7;
    /**
     * Backstop ({@link #maskWideRuns}): at least {@value #MIN_WIDE_RUN_DIGITS} digits with at most {@value #MAX_WIDE_GAP}
     * non-letters between consecutive digits are a phone whatever the punctuation or line breaks, except a strictly
     * ascending comma list ("80, 120, 15000"). Numbers are over-masked on purpose.
     */
    private static final int MIN_WIDE_RUN_DIGITS = 9;
    private static final int MAX_WIDE_GAP = 3;
    private static final long MAX_GROUP_VALUE = 1_000_000_000_000L;
    private static final int MAX_GAP = 6;
    private static final int MAX_HARD_SEPARATORS = 2;
    /** A comma run is a phone only with a {@code +}, or with this many digits and a group of three ("10,20,30,40" stays). */
    private static final int MIN_COMMA_RUN_DIGITS = 9;
    private static final int MIN_COMMA_RUN_GROUP = 3;
    /** A date right after a digit group this long (or a parenthesised one) is the tail of a phone: "06 12.10.20". */
    private static final int MIN_GROUP_BEFORE_DATE = 2;
    /** How far back a {@code +} still opens the digits before a date or time: "+995 55 12.03.56" is never shielded. */
    private static final int MAX_PLUS_LOOKBACK = 24;
    private static final Pattern TIME = Pattern.compile(
            "(?<![\\p{N}:.])(?:[01]?\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d)?(?![\\p{N}:])");
    /**
     * Dates and dotted times are not phones: {@code 2026-10-27}, {@code 27.10.2026}, {@code 10/27/2026}, {@code 27.10},
     * {@code 10.00}. They are shielded before the phone pass so "27.10.2026 10:00" or "27.10-28.10" never join into a
     * digit run. A "date" that goes on with {@code .digit} or {@code /digit} ("06.12.34.56.78") is not shielded.
     */
    private static final Pattern DATE = Pattern.compile(
            "(?<![\\p{N}./])(?:(\\d{4})-(\\d{1,2})-(\\d{1,2})|(\\d{1,2})([./-])(\\d{1,2})\\5\\d{4}"
                    + "|(\\d{1,2})[./](\\d{1,2})(?:[./]\\d{2})?)(?!\\p{N})(?![./]\\d)");
    /** Amounts with two decimals ("12500.50") are not phones either. */
    private static final Pattern DECIMAL = Pattern.compile("\\d{1,8}\\.\\d{2}");

    private static final String NUMBER = "\\d{1,6}\\p{L}?(?:[/-]\\d{1,6}\\p{L}?)?";
    private static final String NOT_LETTER_BEFORE = "(?<![\\p{L}\\p{N}])";
    /** Word quantifiers are bounded and a match never starts inside a hyphenated word, so the patterns stay linear. */
    private static final String NOT_WORD_BEFORE = "(?<![\\p{L}\\p{M}\\p{N}'’-])";
    private static final String WORD = "[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}'’-]{0,40}";
    private static final String GAP = "[ \\t]{1,3}";
    private static final String CITY_NAME = "(?:Tbilisi|Batumi|Kutaisi|Rustavi|თბილისი|ბათუმი|ქუთაისი|Praha|Prague|Brno|Ostrava|Plzeň"
            + "|Olomouc|Москва|Moscow|Санкт-Петербург|Петербург|Казань|Київ|Киев|Kyiv|Львів|Львов|Одеса|Одесса|Харків|Харьков"
            + "|Минск|Minsk|Warszawa|Bratislava)";
    private static final String CITY = CITY_NAME + "(?![\\p{L}])";
    private static final String CURRENCY = "(?i:lari|лари|gel|euro|eur|dollars?|usd|czk|kč|uah|грн|rub|руб)";
    private static final String CAPITALISED = "\\p{Lu}[\\p{L}\\p{M}'’-]{0,40}";
    /** Street keywords followed by the street name: "ул. Ленина 5", "ulice Národní 12", "вул. Хрещатик 22". */
    private static final Pattern ADDRESS_STREET_FIRST = Pattern.compile(
            "(?iu)" + NOT_LETTER_BEFORE
                    + "(?:ул|улица|вул|вулиця|просп|проспект|пр-т|переулок|провулок|бульвар|ulice|ul|třída|str"
                    + "|пер\\.|бул\\.|nám\\.|náměstí)(?![\\p{L}])\\.?[ \\t]{0,3}" + WORD + "(?:" + GAP + WORD + "){0,2}"
                    + "(?:[ \\t,]{1,3}\\d{1,6}\\p{L}?(?![\\p{L}\\p{N}]))?");
    /**
     * Abbreviations and inflected forms that also read as ordinary words, so only before a capitalised name:
     * "пр. Мира 5", "б-р Шевченко 10", "на улицу Ленина 5" but not "пр. к врачу", "на улице холодно".
     */
    private static final Pattern ADDRESS_STREET_CAPITALISED = Pattern.compile(
            "(?iu)" + NOT_LETTER_BEFORE
                    + "(?:(?:пр|пл|наб|ш|ал|туп)\\.|б-р|пр-кт|улиц\\p{L}{1,3}|вулиц\\p{L}{1,3}|площад\\p{L}{1,3}|набережн\\p{L}{1,3}"
                    + "|шоссе|шосе)[ \\t]{0,3}(?-i:\\p{Lu})[\\p{L}\\p{M}'’-]{0,40}"
                    + "(?:" + GAP + "(?-i:[\\p{Lu}\\p{N}])[\\p{L}\\p{M}\\p{N}'’/-]{0,40}){0,2}");
    /** Keywords followed by a number: "в доме 5", "кв. 12", "apt 4", "byt 5", "č.p. 123", "домофон 45К", "floor 3". */
    private static final Pattern ADDRESS_NUMBER_AFTER = Pattern.compile(
            "(?iu)" + NOT_LETTER_BEFORE
                    + "(?:д|дом|дома|доме|домом|кв|kv|квартир\\p{L}{0,3}|корп|корпус\\p{L}{0,2}|буд|будин\\p{L}{0,3}|apt|apartment|flat"
                    + "|house|byt|č\\.\\s?p|čp|č\\.\\s?o|подъезд\\p{L}{0,2}|під'їзд\\p{L}{0,2}|під’їзд\\p{L}{0,2}|домофон\\p{L}{0,2}"
                    + "|этаж\\p{L}{0,2}|поверх\\p{L}{0,2}|floor|entrance|patro|vchod|ბინა|სართული|სადარბაზო|კორპ)"
                    + "(?![\\p{L}])\\.?[ \\t]{0,3}(?:№[ \\t]{0,3})?" + NUMBER);
    /** A number then the keyword: "3 этаже", "2-й подъезд", "3rd floor", "из 42 квартиры", "42 кв.". */
    private static final Pattern ADDRESS_NUMBER_BEFORE = Pattern.compile(
            "(?iu)(?<![\\p{N}])\\d{1,6}(?:-?\\p{L}{1,3})?" + GAP
                    + "(?:(?:этаж|подъезд|поверх|під'їзд|під’їзд|floor|entrance|patro|vchod|квартир|apartment)\\p{L}{0,4}"
                    + "|(?:кв|kv|apt)(?![\\p{L}])\\.?)");
    /** The street name then the keyword: "12 Baker Street", "Rustaveli str. 12", "Rustaveli Ave 12", "რუსთაველის ქ. 12". */
    private static final Pattern ADDRESS_NAME_FIRST = Pattern.compile(
            "(?u)" + NOT_WORD_BEFORE + "(?:\\d{1,6}\\p{L}?" + GAP + ")?(?:\\p{Lu}[\\p{L}\\p{M}'’-]{0,40}" + GAP + "){0,2}"
                    + "\\p{L}[\\p{L}\\p{M}'’-]{0,40}" + GAP
                    + "(?:(?i:street|avenue|road|lane|boulevard|ქუჩა|გამზირი)(?![\\p{L}])|(?i:st|str|ave|rd|blvd)\\."
                    + "|(?i:st|str|ave|rd|blvd)(?=[ \\t]{0,3}\\d)|ქ\\.|გამზ\\.)(?:[ \\t]{0,3}" + NUMBER + ")?");
    /** "12 Baker St", "Baker Rd 5": the short keyword without a dot, after a capitalised name. */
    private static final Pattern ADDRESS_SHORT_KEYWORD = Pattern.compile(
            "(?u)" + NOT_WORD_BEFORE + "(?:\\d{1,6}\\p{L}?" + GAP + ")?" + CAPITALISED + GAP
                    + "(?i:st|rd|ave|blvd)(?![\\p{L}\\p{N}])(?:[ \\t]{0,3}" + NUMBER + ")?");
    /**
     * A capitalised street name and a house without any keyword, only in shapes prices and services rarely take:
     * "Vinohradská 1234/56", "Lenina 5-12", "Тверская, 12-5", "Rustaveli 12a", "Ленина 12 корпус 2 кв 5", "Národní 12, Praha 1",
     * "Тверская 12, Воронеж". A bare "Rustaveli 12" stays, as it reads the same as "Haircut 45"; a range after a weekday
     * or month, or before a unit, is not a house. Ambiguous shapes are masked: "Haircut 10-12", "Анна, 10-12" and "Haircut
     * 45, Color" lose a time or a price, the alternative leaks an address.
     */
    private static final Pattern ADDRESS_HOUSE = Pattern.compile(
            "(?u)" + NOT_WORD_BEFORE + "(?!(?i:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun"
                    + "|january|february|march|april|may|june|july|august|september|october|november|december"
                    + "|понедельник|вторник|среда|среду|четверг|пятница|пятницу|суббота|субботу|воскресенье"
                    + "|понеділок|вівторок|середа|п'ятниця|субота|неділя|pondělí|úterý|středa|čtvrtek|pátek|sobota|neděle"
                    + "|января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)(?![\\p{L}]))"
                    + CAPITALISED + "(?:"
                    + "[ \\t,]{1,3}\\d{1,4}\\p{L}?[/-]\\d{1,4}\\p{L}?(?![\\p{L}\\p{N}])(?![ \\t]{0,3}(?iu:min|мин|час|hour|gel|lari|лари"
                    + "|eur|usd|руб|грн|kč|czk|дн|day|недел|week|визит|visit|услуг|клиент))"
                    + "|[ \\t,]{1,3}\\d{1,4}\\p{L}?(?:[ \\t,]{1,3}(?iu:кв|kv|apt|д|дом|корп|корпус|стр|byt)(?![\\p{L}])\\.?[ \\t]{0,3}"
                    + "\\d{1,4}\\p{L}?){1,3}"
                    + "|" + GAP + "\\d{1,4}\\p{L}?," + GAP + "(?:" + CITY + "(?:" + GAP + "\\d{1,2}(?!\\p{N}))?"
                    + "|\\p{Lu}\\p{Ll}{2,30}(?![\\p{L}])(?![ \\t]{0,3}\\d))"
                    + "|" + GAP + "\\d{1,4}[abcefABCEFабвАБВაბგ](?![\\p{L}\\p{N}]))");
    /** Postal codes in context: after a keyword, or next to a city ("123456, Москва", "110 00 Praha", "Tbilisi 0108"). */
    private static final Pattern POSTAL = Pattern.compile(
            "(?iu)" + NOT_LETTER_BEFORE + "(?:индекс|index|zip(?:[ \\t]?code)?|post(?:al)?[ \\t]?code|psč|psc)"
                    + "[:\\s]{0,3}\\d{3}[ \\t]?\\d{2,3}(?!\\p{N})"
                    + "|(?-i:(?<![\\p{N}.,:+-])(?:\\d{6}(?=,[ \\t]{0,3}(?:г\\.[ \\t]{0,3})?\\p{Lu}\\p{Ll}{2})"
                    + "|\\d{4}(?=,?" + GAP + CITY + ")"
                    + "|(?:\\d{5}|\\d{3} \\d{2})(?=" + GAP + "(?!" + CURRENCY + "(?!\\p{L}))\\p{Lu}\\p{Ll}{2}))"
                    + "|(?<=" + CITY_NAME + ",?" + GAP + ")(?:\\d{4,6}|\\d{3} \\d{2})(?!\\p{N})(?![ \\t]{0,3}" + CURRENCY + "))");

    // --- 2. stop-words -----------------------------------------------------------------------------------------

    /** "должна/винна" read as a debt only before an amount ("она должна 5000"); alone they mean "must". */
    private static final Pattern DEBT_AMOUNT = Pattern.compile(
            "(?iu)" + NOT_LETTER_BEFORE + "(?:должна|должен|должны|винна|винен|винні)"
                    + "(?:" + GAP + "(?:мне|нам|салону|ещё|еще|мені))?" + GAP + "\\d");

    private final List<String> stems;
    private final Set<String> exactWords;

    public FreeTextMasker() {
        List<String> stems = new ArrayList<>();
        Set<String> exact = new HashSet<>();
        for (String locale : LOCALES) {
            for (String line : readList("ai-masking/stopwords_" + locale + ".txt")) {
                if (line.startsWith("=")) {
                    exact.add(Words.key(line.substring(1).trim()));
                } else {
                    stems.add(Words.key(line));
                }
            }
        }
        if (stems.isEmpty()) {
            throw new IllegalStateException("AI masking stop-word lists are empty");
        }
        this.stems = List.copyOf(stems);
        this.exactWords = Set.copyOf(exact);
    }

    /** All passes; {@code names} may be {@link NameIndex#empty()}. */
    public String mask(String text, NameIndex names) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = maskSensitiveSentences(maskPatterns(Words.REF_SHAPED.matcher(text).replaceAll("[ref]")));
        if (log.isDebugEnabled()) {
            log.debug("Free text masked: chars={}, placeholders={}", text.length(), Words.placeholderCounts(out));
        }
        return names == null ? out : names.apply(out);
    }

    /**
     * A short owner-typed label (a service name): token-shaped text and contacts masked, on one line, at most
     * {@value #MAX_LABEL_CHARS} characters. It may stand in the system prompt, so it can never start a line of its own.
     */
    public String maskLabel(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = maskPatterns(Words.REF_SHAPED.matcher(text).replaceAll("[ref]")).replaceAll("\\s+", " ").strip();
        return out.length() <= MAX_LABEL_CHARS ? out : out.substring(0, MAX_LABEL_CHARS);
    }

    /** Contacts only: emails, URLs and handles, phones, addresses. */
    public String maskPatterns(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = replace(EMAIL, text, m -> "[email]");
        out = replace(URL, out, FreeTextMasker::urlKeepingTrailingPunctuation);
        out = replace(HANDLE, out, FreeTextMasker::urlKeepingTrailingPunctuation);
        out = maskStrictRuns(maskWideRuns(out));
        out = replace(ADDRESS_NAME_FIRST, out, m -> "[address]");
        out = replace(POSTAL, out, m -> "[address]");
        out = replace(ADDRESS_STREET_FIRST, out, m -> "[address]");
        out = replace(ADDRESS_STREET_CAPITALISED, out, m -> "[address]");
        out = replace(ADDRESS_SHORT_KEYWORD, out, m -> "[address]");
        out = replace(ADDRESS_HOUSE, out, m -> "[address]");
        out = replace(ADDRESS_NUMBER_BEFORE, out, m -> "[address]");
        out = replace(ADDRESS_NUMBER_AFTER, out, m -> "[address]");
        return out;
    }

    private String maskSensitiveSentences(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        int sentenceStart = 0;
        for (int i = 0; i <= text.length(); i++) {
            char ch = i < text.length() ? text.charAt(i) : '\n';
            boolean spaceAfter = i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1));
            if (ch != ';' && ch != '\n' && !(ch == '.' && spaceAfter)) {
                continue;
            }
            if (isSensitive(text.substring(sentenceStart, i))) {
                int start = sentenceStart;
                while (start < i && Character.isWhitespace(text.charAt(start))) {
                    start++;
                }
                out.append(text, copied, start).append("[sensitive]");
                copied = i;
            }
            sentenceStart = i + 1;
        }
        return copied >= text.length() ? out.toString() : out.append(text, copied, text.length()).toString();
    }

    private boolean isSensitive(String sentence) {
        return hasStopWord(sentence) || DEBT_AMOUNT.matcher(sentence).find();
    }

    private boolean hasStopWord(String sentence) {
        for (Words.Word word : Words.words(sentence)) {
            if (exactWords.contains(word.key())) {
                return true;
            }
            for (String stem : stems) {
                if (word.key().startsWith(stem)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String maskWideRuns(String text) {
        char[] c = shield(text);
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        int i = 0;
        while (i < c.length) {
            if (!Character.isDigit(c[i])) {
                i++;
                continue;
            }
            int end = i + 1;
            int digits = 1;
            int groups = 1;
            boolean ascendingCommaList = true;
            long previous = -1;
            long group = Character.getNumericValue(c[i]);
            while (true) {
                int next = end;
                boolean comma = false;
                while (next < c.length && next - end <= MAX_WIDE_GAP && !Character.isDigit(c[next]) && !Character.isLetter(c[next])) {
                    comma |= c[next] == ',';
                    next++;
                }
                if (next >= c.length || next - end > MAX_WIDE_GAP || !Character.isDigit(c[next])) {
                    break;
                }
                if (next > end) {
                    ascendingCommaList &= comma && previous < group;
                    previous = group;
                    group = 0;
                    groups++;
                }
                group = Math.min(group * 10 + Character.getNumericValue(c[next]), MAX_GROUP_VALUE);
                digits++;
                end = next + 1;
            }
            if (digits >= MIN_WIDE_RUN_DIGITS && !(groups > 1 && ascendingCommaList && previous < group)) {
                out.append(text, copied, runStart(c, i, end)).append("[phone]");
                copied = end;
            }
            i = end;
        }
        return out.append(text, copied, text.length()).toString();
    }

    /** The run widened over its {@code +} and over an opening bracket whose closing one lies inside: "+(916) 123". */
    private static int runStart(char[] c, int first, int end) {
        int start = first;
        int bracket = start > 0 ? "([{".indexOf(c[start - 1]) : -1;
        if (bracket >= 0 && new String(c, first, end - first).indexOf(")]}".charAt(bracket)) >= 0) {
            start--;
        }
        return start > 0 && c[start - 1] == '+' ? start - 1 : start;
    }

    private static String maskStrictRuns(String text) {
        char[] c = shield(text);
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        int noCommaUntil = 0;
        int i = 0;
        while (i < c.length) {
            int first = firstDigit(c, i);
            if (first < 0) {
                i++;
                continue;
            }
            PhoneRun run = scanRun(c, i, first, i >= noCommaUntil);
            if (run.comma && c[i] != '+' && (run.digits < MIN_COMMA_RUN_DIGITS || run.longestGroup < MIN_COMMA_RUN_GROUP)) {
                // A list or an amount, not a phone: read its part again up to the first comma, once per run.
                noCommaUntil = run.end;
                run = scanRun(c, i, first, false);
            }
            if (run.digits >= MIN_PHONE_DIGITS && !DECIMAL.matcher(text.substring(first, run.end)).matches()) {
                // "(555 123 45 67)" keeps its parentheses, "+7 (916 123 45 67)" loses both.
                int start = run.open > 0 && c[i] == '(' ? i + 1 : i;
                int end = run.open > 0 && c[i] != '(' && run.end < c.length && c[run.end] == ')' ? run.end + 1 : run.end;
                out.append(text, copied, start).append("[phone]");
                copied = end;
                i = end;
            } else {
                i = run.end;
            }
        }
        return out.append(text, copied, text.length()).toString();
    }

    /** Same-length shield: refs, dates and times become letters, supplementary digits ASCII; indices stay aligned. */
    private static char[] shield(String text) {
        char[] c = text.toCharArray();
        for (int i = 0; i + 1 < c.length; i++) {
            if (Character.isHighSurrogate(c[i]) && Character.isDigit(text.codePointAt(i))) {
                c[i] = '0';
                c[++i] = '0';
            }
        }
        Matcher ref = Words.REF_SHAPED.matcher(text);
        while (ref.find()) {
            Arrays.fill(c, ref.start(), ref.end(), 'x');
        }
        Matcher time = TIME.matcher(text);
        while (time.find()) {
            if (!opensWithPlus(c, time.start())) {
                Arrays.fill(c, time.start(), time.end(), 'x');
            }
        }
        Matcher date = DATE.matcher(text);
        while (date.find()) {
            if (plausibleDate(date) && !opensWithPlus(c, date.start()) && !followsPhoneGroup(c, date.start())) {
                Arrays.fill(c, date.start(), date.end(), 'x');
            }
        }
        return c;
    }

    private static boolean opensWithPlus(char[] c, int at) {
        for (int k = at - 1; k >= 0 && at - k <= MAX_PLUS_LOOKBACK; k--) {
            if (c[k] == '+') {
                return true;
            }
            if (!Character.isDigit(c[k]) && !isSoftSeparator(c[k]) && !isHardSeparator(c[k]) && c[k] != '(' && c[k] != ')') {
                return false;
            }
        }
        return false;
    }

    /** Whether, over a separator gap, a digit group or a parenthesised group ends right before {@code at}. */
    private static boolean followsPhoneGroup(char[] c, int at) {
        int k = at - 1;
        while (k >= 0 && at - k <= MAX_GAP && (isSoftSeparator(c[k]) || isHardSeparator(c[k]))) {
            k--;
        }
        if (k > 0 && c[k] == ')') {
            return Character.isDigit(c[k - 1]);
        }
        int group = 0;
        while (k >= 0 && Character.isDigit(c[k]) && group < MIN_GROUP_BEFORE_DATE) {
            group++;
            k--;
        }
        return group >= MIN_GROUP_BEFORE_DATE;
    }

    /** The first digit of a run starting at {@code at} (itself, or after a {@code +} or a parenthesis), or -1. */
    private static int firstDigit(char[] c, int at) {
        if (Character.isDigit(c[at])) {
            return at;
        }
        if (c[at] != '+' && c[at] != '(') {
            return -1;
        }
        for (int k = at + 1; k < c.length && k - at <= MAX_HARD_SEPARATORS + 1; k++) {
            if (Character.isDigit(c[k])) {
                return k;
            }
            if (c[k] != '(' && !isSoftSeparator(c[k])) {
                return -1;
            }
        }
        return -1;
    }

    private static PhoneRun scanRun(char[] c, int start, int first, boolean allowComma) {
        PhoneRun run = new PhoneRun();
        for (int k = start; k < first; k++) {
            run.open += c[k] == '(' ? 1 : 0;
        }
        run.digits = 1;
        run.longestGroup = 1;
        run.end = first + 1;
        int group = 1;
        while (true) {
            int next = nextDigit(c, run, allowComma);
            if (next < 0) {
                return run;
            }
            group = next == run.end ? group + 1 : 1;
            run.longestGroup = Math.max(run.longestGroup, group);
            run.digits++;
            run.end = next + 1;
        }
    }

    /** The next digit of the run over one separator gap (which updates the run's open parentheses), or -1. */
    private static int nextDigit(char[] c, PhoneRun run, boolean allowComma) {
        int open = run.open;
        int hard = 0;
        boolean comma = false;
        int k = run.end;
        for (; k < c.length && !Character.isDigit(c[k]); k++) {
            char ch = c[k];
            if (k - run.end >= MAX_GAP) {
                return -1;
            }
            if (ch == '(') {
                open++;
            } else if (ch == ')' && open > 0) {
                open--;
            } else if (ch == ',' && allowComma && k == run.end && k + 1 < c.length && Character.isDigit(c[k + 1])) {
                comma = true;
            } else if (isHardSeparator(ch)) {
                if (++hard > MAX_HARD_SEPARATORS) {
                    return -1;
                }
            } else if (!isSoftSeparator(ch)) {
                return -1;
            }
        }
        if (k == c.length) {
            return -1;
        }
        run.open = open;
        run.comma |= comma;
        return k;
    }

    private static boolean isSoftSeparator(char ch) {
        int type = Character.getType(ch);
        return ch == '\t' || type == Character.SPACE_SEPARATOR || type == Character.FORMAT;
    }

    private static boolean isHardSeparator(char ch) {
        return ch == '.' || ch == '_' || ch == '/' || Character.getType(ch) == Character.DASH_PUNCTUATION;
    }

    private static final class PhoneRun {
        int digits;
        int end;
        int open;
        int longestGroup;
        boolean comma;
    }

    private static boolean plausibleDate(Matcher m) {
        if (m.group(1) != null) {
            return within(m.group(2), 12) && within(m.group(3), 31);
        }
        String a = m.group(4) != null ? m.group(4) : m.group(7);
        String b = m.group(4) != null ? m.group(6) : m.group(8);
        return (within(a, 31) && within(b, 12)) || (within(a, 12) && within(b, 31))
                || (b.equals("00") && Integer.parseInt(a) <= 23);
    }

    private static boolean within(String number, int max) {
        int value = Integer.parseInt(number);
        return value >= 1 && value <= max;
    }

    private static String urlKeepingTrailingPunctuation(Matcher m) {
        String url = m.group();
        int end = url.length();
        while (end > 0 && URL_TRAILING_PUNCTUATION.indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return "[url]" + url.substring(end);
    }

    private static String replace(Pattern pattern, String text, Function<Matcher, String> replacement) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        do {
            out.append(text, copied, m.start()).append(replacement.apply(m));
            copied = m.end();
        } while (m.find());
        return out.append(text, copied, text.length()).toString();
    }

    private static List<String> readList(String resource) {
        InputStream in = FreeTextMasker.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("AI masking list missing: " + resource);
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("AI masking list unreadable: " + resource, e);
        }
        return lines;
    }
}

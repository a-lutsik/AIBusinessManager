package com.cadence.platform.phone;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a text shows a phone number. Shared by the MASTER response policy (a name that shows the client's phone is
 * written as null) and the master's client search (such clients are left out), so both apply one rule (TD-18).
 *
 * <p>Digits count as one run when only spaces, dashes, dots or parentheses stand between them, so "555 12 34 56" is
 * the run 555123456. A text shows a phone when it is made only of phone characters with at least
 * {@value #MIN_PHONE_DIGITS} digits, contains the client's full phone digits, has a run of at least
 * {@value #MIN_PHONE_DIGITS} digits that ends the client's phone (local format), or has any run of at least
 * {@value #ANY_PHONE_RUN} digits (a phone of any client).
 */
public final class PhoneShape {

    /** A value made only of phone characters with at least seven digits reads as a phone number. */
    private static final Pattern PHONE_SHAPED = Pattern.compile("^\\+?[0-9 ()\\-.]*$");
    /** Digits with only phone separators between them. */
    private static final Pattern DIGIT_RUN = Pattern.compile("[0-9](?:[ ()\\-.]*[0-9])*");
    static final int MIN_PHONE_DIGITS = 7;
    static final int ANY_PHONE_RUN = 9;

    private PhoneShape() {
    }

    /** Whether {@code text} shows {@code phone} (nullable), in any formatting, or a phone number of its own. */
    public static boolean showsPhone(String text, String phone) {
        if (text == null) {
            return false;
        }
        String textDigits = digits(text);
        if (textDigits.length() >= MIN_PHONE_DIGITS && PHONE_SHAPED.matcher(text.trim()).matches()) {
            return true;
        }
        String phoneDigits = phone == null ? "" : digits(phone);
        if (phoneDigits.length() >= MIN_PHONE_DIGITS && textDigits.contains(phoneDigits)) {
            return true;
        }
        Matcher runs = DIGIT_RUN.matcher(text);
        while (runs.find()) {
            String run = digits(runs.group());
            if (run.length() >= ANY_PHONE_RUN) {
                return true;
            }
            if (run.length() >= MIN_PHONE_DIGITS && !phoneDigits.isEmpty() && phoneDigits.endsWith(run)) {
                return true;
            }
        }
        return false;
    }

    private static String digits(String value) {
        return value.replaceAll("[^0-9]", "");
    }
}

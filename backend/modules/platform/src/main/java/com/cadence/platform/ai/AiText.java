package com.cadence.platform.ai;

/** Fitting texts into the bounded {@code VARCHAR} columns of the AI tables. */
public final class AiText {

    /** Appended to a text cut to its column limit (ADR 0002 §6, TD-43). */
    public static final String TRUNCATION_MARKER = "...[truncated]";

    private AiText() {
    }

    /**
     * {@code text} itself when it has at most {@code maxChars} UTF-16 units, otherwise its head followed by
     * {@link #TRUNCATION_MARKER}, {@code maxChars} units in total. A surrogate pair is never split. Postgres counts
     * {@code VARCHAR(n)} in code points, which are never more than the UTF-16 units, so the result always fits.
     * NUL characters are dropped first, as Postgres rejects them in text columns.
     */
    public static String truncate(String raw, int maxChars) {
        if (raw == null) {
            return null;
        }
        String text = raw.indexOf('\u0000') < 0 ? raw : raw.replace("\u0000", "");
        if (text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= TRUNCATION_MARKER.length()) {
            throw new IllegalArgumentException("maxChars too small for the truncation marker: " + maxChars);
        }
        int cut = maxChars - TRUNCATION_MARKER.length();
        if (Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut) + TRUNCATION_MARKER;
    }
}

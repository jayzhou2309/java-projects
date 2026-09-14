package project.stockrecommendationengine.rag.evaluation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Where an accepted phrase occurs in a stored chunk's text, by the containment rule of {@link RetrievalEvaluationService#matches}
 * (whitespace runs collapsed to one space, trimmed, lower-cased with {@link Locale#ROOT}): every occurrence of the normalised phrase
 * in the normalised text, overlapping occurrences included, mapped back to UTF-16 offsets of the stored text. A normalised character
 * maps to the stored characters it came from (a collapsed space to its whole whitespace run), so an occurrence spans from its first
 * character's first stored character to its last character's last stored character.
 */
final class PhraseOccurrences {
    /** Vertical tab, one of the characters {@code \s} matches. */
    private static final char VERTICAL_TAB = 11;

    private PhraseOccurrences() {
    }

    /** One occurrence, stored-text offsets {@code [start, end)}. */
    record CharacterSpan(int start, int end) {
    }

    /**
     * Every occurrence in order of start, empty when the normalised text does not contain the normalised phrase (or the phrase
     * normalises to nothing). Null when the mapped normalisation of {@code text} differs from
     * {@link RetrievalEvaluationService#normalise} (a context-dependent lower-casing, such as a final sigma), so positions are not
     * reported rather than approximated.
     */
    static List<CharacterSpan> find(String text, String phrase) {
        String wanted = RetrievalEvaluationService.normalise(phrase);
        Mapped mapped = map(text);
        if (!mapped.text().equals(RetrievalEvaluationService.normalise(text))) return null;
        List<CharacterSpan> out = new ArrayList<>();
        if (wanted.isEmpty()) return out;
        for (int at = mapped.text().indexOf(wanted); at >= 0; at = mapped.text().indexOf(wanted, at + 1)) {
            out.add(new CharacterSpan(mapped.starts()[at], mapped.ends()[at + wanted.length() - 1]));
        }
        return out;
    }

    /** The normalised text and, per normalised character, the stored characters {@code [starts[i], ends[i])} it came from. */
    record Mapped(String text, int[] starts, int[] ends) {
    }

    static Mapped map(String text) {
        // replaceAll("\\s+", " "): without UNICODE_CHARACTER_CLASS, \s is space, tab, line feed, vertical tab, form feed, carriage return.
        StringBuilder collapsed = new StringBuilder(text.length());
        int[] starts = new int[text.length()];
        int[] ends = new int[text.length()];
        int n = 0;
        for (int i = 0; i < text.length(); ) {
            if (isRegexWhitespace(text.charAt(i))) {
                int run = i;
                while (i < text.length() && isRegexWhitespace(text.charAt(i))) i++;
                collapsed.append(' ');
                starts[n] = run;
                ends[n++] = i;
            } else {
                collapsed.append(text.charAt(i));
                starts[n] = i;
                ends[n++] = ++i;
            }
        }
        // trim(): drops leading and trailing characters at or below U+0020.
        int first = 0;
        int last = n;
        while (first < last && collapsed.charAt(first) <= ' ') first++;
        while (last > first && collapsed.charAt(last - 1) <= ' ') last--;
        // toLowerCase(Locale.ROOT) per code point, each output character mapped to the code point's stored characters.
        StringBuilder lower = new StringBuilder(last - first);
        int[] lowerStarts = new int[Math.max(16, 2 * (last - first))];
        int[] lowerEnds = new int[lowerStarts.length];
        int m = 0;
        for (int i = first; i < last; ) {
            int units = Character.charCount(collapsed.codePointAt(i));
            if (i + units > last) units = 1;
            String lowered = collapsed.substring(i, i + units).toLowerCase(Locale.ROOT);
            for (int k = 0; k < lowered.length(); k++) {
                if (m == lowerStarts.length) {
                    lowerStarts = Arrays.copyOf(lowerStarts, 2 * m);
                    lowerEnds = Arrays.copyOf(lowerEnds, 2 * m);
                }
                lowerStarts[m] = starts[i];
                lowerEnds[m++] = ends[i + units - 1];
            }
            lower.append(lowered);
            i += units;
        }
        return new Mapped(lower.toString(), Arrays.copyOf(lowerStarts, m), Arrays.copyOf(lowerEnds, m));
    }

    private static boolean isRegexWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == VERTICAL_TAB || c == '\f' || c == '\r';
    }
}

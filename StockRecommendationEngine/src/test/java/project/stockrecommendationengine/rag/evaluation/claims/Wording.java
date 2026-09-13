package project.stockrecommendationengine.rag.evaluation.claims;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The wording screen (RAG.md, Claims, Wording), applied to the free text of inferred, unknown, and experiment claims, to labels, and to the
 * line introducing each generated block. It matches words, not meaning. Each screen reads the text three ways and reports every distinct
 * match of any of them, in order: as written; normalised, with markdown emphasis and code marks removed; and normalised with those marks read
 * as spaces. Normalising decodes numeric character references and a few named ones ({@code &shy;}, {@code &zwj;}, {@code &nbsp;}, ...),
 * removes HTML tags and comments, keeps a markdown link's text and drops its target, removes every Unicode format character (category Cf: the
 * soft hyphen U+00AD, zero-width space, non-joiner and joiner U+200B to U+200D, word joiner U+2060, byte order mark U+FEFF, bidirectional
 * marks), reads curly apostrophes as {@code '}, and drops a backslash escaping punctuation; the marks are {@code *}, {@code _}, {@code `},
 * and {@code ~}.
 */
final class Wording {
    /** Causal stems and phrases, matched from the start of a word in any case; a space matches any run of whitespace. */
    static final List<String> CAUSAL_STEMS = List.of("caus\\w*", "because", "result\\w*", "lead", "leads", "leading", "led", "driv\\w*", "drove",
            "lift\\w*", "push\\w*", "boost\\w*", "improv\\w*", "trigger\\w*", "contribut\\w*", "stem from", "stems from", "stemmed from",
            "stemming from", "account for", "accounts for", "accounted for", "accounting for", "responsib\\w*", "reason\\w*", "why", "thus",
            "therefore", "hence", "consequen\\w*", "due to", "thanks to", "owing to", "so that", "attribut\\w*", "explain\\w*", "make", "makes",
            "made", "making", "affect\\w*", "effect\\w*", "impact\\w*", "help\\w*", "hurt\\w*", "enabl\\w*", "prevent\\w*", "forc\\w*",
            "induc\\w*", "yield\\w*", "rais\\w*", "put", "puts", "putting", "giv\\w*", "gave", "is behind", "are behind", "was behind",
            "were behind", "be behind", "been behind", "being behind", "lie behind", "lies behind", "lay behind", "source of", "sources of",
            "arise\\w*", "arising", "arose", "come from", "comes from", "came from", "coming from", "originat\\w*", "bring about",
            "brings about", "brought about", "bringing about", "on account of", "in response to");
    /**
     * Absolute or predictive wording: "no" followed by a word (so "no row", "no trace", "no one"; "not recorded" and "does not record" pass),
     * contractions of will ("it'll"), and the listed stems.
     */
    static final List<String> ABSOLUTE_STEMS = List.of("always", "never", "will", "won't", "shall", "shan't", "\\w+'ll", "every\\w*", "all",
            "none", "nothing", "no[\\s-]+\\w+", "nobody", "nowhere", "guarantee\\w*", "invariabl\\w*", "forever", "certain\\w*", "each",
            "any\\w*", "whole", "entire\\w*", "must", "consistent\\w*", "going to", "gonna", "expect\\w*", "prove", "proves", "proved",
            "proven", "proving", "proof");
    /** Spelled-out numbers: cardinals (with -s or -fold), ordinals (with -s), and multiplicatives and fractions. */
    static final List<String> NUMBER_WORDS = List.of(
            "(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen"
                    + "|nineteen|twenty|thirty|forty|fourty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|million|billion|trillion|dozen)"
                    + "(?:s|fold)?",
            "(?:first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|(?:thir|four|fif|six|seven|eigh|nine)teenth"
                    + "|(?:twen|thir|for|four|fif|six|seven|eigh|nine)tieth|hundredth|thousandth|millionth|billionth|trillionth)s?",
            "once", "twice", "thrice", "single", "double", "triple", "quadruple", "half", "halves", "quarter", "quarters");
    static final Pattern CAUSAL = words(CAUSAL_STEMS);
    static final Pattern ABSOLUTE = words(ABSOLUTE_STEMS);
    /** A run of digits (any Unicode number: decimal digits, superscripts, fractions, Roman numeral letters) or a spelled-out number. */
    static final Pattern NUMBER = Pattern.compile("\\p{N}+|\\b(?:" + String.join("|", NUMBER_WORDS) + ")\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    /** A claim citation's opening: a parenthesis, optional whitespace, C in either case, a hyphen or dash (spaces allowed around it), and digits. */
    static final Pattern CITATION = Pattern.compile("\\(\\s*[Cc]\\s*[-\u2010-\u2015\u2212]\\s*\\d+");
    static final String NORMALISED = "read with soft hyphens, invisible characters, and markdown and HTML marks removed";

    private static final Pattern REFERENCE = Pattern.compile("&(?:#([0-9]{1,7})|#[xX]([0-9A-Fa-f]{1,6})|([A-Za-z]+));");
    private static final Map<String, String> NAMED = Map.ofEntries(Map.entry("shy", ""), Map.entry("zwj", ""), Map.entry("zwnj", ""),
            Map.entry("ZeroWidthSpace", ""), Map.entry("NoBreak", ""), Map.entry("lrm", ""), Map.entry("rlm", ""), Map.entry("nbsp", " "),
            Map.entry("amp", "&"), Map.entry("apos", "'"), Map.entry("rsquo", "'"), Map.entry("lsquo", "'"), Map.entry("ast", "*"),
            Map.entry("lowbar", "_"), Map.entry("grave", "`"));
    private static final Pattern TAG = Pattern.compile("<!--.*?-->|</?[A-Za-z][^<>]*>");
    private static final Pattern LINK = Pattern.compile("!?\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern ESCAPE = Pattern.compile("\\\\(\\p{Punct})");
    private static final Pattern MARKS = Pattern.compile("[*_`~]");

    private Wording() {
    }

    /** The distinct matches of one screen, in order, and whether any was found only in the normalised readings. */
    record Found(List<String> words, boolean normalised) {
        boolean isEmpty() {
            return words.isEmpty();
        }

        /** {@code "a", "b"}, followed by the normalisation note when a match was found only after normalising. */
        String quoted() {
            return words.stream().map(word -> "\"" + word + "\"").collect(Collectors.joining(", ")) + (normalised ? " (" + NORMALISED + ")" : "");
        }
    }

    static Found causal(String text) {
        return screen(CAUSAL, text);
    }

    static Found absolute(String text) {
        return screen(ABSOLUTE, text);
    }

    /** Digits and spelled-out numbers. */
    static Found numbers(String text) {
        return screen(NUMBER, text);
    }

    /** Every claim citation opening, {@code (C-} and digits, in the text as written or normalised. */
    static Found citations(String text) {
        return screen(CITATION, text);
    }

    /** The text normalised as described on the class, with markdown marks removed ({@code marks} "") or read as spaces ({@code marks} " "). */
    static String normalise(String text, String marks) {
        Matcher reference = REFERENCE.matcher(text);
        StringBuilder decoded = new StringBuilder();
        while (reference.find()) {
            String replacement = reference.group();
            try {
                if (reference.group(1) != null) replacement = Character.toString(Integer.parseInt(reference.group(1)));
                else if (reference.group(2) != null) replacement = Character.toString(Integer.parseInt(reference.group(2), 16));
                else replacement = NAMED.getOrDefault(reference.group(3), replacement);
            } catch (IllegalArgumentException notACodePoint) {
                replacement = reference.group();
            }
            reference.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
        }
        reference.appendTail(decoded);
        String normalised = LINK.matcher(TAG.matcher(decoded.toString()).replaceAll("")).replaceAll("$1");
        normalised = normalised.codePoints().filter(codePoint -> Character.getType(codePoint) != Character.FORMAT)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        normalised = normalised.replace('’', '\'').replace('‘', '\'').replace('ʼ', '\'');
        normalised = ESCAPE.matcher(normalised).replaceAll("$1");
        return MARKS.matcher(normalised).replaceAll(marks);
    }

    private static Found screen(Pattern pattern, String text) {
        Set<String> asWritten = new LinkedHashSet<>(find(pattern, text));
        Set<String> found = new LinkedHashSet<>(asWritten);
        found.addAll(find(pattern, normalise(text, "")));
        found.addAll(find(pattern, normalise(text, " ")));
        Set<String> writtenLower = asWritten.stream().map(word -> word.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        boolean normalised = found.stream().anyMatch(word -> !writtenLower.contains(word.toLowerCase(Locale.ROOT)));
        return new Found(new ArrayList<>(found), normalised);
    }

    private static List<String> find(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) found.add(matcher.group());
        return found;
    }

    private static Pattern words(List<String> stems) {
        return Pattern.compile("\\b(?:" + stems.stream().map(stem -> stem.replace(" ", "\\s+")).collect(Collectors.joining("|")) + ")\\b",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    }
}

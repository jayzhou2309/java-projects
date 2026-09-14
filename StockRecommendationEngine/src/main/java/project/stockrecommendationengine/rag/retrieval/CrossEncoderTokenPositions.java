package project.stockrecommendationengine.rag.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a passage's tokens fall in the cross-encoder's rows, computed with the scorer's own arithmetic
 * ({@link CrossEncoderPairAssembler#windowLength}, {@link CrossEncoderPairAssembler#windowStarts}) and input bound
 * ({@link CrossEncoderInputBounds}), for the evaluation evidence report (RAG.md, Retrieval Evaluation, Evidence report). Nothing here
 * tokenizes or scores; token spans come from a {@link PassageTokenizer}. Imports nothing from DJL or ONNX Runtime.
 */
public final class CrossEncoderTokenPositions {
    private static final Pattern MAX_WINDOW_LABEL = Pattern.compile("max-window/overlap=(\\d{1,9})/maxWindows=(\\d{1,9})");

    private CrossEncoderTokenPositions() {
    }

    /** Tokens {@code [start, end)} of a tokenization, end exclusive. */
    public record TokenSpan(int start, int end) {
    }

    /** Whether a token span lies inside the head window, the first {@code W} tokens (the only window under head scoring). */
    public enum HeadMembership {
        /** The span ends at or before {@code W}. */
        WHOLLY,
        /** The span starts before {@code W} and ends after it: the head cut splits it. */
        PARTLY,
        /** The span starts at or after {@code W}. */
        NOT
    }

    /**
     * Passage scoring settings as a snapshot records them in {@code properties.rerankerScoring} ({@link PairScorer#scoring()}):
     * {@code head}, or {@code max-window/overlap=<windowOverlapTokens>/maxWindows=<maxWindows>}. Overlap and window count are null
     * under head scoring, which scores one window.
     */
    public record Scoring(PassageScoring mode, Integer windowOverlapTokens, Integer maxWindows) {
        public static Scoring head() {
            return new Scoring(PassageScoring.HEAD, null, null);
        }

        public static Scoring maxWindow(int windowOverlapTokens, int maxWindows) {
            return new Scoring(PassageScoring.MAX_WINDOW, windowOverlapTokens, maxWindows);
        }

        /** The recorded form: {@code head} or {@code max-window/overlap=64/maxWindows=4}. */
        public String label() {
            if (mode == PassageScoring.HEAD) return PassageScoring.HEAD.label();
            return PassageScoring.MAX_WINDOW.label() + "/overlap=" + windowOverlapTokens + "/maxWindows=" + maxWindows;
        }

        /** Parses {@link #label()}; an {@link IllegalArgumentException} for any other text, a max-windows of 0 included. */
        public static Scoring parse(String recorded) {
            if (PassageScoring.HEAD.label().equals(recorded)) return head();
            Matcher matcher = recorded == null ? null : MAX_WINDOW_LABEL.matcher(recorded);
            if (matcher == null || !matcher.matches()) throw new IllegalArgumentException("unrecognised rerankerScoring '" + recorded + "'");
            int maxWindows = Integer.parseInt(matcher.group(2));
            if (maxWindows < 1) throw new IllegalArgumentException("unrecognised rerankerScoring '" + recorded + "'");
            return maxWindow(Integer.parseInt(matcher.group(1)), maxWindows);
        }
    }

    /** The text as the scorer bounds a query or passage before tokenizing it ({@link CrossEncoderInputBounds#bound}); offsets are unchanged. */
    public static String bound(String text) {
        return CrossEncoderInputBounds.bound(text);
    }

    /** Longest text, in UTF-16 units, the scorer tokenizes. */
    public static int maxChars() {
        return CrossEncoderInputBounds.MAX_CHARS;
    }

    /** W: the passage tokens one row holds beside a query of {@code queryTokens} tokens ({@link CrossEncoderPairAssembler#windowLength}). */
    public static int windowLength(int queryTokens, int passageTokens, int maxLength) {
        return CrossEncoderPairAssembler.windowLength(queryTokens, passageTokens, maxLength);
    }

    /**
     * Start token of each row that scores a passage of {@code passageTokens} tokens with windows of {@code windowLength} tokens: one row
     * at 0 under head scoring, {@link CrossEncoderPairAssembler#windowStarts} under max-window scoring.
     */
    public static int[] windowStarts(int passageTokens, int windowLength, Scoring scoring) {
        if (scoring.mode() == PassageScoring.HEAD) return new int[] {0};
        return CrossEncoderPairAssembler.windowStarts(passageTokens, windowLength, scoring.windowOverlapTokens(), scoring.maxWindows());
    }

    /**
     * The tokens whose character span overlaps characters {@code [charStart, charEnd)}: from the first token that ends after
     * {@code charStart} to the last token that starts before {@code charEnd}. Null when no token overlaps (the characters yield no token).
     */
    public static TokenSpan tokenSpan(PassageTokenizer.Tokens tokens, int charStart, int charEnd) {
        int first = -1;
        int last = -1;
        for (int token = 0; token < tokens.count(); token++) {
            if (tokens.ends()[token] > charStart && tokens.starts()[token] < charEnd) {
                if (first < 0) first = token;
                last = token;
            }
        }
        return first < 0 ? null : new TokenSpan(first, last + 1);
    }

    /** {@link HeadMembership} of {@code span} against a head window of {@code windowLength} tokens. */
    public static HeadMembership head(TokenSpan span, int windowLength) {
        if (span.end() <= windowLength) return HeadMembership.WHOLLY;
        if (span.start() < windowLength) return HeadMembership.PARTLY;
        return HeadMembership.NOT;
    }

    /**
     * 1-based indexes, in window order, of the rows that hold {@code span} wholly: row {@code k} covers tokens
     * {@code [starts[k], starts[k] + min(windowLength, passageTokens))}, the tokens {@link CrossEncoderPairAssembler#windows} puts in
     * it. Empty when no row holds the whole span.
     */
    public static List<Integer> windowsHoldingWholly(int passageTokens, int windowLength, int[] starts, TokenSpan span) {
        int length = Math.min(windowLength, passageTokens);
        List<Integer> out = new ArrayList<>();
        for (int index = 0; index < starts.length; index++) {
            if (starts[index] <= span.start() && span.end() <= starts[index] + length) out.add(index + 1);
        }
        return List.copyOf(out);
    }

    /**
     * Spans given as code point offsets into {@code text}, converted to UTF-16 offsets. Fails on an offset outside the text or a span
     * whose end precedes its start.
     */
    static PassageTokenizer.Tokens utf16Spans(String text, int[] codePointStarts, int[] codePointEnds) {
        int codePoints = text.codePointCount(0, text.length());
        int[] utf16 = new int[codePoints + 1];
        for (int codePoint = 0, index = 0; codePoint < codePoints; codePoint++) {
            utf16[codePoint] = index;
            index += Character.charCount(text.codePointAt(index));
            utf16[codePoint + 1] = index;
        }
        int[] starts = new int[codePointStarts.length];
        int[] ends = new int[codePointStarts.length];
        for (int token = 0; token < codePointStarts.length; token++) {
            int start = codePointStarts[token];
            int end = codePointEnds[token];
            if (start < 0 || end < start || end > codePoints) {
                throw new IllegalStateException("Token " + token + " span [" + start + ", " + end + ") is outside a text of " + codePoints + " code points");
            }
            starts[token] = utf16[start];
            ends[token] = utf16[end];
        }
        return new PassageTokenizer.Tokens(starts, ends);
    }
}

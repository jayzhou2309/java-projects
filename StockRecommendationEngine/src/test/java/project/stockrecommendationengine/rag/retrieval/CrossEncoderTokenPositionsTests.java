package project.stockrecommendationengine.rag.retrieval;

import java.util.List;
import org.junit.jupiter.api.Test;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderTokenPositions.HeadMembership;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderTokenPositions.Scoring;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderTokenPositions.TokenSpan;
import static org.assertj.core.api.Assertions.*;

/**
 * Evaluation evidence Milestone 2, D1: token spans, head membership, and the windows holding a phrase, against values computed by hand
 * in the comments from the rules in CrossEncoderPairAssembler's Javadoc (longest-first kept lengths; windows start at 0, advance by
 * W - overlap while the window does not reach the last token, the last one moved back to end at the last token, at most max-windows).
 * A scripted word tokenizer makes every word one token. No model or native library.
 */
class CrossEncoderTokenPositionsTests {
    private final ScriptedWordTokenizer tokenizer = new ScriptedWordTokenizer("test-version");

    @Test
    void aPhraseSplitByTheHeadCutAndOneStraddlingAWindowBoundaryAtOverlap0() {
        // maxLength 20, query 3 tokens, chunk 40 tokens: budget 17, 3 + 40 > 17, the shorter (3) is at most half, so the query keeps 3; W = 17 - 3 = 14.
        int w = CrossEncoderTokenPositions.windowLength(3, 40, 20);
        assertThat(w).isEqualTo(14);
        // Overlap 0, stride 14: starts 0 (0 + 14 < 40), 14 (28 < 40), then 28 + 14 = 42 does not; the final window starts at 40 - 14 = 26.
        int[] starts = CrossEncoderTokenPositions.windowStarts(40, w, Scoring.maxWindow(0, 4));
        assertThat(starts).containsExactly(0, 14, 26);
        // Tokens 12..15 cross the head cut at 14 and the boundary between windows 1 [0, 14) and 2 [14, 28); window 3 is [26, 40).
        TokenSpan split = span(ScriptedWordTokenizer.words(40), "w012 w013 w014 w015");
        assertThat(split).isEqualTo(new TokenSpan(12, 16));
        assertThat(CrossEncoderTokenPositions.head(split, w)).isEqualTo(HeadMembership.PARTLY);
        assertThat(CrossEncoderTokenPositions.windowsHoldingWholly(40, w, starts, split)).isEmpty();
        // Tokens 3..4 lie in the head and window 1 only; tokens 30..31 only in window 3; tokens 26..27 in windows 2 and 3 (their overlap).
        assertThat(CrossEncoderTokenPositions.head(new TokenSpan(3, 5), w)).isEqualTo(HeadMembership.WHOLLY);
        assertThat(CrossEncoderTokenPositions.windowsHoldingWholly(40, w, starts, new TokenSpan(3, 5))).containsExactly(1);
        assertThat(CrossEncoderTokenPositions.head(new TokenSpan(30, 32), w)).isEqualTo(HeadMembership.NOT);
        assertThat(CrossEncoderTokenPositions.windowsHoldingWholly(40, w, starts, new TokenSpan(30, 32))).containsExactly(3);
        assertThat(CrossEncoderTokenPositions.windowsHoldingWholly(40, w, starts, new TokenSpan(26, 28))).containsExactly(2, 3);
        // A span ending exactly at W is wholly in the head; one starting exactly at W is not in it.
        assertThat(CrossEncoderTokenPositions.head(new TokenSpan(10, 14), w)).isEqualTo(HeadMembership.WHOLLY);
        assertThat(CrossEncoderTokenPositions.head(new TokenSpan(14, 15), w)).isEqualTo(HeadMembership.NOT);
        // Overlap 4, stride 10: starts 0, 10, 20 (20 + 14 = 34 < 40), then 30 + 14 = 44 does not; final 26. Window 2 [10, 24) holds 12..15.
        int[] overlapping = CrossEncoderTokenPositions.windowStarts(40, w, Scoring.maxWindow(4, 4));
        assertThat(overlapping).containsExactly(0, 10, 20, 26);
        assertThat(CrossEncoderTokenPositions.windowsHoldingWholly(40, w, overlapping, split)).containsExactly(2);
    }

    @Test
    void overlap0And224AtTheModelWindow() {
        // maxLength 512, query 9 tokens, chunk 1,200 tokens: budget 509, the query keeps 9, W = 500.
        int w = CrossEncoderTokenPositions.windowLength(9, 1200, 512);
        assertThat(w).isEqualTo(500);
        // Overlap 0, stride 500: 0 (500 < 1200), 500 (1000 < 1200), then 1000 + 500 = 1500 does not; final 1200 - 500 = 700.
        int[] disjoint = CrossEncoderTokenPositions.windowStarts(1200, w, Scoring.maxWindow(0, 4));
        assertThat(disjoint).containsExactly(0, 500, 700);
        // Overlap 224, stride 276: 0, 276 (776 < 1200), 552 (1052 < 1200), then 828 + 500 = 1328 does not; final 700.
        int[] overlapping = CrossEncoderTokenPositions.windowStarts(1200, w, Scoring.maxWindow(224, 4));
        assertThat(overlapping).containsExactly(0, 276, 552, 700);
        String chunk = ScriptedWordTokenizer.words(1200);
        // Tokens 495..504 straddle the boundary at 500: no window holds them at overlap 0; window 2 [276, 776) does at overlap 224.
        TokenSpan straddling = span(chunk, ScriptedWordTokenizer.words(495, 505));
        assertThat(straddling).isEqualTo(new TokenSpan(495, 505));
        assertThat(CrossEncoderTokenPositions.head(straddling, w)).isEqualTo(HeadMembership.PARTLY);
        assertThat(CrossEncoderTokenPositions.windowsHoldingWholly(1200, w, disjoint, straddling)).isEmpty();
        assertThat(CrossEncoderTokenPositions.windowsHoldingWholly(1200, w, overlapping, straddling)).containsExactly(2);
        // Tokens 1190..1194: window 3 [700, 1200) at overlap 0, window 4 [700, 1200) at overlap 224; not in the head.
        TokenSpan tail = span(chunk, ScriptedWordTokenizer.words(1190, 1195));
        assertThat(tail).isEqualTo(new TokenSpan(1190, 1195));
        assertThat(CrossEncoderTokenPositions.head(tail, w)).isEqualTo(HeadMembership.NOT);
        assertThat(CrossEncoderTokenPositions.windowsHoldingWholly(1200, w, disjoint, tail)).containsExactly(3);
        assertThat(CrossEncoderTokenPositions.windowsHoldingWholly(1200, w, overlapping, tail)).containsExactly(4);
        // Head scoring scores one window at 0: only a span wholly inside the first W tokens is held.
        int[] head = CrossEncoderTokenPositions.windowStarts(1200, w, Scoring.head());
        assertThat(head).containsExactly(0);
        assertThat(CrossEncoderTokenPositions.windowsHoldingWholly(1200, w, head, straddling)).isEmpty();
        assertThat(CrossEncoderTokenPositions.windowsHoldingWholly(1200, w, head, new TokenSpan(10, 20))).containsExactly(1);
        // A 3,000-token chunk at overlap 0 with at most 4 windows: starts 0, 500, 1000, 1500 (the fourth stops the loop, no final window),
        // so the rows cover tokens 0..1999 and a phrase at 2500 is held by none.
        int[] capped = CrossEncoderTokenPositions.windowStarts(3000, CrossEncoderTokenPositions.windowLength(9, 3000, 512), Scoring.maxWindow(0, 4));
        assertThat(capped).containsExactly(0, 500, 1000, 1500);
        assertThat(CrossEncoderTokenPositions.windowsHoldingWholly(3000, 500, capped, new TokenSpan(2500, 2502))).isEmpty();
        assertThat(CrossEncoderTokenPositions.windowsHoldingWholly(3000, 500, capped, new TokenSpan(1998, 2000))).containsExactly(4);
    }

    @Test
    void aChunkShorterThanWAndAnEmptyChunk() {
        // maxLength 20, query 3, chunk 10: 13 <= 17 keeps both whole; W = 14 > 10, so one window at 0 of the chunk's 10 tokens.
        int w = CrossEncoderTokenPositions.windowLength(3, 10, 20);
        assertThat(w).isEqualTo(14);
        int[] starts = CrossEncoderTokenPositions.windowStarts(10, w, Scoring.maxWindow(0, 4));
        assertThat(starts).containsExactly(0);
        TokenSpan last = span(ScriptedWordTokenizer.words(10), "w008 w009");
        assertThat(last).isEqualTo(new TokenSpan(8, 10));
        assertThat(CrossEncoderTokenPositions.head(last, w)).isEqualTo(HeadMembership.WHOLLY);
        assertThat(CrossEncoderTokenPositions.windowsHoldingWholly(10, w, starts, last)).containsExactly(1);
        // An empty chunk has 0 tokens and one window at 0 under both modes; W beside a 3-token query is 14, and 0 beside a query that
        // fills the budget (20 tokens: the shorter side, 0, is at most half, so the query keeps 17). No character range maps to a token.
        PassageTokenizer.Tokens empty = tokenizer.tokenize("");
        assertThat(empty.count()).isZero();
        assertThat(CrossEncoderTokenPositions.windowLength(3, 0, 20)).isEqualTo(14);
        assertThat(CrossEncoderTokenPositions.windowLength(20, 0, 20)).isZero();
        assertThat(CrossEncoderTokenPositions.windowStarts(0, 14, Scoring.maxWindow(0, 4))).containsExactly(0);
        assertThat(CrossEncoderTokenPositions.windowStarts(0, 0, Scoring.maxWindow(64, 4))).containsExactly(0);
        assertThat(CrossEncoderTokenPositions.windowStarts(0, 14, Scoring.head())).containsExactly(0);
        assertThat(CrossEncoderTokenPositions.tokenSpan(empty, 0, 0)).isNull();
    }

    @Test
    void theTokenSpanIsEveryTokenOverlappingTheCharactersIncludingPartlyCoveredWords() {
        PassageTokenizer.Tokens tokens = tokenizer.tokenize("alpha beta gamma");
        // alpha [0, 5), beta [6, 10), gamma [11, 16).
        assertThat(CrossEncoderTokenPositions.tokenSpan(tokens, 0, 16)).isEqualTo(new TokenSpan(0, 3));
        assertThat(CrossEncoderTokenPositions.tokenSpan(tokens, 2, 8)).isEqualTo(new TokenSpan(0, 2));
        assertThat(CrossEncoderTokenPositions.tokenSpan(tokens, 9, 12)).isEqualTo(new TokenSpan(1, 3));
        assertThat(CrossEncoderTokenPositions.tokenSpan(tokens, 6, 10)).isEqualTo(new TokenSpan(1, 2));
        assertThat(CrossEncoderTokenPositions.tokenSpan(tokens, 5, 6)).isNull();
        assertThat(CrossEncoderTokenPositions.tokenSpan(tokens, 10, 11)).isNull();
    }

    @Test
    void codePointSpansBecomeUtf16Offsets() {
        // The native tokenizer's spans for "a 😀 revenue" (measured, CrossEncoderPassageTokenizerLiveTests): a 0-1, [UNK] 2-3, revenue 4-11
        // in code points; the emoji is two UTF-16 units, so revenue is [5, 12).
        String text = "a 😀 revenue";
        PassageTokenizer.Tokens tokens = CrossEncoderTokenPositions.utf16Spans(text, new int[] {0, 2, 4}, new int[] {1, 3, 11});
        assertThat(tokens.starts()).containsExactly(0, 2, 5);
        assertThat(tokens.ends()).containsExactly(1, 4, 12);
        assertThat(text.substring(tokens.starts()[2], tokens.ends()[2])).isEqualTo("revenue");
        assertThat(CrossEncoderTokenPositions.utf16Spans("", new int[0], new int[0]).count()).isZero();
        assertThatThrownBy(() -> CrossEncoderTokenPositions.utf16Spans(text, new int[] {0}, new int[] {12}))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside a text of 11 code points");
        assertThatThrownBy(() -> CrossEncoderTokenPositions.utf16Spans(text, new int[] {3}, new int[] {2})).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recordedScoringLabelsParseAndRoundTrip() {
        assertThat(Scoring.parse("head")).isEqualTo(new Scoring(PassageScoring.HEAD, null, null));
        assertThat(Scoring.parse("max-window/overlap=64/maxWindows=4")).isEqualTo(new Scoring(PassageScoring.MAX_WINDOW, 64, 4));
        assertThat(Scoring.parse("max-window/overlap=224/maxWindows=4").windowOverlapTokens()).isEqualTo(224);
        for (Scoring scoring : List.of(Scoring.head(), Scoring.maxWindow(0, 1), Scoring.maxWindow(64, 4), Scoring.maxWindow(256, 16))) {
            assertThat(Scoring.parse(scoring.label())).isEqualTo(scoring);
        }
        assertThat(Scoring.maxWindow(64, 4).label()).isEqualTo("max-window/overlap=64/maxWindows=4");
        for (String invalid : new String[] {null, "", "HEAD", "max-window", "max-window/overlap=64/maxWindows=0", "max-window/overlap=-1/maxWindows=4",
                "max-window/overlap=64/maxWindows=4/extra"}) {
            assertThatThrownBy(() -> Scoring.parse(invalid)).as(String.valueOf(invalid)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("unrecognised rerankerScoring '" + invalid + "'");
        }
    }

    private TokenSpan span(String chunk, String phrase) {
        int start = chunk.indexOf(phrase);
        assertThat(start).as("phrase in chunk").isNotNegative();
        return CrossEncoderTokenPositions.tokenSpan(tokenizer.tokenize(chunk), start, start + phrase.length());
    }
}

package project.stockrecommendationengine.rag.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** The Java-side character bound applied to every query and passage before the native tokenizer; no model needed. */
class CrossEncoderInputBoundsTests {
    private static final String EMOJI = "\uD83D\uDE00";
    private static final String HIGH = "\uD800";
    private static final String LOW = "\uDC00";
    private static final String REPLACEMENT = "\uFFFD";

    @Test
    void nullBecomesEmptyAndShortWellFormedTextIsReturnedAsIs() {
        assertThat(CrossEncoderInputBounds.bound(null)).isEmpty();
        String text = "What was Data Center revenue? \uD83D\uDCC8 caf\u00E9 \u6570\u636E";
        assertThat(CrossEncoderInputBounds.bound(text)).isSameAs(text);
        assertThat(CrossEncoderInputBounds.MAX_CHARS).isEqualTo(20_000);
    }

    @Test
    void textLongerThanTheBoundIsCutToTheBound() {
        String longText = "revenue ".repeat(5_000); // 40,000 chars
        String bounded = CrossEncoderInputBounds.bound(longText);
        assertThat(bounded).hasSize(20_000);
        assertThat(longText).startsWith(bounded);
        assertThat(CrossEncoderInputBounds.bound("x".repeat(20_000))).hasSize(20_000);
        assertThat(CrossEncoderInputBounds.bound("x".repeat(20_001))).hasSize(20_000);
    }

    @Test
    void theCutNeverSplitsASurrogatePair() {
        assertThat(EMOJI).hasSize(2);
        // The pair straddles the bound: chars 9 and 10 of an 11-char string cut at 10.
        String text = "x".repeat(9) + EMOJI;
        assertThat(CrossEncoderInputBounds.bound(text, 10)).isEqualTo("x".repeat(9));
        assertThat(CrossEncoderInputBounds.bound(text, 11)).isEqualTo(text);
        String emojis = EMOJI.repeat(15_000); // 30,000 chars
        assertThat(CrossEncoderInputBounds.bound(emojis)).hasSize(20_000).isEqualTo(EMOJI.repeat(10_000));
        assertThat(CrossEncoderInputBounds.bound("a" + emojis)).hasSize(19_999).isEqualTo("a" + EMOJI.repeat(9_999));
    }

    @Test
    void unpairedSurrogatesAreReplacedAndPairsKept() {
        assertThat(CrossEncoderInputBounds.bound("a" + HIGH + "b")).isEqualTo("a" + REPLACEMENT + "b");
        assertThat(CrossEncoderInputBounds.bound("a" + LOW + "b")).isEqualTo("a" + REPLACEMENT + "b");
        assertThat(CrossEncoderInputBounds.bound(HIGH)).isEqualTo(REPLACEMENT);
        assertThat(CrossEncoderInputBounds.bound(LOW + HIGH)).isEqualTo(REPLACEMENT + REPLACEMENT);
        assertThat(CrossEncoderInputBounds.bound(HIGH + EMOJI + LOW)).isEqualTo(REPLACEMENT + EMOJI + REPLACEMENT);
        assertThat(CrossEncoderInputBounds.bound(HIGH + HIGH + LOW)).isEqualTo(REPLACEMENT + HIGH + LOW);
        String withNul = "nul" + (char) 0 + "ok";
        assertThat(CrossEncoderInputBounds.bound(withNul)).isSameAs(withNul);
    }
}

package project.stockrecommendationengine.rag.evaluation;

import java.util.List;
import org.junit.jupiter.api.Test;
import project.stockrecommendationengine.rag.evaluation.PhraseOccurrences.CharacterSpan;
import static org.assertj.core.api.Assertions.*;

/** Occurrences of an accepted phrase under the RetrievalEvaluationService.matches rule, mapped back to stored-text offsets. */
class PhraseOccurrencesTests {
    private static final String NBSP = Character.toString(0x00A0);
    private static final String DOTTED_CAPITAL_I = Character.toString(0x0130);
    private static final String EMOJI = Character.toString(0x1F600);

    @Test
    void collapsedWhitespaceAndCaseMapBackToTheStoredCharacters() {
        String text = "  Intro w012  W013\n\tw014 w015 tail";
        // Normalised: "intro w012 w013 w014 w015 tail"; the phrase starts at stored index 8 ("w012") and ends after "w015" at 29.
        List<CharacterSpan> spans = PhraseOccurrences.find(text, "w012 w013 w014 w015");
        assertThat(spans).containsExactly(new CharacterSpan(8, 29));
        assertThat(text.substring(8, 29)).isEqualTo("w012  W013\n\tw014 w015");
        assertThat(PhraseOccurrences.find(text, "  W012   w013 ")).containsExactly(new CharacterSpan(8, 18));
    }

    @Test
    void everyOccurrenceIsReportedOverlappingOnesIncluded() {
        assertThat(PhraseOccurrences.find("abab ab abab", "abab")).containsExactly(new CharacterSpan(0, 4), new CharacterSpan(8, 12));
        assertThat(PhraseOccurrences.find("aaaa", "aaa")).containsExactly(new CharacterSpan(0, 3), new CharacterSpan(1, 4));
        assertThat(PhraseOccurrences.find("no match here", "absent phrase")).isEmpty();
        assertThat(PhraseOccurrences.find("anything", "   ")).isEmpty();
    }

    @Test
    void aNonBreakingSpaceIsNotWhitespaceForTheRuleAndIsKept() {
        // \s does not match U+00A0, so "a<nbsp>b" normalises to itself and does not contain "a b".
        assertThat(PhraseOccurrences.find("x a" + NBSP + "b y", "a b")).isEmpty();
        assertThat(PhraseOccurrences.find("x a" + NBSP + "b y", "A" + NBSP + "B")).containsExactly(new CharacterSpan(2, 5));
    }

    @Test
    void theMappedNormalisationEqualsTheMatchRuleOrPositionsAreNotReported() {
        String control = Character.toString(0x0001);
        for (String text : new String[] {"", " ", control + " lead and trail " + control, "Caf" + Character.toString(0x00C9) + " quoted",
                "a " + EMOJI + " emoji", DOTTED_CAPITAL_I + "stanbul", "vertical" + Character.toString(0x000B) + "tab"}) {
            assertThat(PhraseOccurrences.map(text).text()).as(text).isEqualTo(RetrievalEvaluationService.normalise(text));
        }
        // U+0130 lower-cases to two characters; both map to the one stored character, so the occurrence still ends at stored index 10.
        assertThat(PhraseOccurrences.find("x " + DOTTED_CAPITAL_I + "stanbul", DOTTED_CAPITAL_I + "stanbul")).containsExactly(new CharacterSpan(2, 10));
        // The emoji is two UTF-16 units: "emoji" starts at stored index 5.
        assertThat(PhraseOccurrences.find("a " + EMOJI + " emoji", "emoji")).containsExactly(new CharacterSpan(5, 10));
        // A word-final capital sigma lower-cases by context in String.toLowerCase, not per character: positions are withheld, not guessed.
        String greek = new String(new int[] {0x039F, 0x0394, 0x039F, 0x03A3}, 0, 4);
        assertThat(RetrievalEvaluationService.normalise(greek)).isNotEqualTo(PhraseOccurrences.map(greek).text());
        assertThat(PhraseOccurrences.find(greek, new String(new int[] {0x03BF, 0x03B4}, 0, 2))).isNull();
    }
}

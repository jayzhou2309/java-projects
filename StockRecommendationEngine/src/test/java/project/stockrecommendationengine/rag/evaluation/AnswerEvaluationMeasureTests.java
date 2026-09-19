package project.stockrecommendationengine.rag.evaluation;

import java.util.List;
import org.junit.jupiter.api.Test;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.evaluation.AnswerEvaluation.QuestionResult;
import project.stockrecommendationengine.rag.evaluation.AnswerEvaluationService.FigureCheck;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationQuestion.Kind;
import project.stockrecommendationengine.rag.repository.FilingRetrievalRepository;
import project.stockrecommendationengine.recommendation.EvaluationRun;
import project.stockrecommendationengine.recommendation.EvaluationRun.ShownPassage;
import project.stockrecommendationengine.recommendation.RecommendationResponse;
import static org.assertj.core.api.Assertions.*;
import static project.stockrecommendationengine.rag.evaluation.ScriptedAnswers.ACCESSION;
import static project.stockrecommendationengine.rag.evaluation.ScriptedAnswers.SECTION;
import static project.stockrecommendationengine.rag.evaluation.ScriptedAnswers.chunk;

/** The measure functions of the answer evaluation on hand-built runs: the edges the scripted passes do not reach. */
class AnswerEvaluationMeasureTests {
    @Test void aPhraseStraddlingTheCutIsNotVisibleAndOneVisibleChunkAmongSeveralIsEnough() {
        var question = question(Kind.NARRATIVE, "the expected phrase in full");
        RetrievedFilingChunk straddling = chunk(1, SECTION, "Lead-in. The expected phrase in full follows.");
        RetrievedFilingChunk whole = chunk(2, SECTION, "The expected phrase in full, early in the chunk. " + "x".repeat(100));
        RetrievedFilingChunk other = chunk(3, SECTION, "No phrase here.");
        var run = new EvaluationRun(response("PARTIAL", "NEUTRAL", "Reasoning.", List.of(other)), true, List.of(
                new ShownPassage(straddling, "Lead-in. The expected phrase in …[22 more characters not shown]"),
                new ShownPassage(whole, "The expected phrase in full, early in the chunk. …[100 more characters not shown]"),
                new ShownPassage(other, "No phrase here.")));
        QuestionResult result = AnswerEvaluationService.measure(question, run, 12);
        assertThat(result.expectedChunkIds()).containsExactly(1L, 2L);
        assertThat(result.visibleChunkIds()).containsExactly(2L);
        assertThat(result.visibleToModel()).isTrue();
        assertThat(result.citedExpected()).isFalse();
        assertThat(result.elapsedMs()).isEqualTo(12);

        var onlyStraddling = new EvaluationRun(run.response(), true, run.retrieved().subList(0, 1));
        assertThat(AnswerEvaluationService.measure(question, onlyStraddling, 0).retrievedExpected()).isTrue();
        assertThat(AnswerEvaluationService.measure(question, onlyStraddling, 0).visibleToModel()).isFalse();
    }

    @Test void withoutCapturedEvidenceTheRetrievalMeasuresAreUnknownNotFalse() {
        var run = new EvaluationRun(response("DEADLINE_EXCEEDED", "INSUFFICIENT_EVIDENCE", "", List.of()), false, List.of());
        QuestionResult result = AnswerEvaluationService.measure(question(Kind.FIGURE, "revenue of $12.5 billion was recorded"), run, 0);
        assertThat(result.evidenceCaptured()).isFalse();
        assertThat(result.retrievedExpected()).isNull();
        assertThat(result.visibleToModel()).isNull();
        assertThat(result.expectedChunkIds()).isNull();
        assertThat(result.citedExpected()).isFalse();
        assertThat(result.figuresInReasoning()).as("an empty reasoning holds no figure").isFalse();
        var aggregates = AnswerEvaluationService.aggregate(List.of(result));
        assertThat(aggregates.withRun()).isEqualTo(1);
        assertThat(aggregates.measured()).isZero();
        assertThat(aggregates.shareRetrieved()).isNull();
        assertThat(aggregates.statusCounts()).containsEntry("DEADLINE_EXCEEDED", 1);
    }

    @Test void figuresFollowTheRetrievalFigureRuleAndMatchByNumericValue() {
        // The rule is the retrieval layer's: numeric tokens of length 2 or more, years left out.
        assertThat(FilingRetrievalRepository.figureTokens("Greater China 64,377 (4) % 66,952 (8) % in 2025, up 15.6 %")).containsExactly("64,377", "66,952", "15.6");
        assertThat(FilingRetrievalRepository.figureTokens("increased during 2025 compared to 2024")).isEmpty();
        assertThat(FilingRetrievalRepository.figureTokens(null)).isEmpty();
        for (String text : List.of("revenue was $40.4 billion in 2026", "during 2025 compared to 2024", "no numbers", "")) {
            assertThat(FilingRetrievalRepository.figureTokens(text).isEmpty()).as(text).isEqualTo(FilingRetrievalRepository.figureTerms(text).isEmpty());
        }

        var question = question(Kind.FIGURE, "we repurchased 282 million shares of our common stock for $40.4 billion");
        assertThat(check(question, "Bought 282 million shares for $40.4 billion.").missing()).isEmpty();
        assertThat(check(question, "Bought 282 million shares for 40.40 billion.").missing()).as("same value, other formatting").isEmpty();
        assertThat(check(question, "Bought 282 million shares for about $40 billion.").missing()).containsExactly("40.4");
        assertThat(check(question, "Bought 1,282 million shares for $140.4 billion.").missing()).as("a figure inside a longer numeral does not count")
                .containsExactly("282", "40.4");
        assertThat(check(question(Kind.FIGURE, "we had approximately 42,000 employees in fiscal 2026"), "About 42000 people.").missing()).isEmpty();
        assertThat(check(question(Kind.FIGURE, "net sales were 64,377 million for the year"), "Sales were $64.4 billion.").missing())
                .as("a rescaled or rounded figure does not match").containsExactly("64,377");
        assertThat(check(question(Kind.FIGURE, "revenue was up 65% from a year ago"), "Revenue rose 65% year over year.").missing()).isEmpty();
        assertThat(check(question(Kind.FIGURE, "sales in the year 2,000 units"), "It sold 2000 units.").missing()).as("years are kept on the reasoning side").isEmpty();

        // Any one accepted phrase is enough; a phrase without a figure is skipped; none with a figure means no check.
        var alternatives = new RetrievalEvaluationQuestion("q", "TSTA", Kind.FIGURE, "Q?", List.of(
                new ExpectedPassage(ACCESSION, SECTION, "increased during 2025 compared to 2024"),
                new ExpectedPassage(ACCESSION, SECTION, "Revenue $ 215,938 $ 130,497 Up 65%"),
                new ExpectedPassage(ACCESSION, SECTION, "Data Center revenue was up 68% from a year ago")), null);
        assertThat(check(alternatives, "Data Center grew 68%.")).isEqualTo(new FigureCheck(List.of("68"), List.of()));
        assertThat(check(alternatives, "Revenue was 215,938.")).as("the phrase with the fewest missing figures, the first on a tie")
                .isEqualTo(new FigureCheck(List.of("68"), List.of("68")));
        assertThat(check(alternatives, "Revenue was 215,938, up from 130,497.")).isEqualTo(new FigureCheck(List.of("215,938", "130,497", "65"), List.of("65")));
        assertThat(check(question(Kind.FIGURE, "increased during 2025 compared to 2024"), "It increased in 2025.")).isNull();
        assertThat(AnswerEvaluationService.measure(question(Kind.FIGURE, "increased during 2025 compared to 2024"),
                new EvaluationRun(response("PARTIAL", "NEUTRAL", "It increased in 2025.", List.of()), true, List.of()), 0).figuresInReasoning()).isNull();
        assertThat(AnswerEvaluationService.measure(question(Kind.NARRATIVE, "revenue was up 65% from a year ago"),
                new EvaluationRun(response("PARTIAL", "NEUTRAL", "Revenue rose 65%.", List.of()), true, List.of()), 0).figuresInReasoning())
                .as("only FIGURE questions are checked").isNull();
    }

    private static FigureCheck check(RetrievalEvaluationQuestion question, String reasoning) {
        return AnswerEvaluationService.figures(question, reasoning);
    }

    private static RetrievalEvaluationQuestion question(Kind kind, String phrase) {
        return new RetrievalEvaluationQuestion("q", "TSTA", kind, "Q?", List.of(new ExpectedPassage(ACCESSION, SECTION, phrase)), null);
    }

    private static RecommendationResponse response(String status, String assessment, String reasoning, List<RetrievedFilingChunk> sources) {
        return new RecommendationResponse("00000000-0000-0000-0000-000000000001", "TSTA", status, assessment, reasoning, sources, List.of(),
                List.of(status), List.of(), 1, 100, null, null, null, null, null, null, null, null, null);
    }
}

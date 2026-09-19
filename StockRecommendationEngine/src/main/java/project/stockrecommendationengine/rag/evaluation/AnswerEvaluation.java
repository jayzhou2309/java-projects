package project.stockrecommendationengine.rag.evaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationQuestion.Kind;

/**
 * One answer-evaluation snapshot: a pass of evaluation-set questions through the recommendation loop, each as a run
 * stored with purpose EVALUATION, with deterministic per-question measures and the aggregates over them. Appended,
 * never updated. The measures say what was retrieved, shown, and cited; none of them judges whether a cited passage
 * supports the claim made from it.
 *
 * @param questionCount the questions selected for the pass (the set after the {@code questions} filter and {@code limit})
 * @param attempted     the questions the runner handed to the recommendation service; {@code results} lists exactly these
 * @param partial       true when the pass stopped before every selected question was attempted or when it stopped on the
 *                      question it names; {@code partialReason} says why and {@code notAttempted} lists what is left
 */
public record AnswerEvaluation(Long id, Instant evaluatedAt, String setVersion, int questionCount, int attempted, boolean partial,
        String partialReason, Aggregates aggregates, Map<String, Object> properties, List<QuestionResult> results,
        List<String> notAttempted) {

    /**
     * The measures of one attempted question. {@code runId} is null only when the service refused the run ({@code error}
     * says why). The Boolean measures are null when they cannot be told: all of {@code retrievedExpected},
     * {@code visibleToModel}, and the chunk id lists when the run's evidence was not captured; {@code figuresInReasoning}
     * for a question that is not FIGURE or whose accepted phrases hold no figure.
     *
     * @param retrievedExpected     some chunk retrieved during the run holds an accepted phrase (same accession and section, the
     *                              phrase inside the full content; the retrieval evaluation's matching rule)
     * @param visibleToModel        for some such chunk, the phrase lies inside the text the model was shown for it
     * @param citedExpected         a cited chunk holds an accepted phrase
     * @param citedHoldingPhrase    how many cited chunks hold an accepted phrase
     * @param figureTokens          the figures of the accepted phrase the figure check settled on (years left out)
     * @param figureTokensMissing   those of them not found in the reasoning
     */
    public record QuestionResult(String id, String ticker, Kind kind, String runId, String error, String status, String assessment,
            boolean evidenceCaptured, int retrievedCount, Boolean retrievedExpected, List<Long> expectedChunkIds,
            Boolean visibleToModel, List<Long> visibleChunkIds, Boolean citedExpected, int citedCount, int citedHoldingPhrase,
            List<Long> citedChunkIds, Boolean figuresInReasoning, List<String> figureTokens, List<String> figureTokensMissing,
            List<String> limitations, String criticVerdict, List<String> unsupportedNumerals, int modelCalls, int observedTokens,
            long elapsedMs, String reasoning) { }

    /**
     * Counts first, shares derived from them at scale 6; a share whose denominator is 0 is null. {@code withRun} counts
     * attempted questions that produced a stored run, {@code measured} those whose evidence was captured.
     * {@code shareRetrieved} = retrieved / measured; {@code shareVisibleGivenRetrieved} = visible / retrieved;
     * {@code shareCitedGivenVisible} = citedAndVisible / visible; {@code shareFiguresInReasoning} = figuresInReasoning /
     * figureQuestions (questions with a non-null figure check); {@code insufficientEvidenceRate} = insufficientEvidence
     * (status INSUFFICIENT_EVIDENCE) / withRun. {@code statusCounts} counts every run status, so stop codes such as
     * INVALID_CITATION or TOKEN_LIMIT appear there ({@code invalidCitationRuns} repeats the first); {@code limitationCounts}
     * counts limitation codes with a trailing numeric id removed.
     *
     * <p>A run that produced no answer (stopped by INVALID_CITATION, TOKEN_LIMIT, MODEL_UNAVAILABLE and the like) still sits
     * in the denominators above with the literal values its per-question measures have ({@code citedExpected} false,
     * {@code figuresInReasoning} false on its empty reasoning). The {@code ...AmongAnswered} fields are the same counts and
     * shares restricted to answered runs, each with its own numerator and denominator: {@code answeredRuns} counts runs
     * whose status is COMPLETE, PARTIAL or INSUFFICIENT_EVIDENCE (the statuses given to a validated answer) with a
     * non-blank reasoning, {@code noAnswerRuns} = withRun - answeredRuns. {@code shareRetrievedAmongAnswered} =
     * retrievedAmongAnswered / measuredAmongAnswered; {@code shareVisibleGivenRetrievedAmongAnswered} =
     * visibleAmongAnswered / retrievedAmongAnswered; {@code shareCitedGivenVisibleAmongAnswered} =
     * citedAndVisibleAmongAnswered / visibleAmongAnswered; {@code shareFiguresInReasoningAmongAnswered} =
     * figuresInReasoningAmongAnswered / figureQuestionsAmongAnswered; {@code insufficientEvidenceRateAmongAnswered} =
     * insufficientEvidence / answeredRuns. These counts are boxed: a row written before they existed reads them as null
     * (unknown), where a primitive would make the row unreadable.
     */
    public record Aggregates(int attempted, int withRun, int measured, int retrieved, BigDecimal shareRetrieved, int visible,
            BigDecimal shareVisibleGivenRetrieved, int citedAndVisible, BigDecimal shareCitedGivenVisible, int figureQuestions,
            int figuresInReasoning, BigDecimal shareFiguresInReasoning, int insufficientEvidence, BigDecimal insufficientEvidenceRate,
            int invalidCitationRuns, Map<String, Integer> statusCounts, Map<String, Integer> limitationCounts, long totalTokens,
            long totalModelCalls, long totalElapsedMs, Integer answeredRuns, Integer noAnswerRuns, Integer measuredAmongAnswered,
            Integer retrievedAmongAnswered, BigDecimal shareRetrievedAmongAnswered, Integer visibleAmongAnswered,
            BigDecimal shareVisibleGivenRetrievedAmongAnswered, Integer citedAndVisibleAmongAnswered,
            BigDecimal shareCitedGivenVisibleAmongAnswered, Integer figureQuestionsAmongAnswered, Integer figuresInReasoningAmongAnswered,
            BigDecimal shareFiguresInReasoningAmongAnswered, BigDecimal insufficientEvidenceRateAmongAnswered) { }

    AnswerEvaluation withId(long newId) {
        return new AnswerEvaluation(newId, evaluatedAt, setVersion, questionCount, attempted, partial, partialReason, aggregates,
                properties, results, notAttempted);
    }
}

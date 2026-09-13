package project.stockrecommendationengine.rag.evaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationQuestion.Kind;

/**
 * One evaluation snapshot: every question of the bundled set run through retrieval, with the rank of the first
 * matching chunk per question and the metrics over the whole set. Appended, never updated, so a retrieval change can
 * be compared against any earlier snapshot. Metrics are fractions at scale 6.
 *
 * <p>{@code slices} splits the same results by whether the question text carries a figure, using the rule that decides
 * whether retrieval's figure leg runs ({@code FilingRetrievalRepository.figureTerms} non-empty): keys {@link #FIGURE_SLICE}
 * and {@link #NON_FIGURE_SLICE}, always both present on a snapshot taken since slices were added. Snapshots stored before
 * that carry no slices and read back with {@code slices} null; nothing is backfilled.
 */
public record RetrievalEvaluation(Long id, Instant evaluatedAt, String setVersion, int questionCount, BigDecimal hitAt1,
        BigDecimal hitAt3, BigDecimal hitAt5, BigDecimal mrr, int window, String retrievalStrategy,
        Map<String, Object> properties, List<QuestionResult> results, Map<String, BigDecimal> tickerHitAt5, List<Miss> misses,
        Map<String, SliceMetrics> slices) {
    public static final String FIGURE_SLICE = "figure";
    public static final String NON_FIGURE_SLICE = "nonFigure";

    /** One question's outcome: the 1-based rank of the first matching chunk within the window, null on a miss or an error. */
    public record QuestionResult(String id, String ticker, Kind kind, Integer rank, Long matchedChunkId, String error) { }

    /** A question with no matching chunk in the window, with what retrieval returned instead (or the error that stopped it). */
    public record Miss(String id, List<TopChunk> top, String error) { }

    /** A returned chunk as recorded for a miss; enough to see which filing and section retrieval preferred. */
    public record TopChunk(Long chunkId, String accessionNo, String sectionKey, BigDecimal similarity) { }

    /**
     * Metrics over one slice of the questions, with the same definitions and scale as the aggregate. An empty slice has
     * question count 0 and null metrics. {@code missIds} lists, in set order, the slice's questions with no matching chunk
     * in the window (a retrieval error included), matching the aggregate {@code misses}.
     */
    public record SliceMetrics(int questionCount, BigDecimal hitAt1, BigDecimal hitAt3, BigDecimal hitAt5, BigDecimal mrr, List<String> missIds) { }

    public RetrievalEvaluation withId(Long newId) {
        return new RetrievalEvaluation(newId, evaluatedAt, setVersion, questionCount, hitAt1, hitAt3, hitAt5, mrr, window,
                retrievalStrategy, properties, results, tickerHitAt5, misses, slices);
    }
}

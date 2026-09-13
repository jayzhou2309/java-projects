package project.stockrecommendationengine.rag.evaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationQuestion.Kind;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace;

/**
 * One evaluation snapshot: every question of the bundled set run through retrieval, with the rank of the first
 * matching chunk per question and the metrics over the whole set. Appended, never updated, so a retrieval change can
 * be compared against any earlier snapshot. Metrics are fractions at scale 6.
 *
 * <p>{@code slices} splits the same results by whether the question text carries a figure, using the rule that decides
 * whether retrieval's figure leg runs ({@code FilingRetrievalRepository.figureTerms} non-empty): keys {@link #FIGURE_SLICE}
 * and {@link #NON_FIGURE_SLICE}, always both present on a snapshot taken since slices were added. Snapshots stored before
 * that carry no slices and read back with {@code slices} null; nothing is backfilled.
 *
 * <p>{@code traces} holds one {@link QuestionTrace} per question, in set order, only on a snapshot evaluated with
 * {@code trace=true} ({@code properties.trace} true); it is null on an untraced snapshot and on every snapshot stored before
 * traces existed. Nothing is backfilled.
 */
public record RetrievalEvaluation(Long id, Instant evaluatedAt, String setVersion, int questionCount, BigDecimal hitAt1,
        BigDecimal hitAt3, BigDecimal hitAt5, BigDecimal mrr, int window, String retrievalStrategy,
        Map<String, Object> properties, List<QuestionResult> results, Map<String, BigDecimal> tickerHitAt5, List<Miss> misses,
        Map<String, SliceMetrics> slices, List<QuestionTrace> traces) {
    public static final String FIGURE_SLICE = "figure";
    public static final String NON_FIGURE_SLICE = "nonFigure";

    /** A snapshot without traces. */
    public RetrievalEvaluation(Long id, Instant evaluatedAt, String setVersion, int questionCount, BigDecimal hitAt1,
            BigDecimal hitAt3, BigDecimal hitAt5, BigDecimal mrr, int window, String retrievalStrategy,
            Map<String, Object> properties, List<QuestionResult> results, Map<String, BigDecimal> tickerHitAt5, List<Miss> misses,
            Map<String, SliceMetrics> slices) {
        this(id, evaluatedAt, setVersion, questionCount, hitAt1, hitAt3, hitAt5, mrr, window, retrievalStrategy, properties, results,
                tickerHitAt5, misses, slices, null);
    }

    /**
     * One question's retrieval trace ({@link RetrievalTrace}: the fused candidates with their leg ranks, the rerank outcome with
     * every rerank input's position, score, and window scores when reranking ran, and the returned chunk ids); {@code trace} is
     * null when the question's retrieval threw (its error is in {@code results}).
     */
    public record QuestionTrace(String id, RetrievalTrace trace) { }

    /**
     * One question's outcome: the 1-based rank of the first matching chunk within the window, null on a miss or an error.
     * {@code retrievalStrategy} is the strategy retrieval reported for this question (so {@code HYBRID_RRF} on a question
     * where reranking was requested means it fell back); null on a retrieval error and on snapshots stored before the field
     * was added.
     */
    public record QuestionResult(String id, String ticker, Kind kind, Integer rank, Long matchedChunkId, String error,
            String retrievalStrategy) { }

    /** A question with no matching chunk in the window, with what retrieval returned instead (or the error that stopped it). */
    public record Miss(String id, List<TopChunk> top, String error) { }

    /** A returned chunk as recorded for a miss; enough to see which filing and section retrieval preferred. */
    public record TopChunk(Long chunkId, String accessionNo, String sectionKey, BigDecimal similarity) { }

    /**
     * Metrics over one slice of the questions, with the same definitions and scale as the aggregate. An empty slice has
     * question count 0 and null metrics. {@code missIds} lists, in set order, the slice's questions with no matching chunk
     * in the window (a retrieval error included), matching the aggregate {@code misses}. {@code notInTop5} lists, in set
     * order, every question of the slice that does not count toward its hit@5 (rank null or greater than 5) with its rank, so
     * a floor failure can name the questions ranked 6 to 10 as well as those with no match; snapshots stored before this
     * field was added read back with it null.
     */
    public record SliceMetrics(int questionCount, BigDecimal hitAt1, BigDecimal hitAt3, BigDecimal hitAt5, BigDecimal mrr, List<String> missIds,
            List<RankedQuestion> notInTop5) { }

    /** A question id with its rank within the window; rank null means no match in the window or a retrieval error. */
    public record RankedQuestion(String id, Integer rank) { }

    public RetrievalEvaluation withId(Long newId) {
        return new RetrievalEvaluation(newId, evaluatedAt, setVersion, questionCount, hitAt1, hitAt3, hitAt5, mrr, window,
                retrievalStrategy, properties, results, tickerHitAt5, misses, slices, traces);
    }
}

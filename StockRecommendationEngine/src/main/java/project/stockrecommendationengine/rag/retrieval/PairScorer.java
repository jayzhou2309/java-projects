package project.stockrecommendationengine.rag.retrieval;

import java.util.List;

/**
 * Scores (query, passage) pairs for relevance; a higher score means more relevant. Kept apart from ordering so the
 * cross-encoder reranker's ordering is testable without a model. Implementations throw a {@link RuntimeException} on any
 * failure, which the retrieval service turns into a fallback to the fused order.
 */
public interface PairScorer extends AutoCloseable {
    /**
     * One score per passage plus the model rows scored for them: one per passage, or one per window under windowed scoring.
     * {@code windowScores}, when not null, holds per passage (same index as {@code scores}) the logit of each of its rows in
     * window order, so a passage's score is the reduction of its row logits (their maximum under windowed scoring, the single
     * logit under head scoring); null when the scorer does not report rows.
     */
    record Scored(float[] scores, int windows, float[][] windowScores) {
        /** A result without per-row logits. */
        public Scored(float[] scores, int windows) {
            this(scores, windows, null);
        }
    }

    /** One score per passage, in the order given; the query is paired with every passage. */
    float[] score(String query, List<String> passages);

    /**
     * {@link #score} with the number of model rows scored; the default reports one row per passage, so each passage's single row
     * logit is its score ({@code windowScores} null when {@link #score} returns null or a count that does not match the passages).
     */
    default Scored scoreWithWindows(String query, List<String> passages) {
        float[] scores = score(query, passages);
        if (scores == null || scores.length != passages.size()) return new Scored(scores, passages.size());
        float[][] windowScores = new float[scores.length][];
        for (int passage = 0; passage < scores.length; passage++) windowScores[passage] = new float[] {scores[passage]};
        return new Scored(scores, passages.size(), windowScores);
    }

    /** A short description of how a passage is scored (its window settings, for example); null when the scorer has none. */
    default String scoring() {
        return null;
    }

    /** Releases native resources; the default does nothing. */
    @Override
    default void close() {
    }
}

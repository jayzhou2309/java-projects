package project.stockrecommendationengine.rag.retrieval;

import java.util.List;

/**
 * Scores (query, passage) pairs for relevance; a higher score means more relevant. Kept apart from ordering so the
 * cross-encoder reranker's ordering is testable without a model. Implementations throw a {@link RuntimeException} on any
 * failure, which the retrieval service turns into a fallback to the fused order.
 */
public interface PairScorer extends AutoCloseable {
    /** One score per passage plus the model rows scored for them: one per passage, or one per window under windowed scoring. */
    record Scored(float[] scores, int windows) {
    }

    /** One score per passage, in the order given; the query is paired with every passage. */
    float[] score(String query, List<String> passages);

    /** {@link #score} with the number of model rows scored; the default reports one row per passage. */
    default Scored scoreWithWindows(String query, List<String> passages) {
        return new Scored(score(query, passages), passages.size());
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

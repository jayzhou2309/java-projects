package project.stockrecommendationengine.rag.retrieval;

import java.util.List;

/**
 * Scores (query, passage) pairs for relevance; a higher score means more relevant. Kept apart from ordering so the
 * cross-encoder reranker's ordering is testable without a model. Implementations throw a {@link RuntimeException} on any
 * failure, which the retrieval service turns into a fallback to the fused order.
 */
public interface PairScorer extends AutoCloseable {
    /** One score per passage, in the order given; the query is paired with every passage. */
    float[] score(String query, List<String> passages);

    /** Releases native resources; the default does nothing. */
    @Override
    default void close() {
    }
}

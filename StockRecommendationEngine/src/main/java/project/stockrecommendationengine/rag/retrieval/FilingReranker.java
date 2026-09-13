package project.stockrecommendationengine.rag.retrieval;

import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import java.util.List;

/** Optional model adapter. Return a ranked subset without changing evidence or cosine scores. */
public interface FilingReranker {
    List<RetrievedFilingChunk> rerank(String query, List<RetrievedFilingChunk> candidates, int topK);

    /**
     * One candidate as a scored reranking ranked it: {@code inputIndex} is its 0-based index in the candidates given,
     * {@code score} the score that placed it, and {@code windowScores} the model row scores that score was reduced from, in
     * window order (null when the reranker does not report rows).
     */
    record ScoredCandidate(int inputIndex, float score, float[] windowScores) {
    }

    /**
     * The outcome of {@link #rerankScored}: {@code results} exactly as {@link #rerank} returns them for the same input, and
     * {@code order} every candidate given, once each, in reranked order, so its first {@code results.size()} entries are the
     * results.
     */
    record ScoredReranking(List<RetrievedFilingChunk> results, List<ScoredCandidate> order) {
    }

    /** True when {@link #rerankScored} is implemented; the default is false. */
    default boolean reportsScores() {
        return false;
    }

    /**
     * {@link #rerank} with the scores that produced its order, from one scoring pass (never a second scoring call). Used only by
     * traced evaluation runs through {@code FilingRetrievalService.retrieveTraced}; its results must equal {@link #rerank}'s for the
     * same input. The default is not supported: it throws {@link UnsupportedOperationException}, and {@link #reportsScores()} is
     * false.
     */
    default ScoredReranking rerankScored(String query, List<RetrievedFilingChunk> candidates, int topK) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not report reranking scores");
    }

    /** A short identifier of the exact model this reranker runs, recorded in evaluation snapshots; null when it has none. */
    default String version() {
        return null;
    }

    /**
     * A short description of how this reranker scores a passage (for the cross-encoder, {@code head} or
     * {@code max-window/overlap=64/maxWindows=4}), recorded as {@code properties.rerankerScoring} in evaluation snapshots so
     * snapshots from different scoring modes are distinguishable; null when it has none.
     */
    default String scoring() {
        return null;
    }
}

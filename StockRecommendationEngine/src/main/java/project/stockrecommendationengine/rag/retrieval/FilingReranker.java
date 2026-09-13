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
     * The outcome of {@link #rerankScored}: {@code results}, the reranked subset retrieval validates and uses, and {@code order},
     * every candidate given, once each, in reranked order, so its first {@code results.size()} entries are the results; {@code order}
     * is null when the reranker reports no scores.
     */
    record ScoredReranking(List<RetrievedFilingChunk> results, List<ScoredCandidate> order) {
    }

    /**
     * The one reranker method {@code FilingRetrievalService} calls, once per reranked retrieval, on both {@code retrieve} and
     * {@code retrieveTraced}; the untraced path discards {@code order}, the traced path records it. Because both paths take their
     * results from this single call, traced and untraced retrieval return identical results for any reranker, including one whose
     * {@link #rerank} would order differently, and there is no separate capability flag to disagree with it. The default calls
     * {@link #rerank} once and reports no scores ({@code order} null), which the trace records as {@code reranker reports no scores}.
     * A reranker that overrides it returns the scores that produced its order from the same scoring pass (never a second scoring
     * call) and should keep {@link #rerank} equal to its results for direct callers.
     */
    default ScoredReranking rerankScored(String query, List<RetrievedFilingChunk> candidates, int topK) {
        return new ScoredReranking(rerank(query, candidates, topK), null);
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

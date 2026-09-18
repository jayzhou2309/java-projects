package project.stockrecommendationengine.rag.evaluation;

import java.util.List;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.RemovedCandidate;

/**
 * The per-question evidence for one stored evaluation snapshot ({@code GET /api/rag/evaluate/{id}/evidence}; RAG.md, Retrieval
 * Evaluation, Evidence report), built by {@link RetrievalEvidenceService} with no model call. Keys name a row (a question's id, ticker,
 * kind, and text; an accepted phrase's accession, section, and text; a chunk id heading a row); every other field is an
 * {@link EvidenceValue} carrying its basis.
 */
public record RetrievalEvidenceReport(long snapshotId, EvidenceValue<String> setVersion, EvidenceValue<String> set, EvidenceValue<Boolean> traced,
        Settings settings, List<QuestionEvidence> questions) {

    /**
     * The settings token positions and window membership depend on. {@code maxLength} is not recorded in snapshots, so it is observed
     * from the current configuration; {@code passageScoring}, {@code windowOverlapTokens}, and {@code maxWindows} are parsed from
     * {@code rerankerScoring}. {@code chunkMaxChars}, {@code chunkOverlapChars}, and {@code storeVersions} (since 2026-09-17) are the
     * chunk size and overlap configured when the snapshot ran and the distinct processing versions of the store it ran against, as recorded;
     * unknown for a snapshot stored before they were recorded.
     */
    public record Settings(EvidenceValue<Boolean> rerank, EvidenceValue<Integer> rerankCandidates, EvidenceValue<String> reranker,
            EvidenceValue<String> rerankerVersion, EvidenceValue<String> loadedModelVersion, EvidenceValue<String> rerankerScoring,
            EvidenceValue<String> passageScoring, EvidenceValue<Integer> windowOverlapTokens, EvidenceValue<Integer> maxWindows,
            EvidenceValue<Integer> maxLength, EvidenceValue<Integer> chunkMaxChars, EvidenceValue<Integer> chunkOverlapChars,
            EvidenceValue<List<String>> storeVersions) {
    }

    /**
     * One question of the snapshot, in the snapshot's order. {@code rank}, {@code matchedChunkId}, {@code retrievalStrategy}, and
     * {@code error} are the snapshot's result; the rerank fields and {@code rankedAbove} come from the question's trace;
     * {@code phrases} lists every accepted phrase of the question in the bundled set with every stored chunk that holds it.
     * {@code ranking} names the order the response was cut from ({@code reranked order} or {@code fused order}); {@code bestAcceptedChunk}
     * is the first chunk in it holding any accepted phrase (null when none of them is in it), and {@code rankedAbove} lists every chunk
     * ranked above that one (the whole ranking when none is in it). {@code removed} is the trace's list of chunks diversification removed and
     * {@code acceptedChunkRemoved} whether a chunk holding an accepted phrase is among them; both are unknown ({@code no trace of removals})
     * for a trace recorded before removals were traced.
     */
    public record QuestionEvidence(String id, String ticker, String kind, String question, EvidenceValue<Integer> rank,
            EvidenceValue<Long> matchedChunkId, EvidenceValue<String> retrievalStrategy, EvidenceValue<String> error,
            EvidenceValue<String> rerankOutcome, EvidenceValue<String> fallbackReason, EvidenceValue<String> scoresNotRecorded,
            EvidenceValue<Integer> fusedCount, EvidenceValue<Integer> rerankInputCount, EvidenceValue<Integer> queryTokens,
            EvidenceValue<Integer> acceptedPhraseCount, List<PhraseEvidence> phrases, EvidenceValue<String> ranking,
            EvidenceValue<Long> bestAcceptedChunk, EvidenceValue<Integer> bestAcceptedPosition, EvidenceValue<List<RankedChunk>> rankedAbove,
            EvidenceValue<List<RemovedCandidate>> removed, EvidenceValue<Boolean> acceptedChunkRemoved) {
    }

    /** One accepted phrase and every stored chunk of its accession and section that holds it (none when {@code heldByStoredChunk} is false). */
    public record PhraseEvidence(String accessionNo, String sectionKey, String phrase, EvidenceValue<Boolean> heldByStoredChunk,
            List<ChunkEvidence> chunks) {
    }

    /**
     * One stored chunk holding a phrase: its token length, the window length W beside this question, the row starts under the
     * snapshot's scoring, each occurrence of the phrase, and the chunk's candidate fields from the trace; {@code removedRedundantWith} is the
     * kept chunk that made this chunk redundant when diversification removed it (null when it did not).
     */
    public record ChunkEvidence(long chunkId, EvidenceValue<Integer> chunkTokens, EvidenceValue<Integer> windowLength,
            EvidenceValue<List<Integer>> windowStarts, List<OccurrenceEvidence> occurrences, EvidenceValue<Integer> fusedPosition,
            EvidenceValue<Boolean> rerankInput, EvidenceValue<Integer> rerankedPosition, EvidenceValue<Float> score,
            EvidenceValue<Integer> windowCount, EvidenceValue<List<Float>> windowScores, EvidenceValue<Integer> returnedPosition,
            EvidenceValue<Long> removedRedundantWith) {
    }

    /** One occurrence of the phrase in the chunk: its character span, token span, head membership, and the rows holding it wholly. */
    public record OccurrenceEvidence(EvidenceValue<Span> characterSpan, EvidenceValue<Span> tokenSpan, EvidenceValue<String> head,
            EvidenceValue<List<Integer>> windowsHoldingWholly) {
    }

    /** Offsets {@code [start, end)}, end exclusive. */
    public record Span(int start, int end) {
    }

    /** A chunk of the ranking: its position in it, its fused position, and its reranker score and row scores. */
    public record RankedChunk(long chunkId, EvidenceValue<Integer> position, EvidenceValue<Integer> fusedPosition, EvidenceValue<Float> score,
            EvidenceValue<List<Float>> windowScores) {
    }
}

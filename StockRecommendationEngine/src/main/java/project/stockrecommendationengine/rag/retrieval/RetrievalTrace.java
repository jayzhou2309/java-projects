package project.stockrecommendationengine.rag.retrieval;

import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one retrieval did, recorded only on the traced path ({@link FilingRetrievalService#retrieveTraced}, used by traced
 * evaluation runs alone): never part of a {@code RetrievalResponse}, so it reaches neither {@code /api/rag/retrieve}, the
 * recommendation tools, nor a prompt. Every value is copied from the retrieval that produced the response; nothing is
 * recomputed or rescored.
 * <p>
 * {@code vectorCandidates} is the size of the vector leg; {@code keywordCandidates} and {@code figureCandidates} are the sizes
 * of those legs, null when the leg did not run or failed (so a null leg rank below means "absent from a leg that ran" only when
 * the leg size is not null). {@code fused} is the fused, diversified candidate list in order (the vector list, diversified,
 * when fusion did not run); chunks diversification removed are not listed. {@code returnedChunkIds} are the response's
 * results in order.
 */
public record RetrievalTrace(int vectorCandidates, Integer keywordCandidates, Integer figureCandidates, List<FusedCandidate> fused,
        Rerank rerank, List<Long> returnedChunkIds) {

    /**
     * One chunk of the fused, diversified list: its 1-based {@code fusedPosition} (1..n without gaps) and its 1-based rank in
     * each leg that returned it, null when that leg did not return it or did not run. A leg rank counts a chunk repeated within
     * the leg once, at its first position, the rule reciprocal rank fusion uses.
     */
    public record FusedCandidate(long chunkId, int fusedPosition, Integer vectorRank, Integer keywordRank, Integer figureRank) {
    }

    /** Whether the reranker's order was used. */
    public enum Outcome {
        /** Reranking did not resolve on for this retrieval. */
        OFF,
        /** The reranker's validated order was returned. */
        RERANKED,
        /** Reranking resolved on but the fused order was returned; {@link Rerank#fallbackReason} says why. */
        FALLBACK
    }

    /**
     * The rerank step. {@code fallbackReason} (FALLBACK only) is the reason class the service logs: {@code timeout},
     * {@code interrupted}, {@code failure}, {@code invalidEvidence}, or {@code noCandidates} (the fused list was empty, so the
     * reranker was not called). {@code inputCount} is the number of chunks given to the reranker, the first
     * max({@code rerank-candidates}, topK) of the fused list (0 for {@code noCandidates}; null when OFF). {@code candidates}
     * (RERANKED only; null otherwise, so a fallback records no scores) lists every rerank input chunk once, ordered by reranked
     * position, with chunks the order does not place last in fused order. {@code scoresNotRecorded} (RERANKED only) is null when
     * every candidate carries its reranked position and score, otherwise the reason they do not: {@code reranker reports no scores}
     * (only the returned chunks then carry a reranked position) or {@code scores inconsistent with the returned order} (the
     * reranker's scored order did not list every input once with the returned chunks first; the returned chunks still carry their
     * positions).
     */
    public record Rerank(Outcome outcome, String fallbackReason, Integer inputCount, String scoresNotRecorded, List<RerankedCandidate> candidates) {
        static Rerank off() {
            return new Rerank(Outcome.OFF, null, null, null, null);
        }

        static Rerank fallback(String reason, int inputCount) {
            return new Rerank(Outcome.FALLBACK, reason, inputCount, null, null);
        }
    }

    /**
     * One rerank input chunk: its {@code fusedPosition} (its position in the fused list, which is also its 1-based position in the
     * reranker's input), its 1-based {@code rerankedPosition} within the input, the reranker's {@code score}, and the model row
     * scores that score was reduced from ({@code windowScores}, with {@code windowCount} their number); each null when not recorded.
     */
    public record RerankedCandidate(long chunkId, int fusedPosition, Integer rerankedPosition, Float score, Integer windowCount,
            List<Float> windowScores) {
    }

    static final String NO_SCORES = "reranker reports no scores";
    static final String INCONSISTENT_SCORES = "scores inconsistent with the returned order";

    /** The fused list as recorded: positions 1..n and each leg's rank, legs null when they did not run. */
    static List<FusedCandidate> fused(List<RetrievedFilingChunk> diversified, List<RetrievedFilingChunk> vector,
            List<RetrievedFilingChunk> keyword, List<RetrievedFilingChunk> figure) {
        Map<Long, Integer> vectorRanks = ranks(vector);
        Map<Long, Integer> keywordRanks = ranks(keyword);
        Map<Long, Integer> figureRanks = ranks(figure);
        List<FusedCandidate> out = new ArrayList<>(diversified.size());
        for (int index = 0; index < diversified.size(); index++) {
            long chunkId = diversified.get(index).chunkId();
            out.add(new FusedCandidate(chunkId, index + 1, vectorRanks.get(chunkId), keywordRanks.get(chunkId), figureRanks.get(chunkId)));
        }
        return List.copyOf(out);
    }

    private static Map<Long, Integer> ranks(List<RetrievedFilingChunk> leg) {
        Map<Long, Integer> ranks = new HashMap<>();
        if (leg == null) return ranks;
        int rank = 0;
        for (RetrievedFilingChunk chunk : leg) {
            if (ranks.containsKey(chunk.chunkId())) continue;
            ranks.put(chunk.chunkId(), ++rank);
        }
        return ranks;
    }

    /**
     * The RERANKED record for {@code input} and the validated {@code results}. With a {@code scored} order that lists every input
     * index once and whose first entries are exactly the results, every candidate carries its position, score, and row scores;
     * otherwise only the returned chunks carry a position, and {@code scoresNotRecorded} says why.
     */
    static Rerank reranked(List<RetrievedFilingChunk> input, List<RetrievedFilingChunk> results, FilingReranker.ScoredReranking scored) {
        String notRecorded = scored == null ? NO_SCORES : consistent(input, results, scored) ? null : INCONSISTENT_SCORES;
        Integer[] positions = new Integer[input.size()];
        FilingReranker.ScoredCandidate[] scores = new FilingReranker.ScoredCandidate[input.size()];
        if (notRecorded == null) {
            for (int position = 0; position < scored.order().size(); position++) {
                FilingReranker.ScoredCandidate candidate = scored.order().get(position);
                positions[candidate.inputIndex()] = position + 1;
                scores[candidate.inputIndex()] = candidate;
            }
        } else {
            for (int position = 0; position < results.size(); position++) {
                positions[indexOf(input, results.get(position))] = position + 1;
            }
        }
        List<RerankedCandidate> candidates = new ArrayList<>(input.size());
        for (int index = 0; index < input.size(); index++) {
            FilingReranker.ScoredCandidate score = scores[index];
            float[] windows = score == null ? null : score.windowScores();
            candidates.add(new RerankedCandidate(input.get(index).chunkId(), index + 1, positions[index], score == null ? null : score.score(),
                    windows == null ? null : windows.length, windows == null ? null : boxed(windows)));
        }
        candidates.sort(Comparator.comparing(RerankedCandidate::rerankedPosition, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(RerankedCandidate::fusedPosition));
        return new Rerank(Outcome.RERANKED, null, input.size(), notRecorded, List.copyOf(candidates));
    }

    private static boolean consistent(List<RetrievedFilingChunk> input, List<RetrievedFilingChunk> results, FilingReranker.ScoredReranking scored) {
        List<FilingReranker.ScoredCandidate> order = scored.order();
        if (order == null || order.size() != input.size() || results.size() > order.size()) return false;
        Set<Integer> seen = new HashSet<>();
        for (FilingReranker.ScoredCandidate candidate : order) {
            if (candidate == null || candidate.inputIndex() < 0 || candidate.inputIndex() >= input.size() || !seen.add(candidate.inputIndex())) return false;
        }
        for (int position = 0; position < results.size(); position++) {
            if (!input.get(order.get(position).inputIndex()).equals(results.get(position))) return false;
        }
        return true;
    }

    /** Index of a validated result in the input; validation guarantees it is present. */
    private static int indexOf(List<RetrievedFilingChunk> input, RetrievedFilingChunk result) {
        int index = input.indexOf(result);
        if (index < 0) throw new IllegalStateException("A validated reranked chunk is not in the rerank input");
        return index;
    }

    private static List<Float> boxed(float[] values) {
        List<Float> out = new ArrayList<>(values.length);
        for (float value : values) out.add(value);
        return List.copyOf(out);
    }

    static List<Long> chunkIds(List<RetrievedFilingChunk> chunks) {
        return chunks.stream().map(RetrievedFilingChunk::chunkId).toList();
    }
}

package project.stockrecommendationengine.rag.evaluation;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import project.stockrecommendationengine.rag.evaluation.EvidenceChunkRepository.StoredChunk;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionResult;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionTrace;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationQuestion.Kind;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderProperties;
import project.stockrecommendationengine.rag.retrieval.PassageTokenizer;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.FusedCandidate;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.Outcome;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.RerankedCandidate;
import project.stockrecommendationengine.rag.retrieval.ScriptedWordTokenizer;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A scripted snapshot, set, and chunk store for the evidence report tests, with no database or model. Every word is one token
 * ({@link ScriptedWordTokenizer}) and {@code max-length} is 20, so token positions are computed by hand:
 * <ul>
 * <li>q1 (3 query tokens), RERANKED over fused chunks 101, 301, 102, 302 with rerank input 3: phrase 1 ("w012 w013 w014 w015") is held
 * by chunk 101 (40 words: tokens 12 to 15, W 14, rows at 0, 14, 26 under overlap 0, so the head cut splits it and no row holds it
 * wholly) and by chunk 102 (the same words with other case and whitespace, 6 tokens, one row); phrase 2 is held by no stored chunk.
 * The reranker ordered 301, 102, 101, so 102 is the best accepted chunk at position 2 with 301 above it.</li>
 * <li>q2 (2 query tokens), FALLBACK on timeout with rerank input 2: its phrase is held by chunk 201 (40 words: tokens 30 and 31, W 15,
 * rows at 0, 15, 25, so row 3 holds it), which is not in the fused list (202, 203), so no accepted chunk is in the ranking.</li>
 * <li>q3: retrieval failed, trace null.</li>
 * </ul>
 */
final class ScriptedEvidence {
    static final String SET_RESOURCE = "evaluation/scripted-set.json";
    static final String ACC = "0000000001-26-000001";
    static final String ACC2 = "0000000002-26-000002";
    static final String VERSION = "5d3e70fd0c9f";
    static final Path FIXTURE_JSON = Path.of("src/test/resources/evaluation/evidence/scripted-report.json");
    static final Path FIXTURE_MARKDOWN = Path.of("src/test/resources/evaluation/evidence/scripted-report.md");

    final RetrievalEvaluationRepository snapshots = mock(RetrievalEvaluationRepository.class);
    final RetrievalEvaluationSetLoader loader = mock(RetrievalEvaluationSetLoader.class);
    final EvidenceChunkRepository chunks = mock(EvidenceChunkRepository.class);
    final CrossEncoderProperties crossEncoder = new CrossEncoderProperties();
    final ScriptedWordTokenizer tokenizer = new ScriptedWordTokenizer(VERSION);

    ScriptedEvidence() {
        crossEncoder.setMaxLength(20);
        when(loader.load(SET_RESOURCE)).thenReturn(set());
        when(chunks.chunks(anyString(), anyString())).thenReturn(List.of());
        when(chunks.chunks(ACC, "ITEM_7")).thenReturn(List.of(new StoredChunk(100, "w000 unrelated"), new StoredChunk(101, ScriptedWordTokenizer.words(40)),
                new StoredChunk(102, "Intro w012  W013\n\tw014 w015 tail")));
        when(chunks.chunks(ACC, "ITEM_1A")).thenReturn(List.of(new StoredChunk(103, "something else entirely"), new StoredChunk(104, "")));
        when(chunks.chunks(ACC2, "ITEM_1")).thenReturn(List.of(new StoredChunk(201, ScriptedWordTokenizer.words(40))));
    }

    RetrievalEvidenceService service() {
        return service(Optional.of(tokenizer));
    }

    RetrievalEvidenceService service(Optional<PassageTokenizer> passageTokenizer) {
        return new RetrievalEvidenceService(snapshots, loader, chunks, crossEncoder, passageTokenizer);
    }

    static RetrievalEvaluationSet set() {
        return new RetrievalEvaluationSet("v-test", LocalDate.of(2026, 9, 13), List.of(
                new RetrievalEvaluationQuestion("q1", "AAPL", Kind.FIGURE, "q1 alpha beta", List.of(
                        new ExpectedPassage(ACC, "ITEM_7", "w012 w013 w014 w015"),
                        new ExpectedPassage(ACC, "ITEM_1A", "a phrase no stored chunk holds")), null),
                new RetrievalEvaluationQuestion("q2", "MSFT", Kind.NARRATIVE, "q2 gamma", List.of(
                        new ExpectedPassage(ACC2, "ITEM_1", "w030 w031")), null),
                new RetrievalEvaluationQuestion("q3", "NVDA", Kind.FIGURE, "q3 delta epsilon", List.of(
                        new ExpectedPassage(ACC2, "ITEM_1", "w030 w031")), null)));
    }

    /** The traced snapshot described in the class comment. */
    static RetrievalEvaluation tracedSnapshot() {
        RetrievalTrace q1 = new RetrievalTrace(4, 4, null,
                List.of(new FusedCandidate(101, 1, 1, 2, null), new FusedCandidate(301, 2, 2, 1, null), new FusedCandidate(102, 3, 4, 3, null),
                        new FusedCandidate(302, 4, 3, null, null)),
                new RetrievalTrace.Rerank(Outcome.RERANKED, null, 3, null, List.of(
                        new RerankedCandidate(301, 2, 1, 2.5f, 2, List.of(2.5f, -1.0f)),
                        new RerankedCandidate(102, 3, 2, 1.25f, 1, List.of(1.25f)),
                        new RerankedCandidate(101, 1, 3, -0.5f, 3, List.of(-0.5f, -3.0f, -2.0f)))),
                List.of(301L, 102L));
        RetrievalTrace q2 = new RetrievalTrace(2, 2, null, List.of(new FusedCandidate(202, 1, 1, 1, null), new FusedCandidate(203, 2, 2, null, null)),
                new RetrievalTrace.Rerank(Outcome.FALLBACK, "timeout", 2, null, null), List.of(202L, 203L));
        return snapshot(properties(true), List.of(new QuestionTrace("q1", q1), new QuestionTrace("q2", q2), new QuestionTrace("q3", null)));
    }

    /** The same questions stored without traces. */
    static RetrievalEvaluation untracedSnapshot() {
        return snapshot(properties(false), null);
    }

    static Map<String, Object> properties(boolean trace) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("window", 10);
        properties.put("set", SET_RESOURCE);
        properties.put("hybrid", null);
        properties.put("rerank", true);
        properties.put("rerankCandidates", 3);
        properties.put("reranker", "CrossEncoderReranker");
        properties.put("rerankerVersion", VERSION);
        properties.put("rerankerScoring", "max-window/overlap=0/maxWindows=4");
        properties.put("trace", trace);
        return properties;
    }

    static RetrievalEvaluation snapshot(Map<String, Object> properties, List<QuestionTrace> traces) {
        List<QuestionResult> results = new ArrayList<>(List.of(
                new QuestionResult("q1", "AAPL", Kind.FIGURE, 2, 102L, null, "HYBRID_RRF_RERANKED"),
                new QuestionResult("q2", "MSFT", Kind.NARRATIVE, null, null, null, "HYBRID_RRF"),
                new QuestionResult("q3", "NVDA", Kind.FIGURE, null, null, "IllegalStateException: Embedding request failed", null)));
        return new RetrievalEvaluation(459L, Instant.parse("2026-09-13T11:45:32Z"), "v-test", 3, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 10, "HYBRID_RRF_RERANKED", properties, List.copyOf(results), Map.of(), List.of(), Map.of(), traces);
    }

    /** A copy of {@code snapshot} with other properties and traces. */
    static RetrievalEvaluation with(RetrievalEvaluation snapshot, Map<String, Object> properties, List<QuestionTrace> traces) {
        return new RetrievalEvaluation(snapshot.id(), snapshot.evaluatedAt(), snapshot.setVersion(), snapshot.questionCount(), snapshot.hitAt1(),
                snapshot.hitAt3(), snapshot.hitAt5(), snapshot.mrr(), snapshot.window(), snapshot.retrievalStrategy(), properties, snapshot.results(),
                snapshot.tickerHitAt5(), snapshot.misses(), snapshot.slices(), traces);
    }
}

package project.stockrecommendationengine.rag.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.stockrecommendationengine.rag.dto.RetrievalRequest;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionResult;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionTrace;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalService;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.FusedCandidate;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.RerankedCandidate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/**
 * Opt-in (-Drag.evaluation.live=true; model files under models/; one embedding per question plus one warm-up, no chat model)
 * reproduction check for traced evaluation (plan 2026-09-13-evaluation-evidence, Milestone 1, C5): a traced run at the settings
 * of snapshot 297 (cross-encoder on, rerank true, rerank-candidates 20, max-window scoring with overlap 64 and at most 4 windows,
 * window 10, hybrid at its default, set v2) must reproduce 297's rank and matched chunk for every question. The cross-encoder and those
 * settings are enabled only in this test's own Spring context (the properties below), never through the environment. The comparison is
 * {@link TraceReproductionCheck} (unit-tested without a database in TraceReproductionCheckTests): it collects every problem, prints each
 * one, and fails once naming every question whose rank or matched chunk differs from 297's, every question that fell back with its
 * trace's reason, every errored question, and then every run property that differs from 297's (all of 297's properties are compared;
 * the run's extra {@code trace} must be true). The snapshot is saved inside a transaction that is rolled back, so nothing is left in the
 * shared database. After the comparison passes it also checks, on the live data, one trace per question, each RERANKED, the trace
 * invariants (fused positions, every rerank input once, the first topK of the rerank order are the returned chunks, window scores
 * reduce to each score), that the stored traces read back equal, and prints the stored document's size with and without traces. With -Drag.evaluation.trace-out=<file> the stored row (row_to_json) is written there
 * before the rollback.
 */
@SpringBootTest(properties = {
        "rag.retrieval.cross-encoder.enabled=true",
        "rag.retrieval.rerank-candidates=20",
        "rag.retrieval.cross-encoder.passage-scoring=max-window",
        "rag.retrieval.cross-encoder.window-overlap-tokens=64",
        "rag.retrieval.cross-encoder.max-windows=4",
        "rag.evaluation.set=evaluation/retrieval-set-v2.json",
        "rag.evaluation.window=10"})
@Transactional
@EnabledIfSystemProperty(named = "rag.evaluation.live", matches = "true")
class RetrievalEvaluationTraceLiveTests {
    static final Path SNAPSHOT_297 = Path.of("src/main/java/documentation/live-runs/2026-09-13-reranker-windows/measurement/snapshot-297-rerank-candidates-20.json");

    @Autowired RetrievalEvaluationService service;
    @Autowired RetrievalEvaluationRepository repository;
    @Autowired FilingRetrievalService retrieval;
    @Autowired JdbcTemplate jdbc;

    @Test void aTracedRunAtSnapshot297sSettingsReproducesItsRanksAndMatchedChunks() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        JsonNode reference = json.readTree(Files.readString(SNAPSHOT_297));
        assertThat(reference.get("id").asLong()).isEqualTo(297L);

        // Warm-up, as the 297 harness did after each start, so no evaluation call is a cold start.
        var warmUp = retrieval.retrieve(new RetrievalRequest("NVDA", "What was NVIDIA gross margin in fiscal 2026?", null, null, null, null, null, null, null, true));
        System.out.println("RETRIEVAL_TRACE warmUp strategy=" + warmUp.retrievalStrategy() + " results=" + warmUp.results().size());

        long started = System.nanoTime();
        RetrievalEvaluation evaluation = service.evaluate(null, true, true);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        Map<String, Object> properties = evaluation.properties();
        System.out.println("RETRIEVAL_TRACE snapshot id=" + evaluation.id() + " elapsedMs=" + elapsedMs + " hitAt1=" + evaluation.hitAt1()
                + " hitAt3=" + evaluation.hitAt3() + " hitAt5=" + evaluation.hitAt5() + " mrr=" + evaluation.mrr() + " properties=" + properties);

        // C5: every question's rank and matched chunk, every fallback, every error, and every property against 297's; all problems are
        // collected and printed first, then the test fails once with all of them.
        List<String> problems = TraceReproductionCheck.problems(reference, evaluation);
        System.out.println("RETRIEVAL_TRACE reproduction questions=" + reference.get("results").get("questions").size() + " results="
                + evaluation.results().size() + " problems=" + problems.size());
        problems.forEach(problem -> System.out.println("RETRIEVAL_TRACE problem " + problem));
        TraceReproductionCheck.assertReproduces(reference, evaluation);

        // One trace per question, in set order, each reranked, with the recorded invariants holding on the live data.
        assertThat(evaluation.traces()).extracting(QuestionTrace::id).containsExactlyElementsOf(evaluation.results().stream().map(QuestionResult::id).toList());
        int totalWindows = 0;
        for (int i = 0; i < evaluation.traces().size(); i++) {
            QuestionResult result = evaluation.results().get(i);
            RetrievalTrace trace = evaluation.traces().get(i).trace();
            assertThat(trace).as(result.id()).isNotNull();
            RetrievalTrace.Rerank rerank = trace.rerank();
            assertThat(rerank.outcome()).as(result.id()).isEqualTo(RetrievalTrace.Outcome.RERANKED);
            assertThat(rerank.scoresNotRecorded()).as(result.id()).isNull();
            assertThat(trace.fused()).extracting(FusedCandidate::fusedPosition).containsExactlyElementsOf(IntStream.rangeClosed(1, trace.fused().size()).boxed().toList());
            assertThat(new HashSet<>(trace.fused().stream().map(FusedCandidate::chunkId).toList())).hasSize(trace.fused().size());
            assertThat(trace.fused()).allSatisfy(c -> assertThat(c.vectorRank() != null || c.keywordRank() != null || c.figureRank() != null).isTrue());
            assertThat(rerank.inputCount()).as(result.id()).isEqualTo(Math.min(20, trace.fused().size()));
            List<RerankedCandidate> candidates = rerank.candidates();
            assertThat(candidates).extracting(RerankedCandidate::rerankedPosition).containsExactlyElementsOf(IntStream.rangeClosed(1, candidates.size()).boxed().toList());
            assertThat(candidates).extracting(RerankedCandidate::fusedPosition).containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, rerank.inputCount()).boxed().toList());
            for (RerankedCandidate candidate : candidates) {
                assertThat(trace.fused().get(candidate.fusedPosition() - 1).chunkId()).isEqualTo(candidate.chunkId());
                assertThat(candidate.windowScores()).hasSize(candidate.windowCount()).isNotEmpty();
                assertThat(candidate.windowScores().stream().max(Float::compare).orElseThrow()).isEqualTo(candidate.score());
                totalWindows += candidate.windowCount();
            }
            assertThat(candidates.subList(0, trace.returnedChunkIds().size())).extracting(RerankedCandidate::chunkId).containsExactlyElementsOf(trace.returnedChunkIds());
            assertThat(trace.returnedChunkIds()).hasSize(Math.min(10, candidates.size()));
            if (result.rank() != null) assertThat(trace.returnedChunkIds().get(result.rank() - 1)).isEqualTo(result.matchedChunkId());
            System.out.println("RETRIEVAL_TRACE question=" + result.id() + " rank=" + result.rank() + " chunk=" + result.matchedChunkId()
                    + " legs(vector/keyword/figure)=" + trace.vectorCandidates() + "/" + trace.keywordCandidates() + "/" + trace.figureCandidates()
                    + " fused=" + trace.fused().size() + " rerankInput=" + rerank.inputCount()
                    + " windows=" + candidates.stream().mapToInt(RerankedCandidate::windowCount).sum() + " returned=" + trace.returnedChunkIds());
        }
        for (var miss : evaluation.misses()) {
            var trace = evaluation.traces().stream().filter(t -> t.id().equals(miss.id())).findFirst().orElseThrow().trace();
            assertThat(miss.top()).extracting(RetrievalEvaluation.TopChunk::chunkId).containsExactlyElementsOf(trace.returnedChunkIds().subList(0, miss.top().size()));
        }

        // The stored document reads back with equal traces; its size with and without them.
        var stored = repository.findById(evaluation.id()).orElseThrow();
        assertThat(stored.traces()).isEqualTo(evaluation.traces());
        assertThat(stored.results()).isEqualTo(evaluation.results());
        Map<String, Object> size = jdbc.queryForMap("""
                SELECT octet_length(results::text) AS results_bytes, octet_length((results - 'traces')::text) AS without_traces_bytes,
                       octet_length((results -> 'traces')::text) AS traces_bytes, pg_column_size(results) AS stored_bytes,
                       pg_column_size(results - 'traces') AS stored_without_traces_bytes
                FROM retrieval_evaluations WHERE id = ?""", evaluation.id());
        System.out.println("RETRIEVAL_TRACE size " + size + " reference297ResultsBytes=" + reference.get("results").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + " totalWindows=" + totalWindows);
        String out = System.getProperty("rag.evaluation.trace-out");
        if (out != null && !out.isBlank()) {
            String row = jdbc.queryForObject("SELECT row_to_json(r)::text FROM retrieval_evaluations r WHERE id = ?", String.class, evaluation.id());
            Files.writeString(Path.of(out), row);
            System.out.println("RETRIEVAL_TRACE wrote " + out + " bytes=" + row.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }
    }
}

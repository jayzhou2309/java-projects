package project.stockrecommendationengine.rag.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import project.stockrecommendationengine.rag.evaluation.TraceReproductionCheck.ChunkIdentity;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalService;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.FusedCandidate;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.RerankedCandidate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/**
 * Opt-in (-Drag.evaluation.live=true; model files under models/; one embedding per question plus one warm-up, no chat model)
 * reproduction check for traced evaluation (plan 2026-09-13-evaluation-evidence, Milestone 1, C5; reference re-recorded 2026-09-18, plan
 * 2026-09-17-chunk-size.md Milestone 4, H4): a traced run at the settings of snapshot 297 (cross-encoder on, rerank true, rerank-candidates
 * 20, max-window scoring with overlap 64 and at most 4 windows, window 10, hybrid at its default, set v2) must reproduce, for every question,
 * the rank and the matched chunk of the committed reference {@link #REFERENCE}: snapshot 1892, a traced run at those settings on the store
 * of 2026-09-18 (the store after the rebuilds of 2026-09-17, whose chunk ids differ from those of the 2026-09-13 store that snapshot 297 was
 * run on; 1892 equals 297 rank for rank on the 42 questions, {@code live-runs/2026-09-18-rag29-section-key/comparison.txt}). A matched chunk
 * is compared by what it holds, never by its id: the reference's chunk is resolved through the committed exports {@link #CHUNK_HASHES}
 * (id, filing, section key, chunk index, length, content md5) and {@link #FILINGS} (filing id to accession number), the run's through the
 * same columns of {@code sec_filing_chunks} and {@code sec_filings} at test time, and the two must agree on accession number, section
 * key, chunk index, length, and md5 ({@link TraceReproductionCheck.ChunkIdentity}). The ranks are also compared with snapshot 297's
 * ({@link #SNAPSHOT_297}, rank only, since its chunk ids no longer exist). The cross-encoder and those settings are enabled only in this
 * test's own Spring context (the properties below), never through the environment. The comparison is {@link TraceReproductionCheck}
 * (unit-tested without a database in TraceReproductionCheckTests): it collects every problem, prints each one, and fails once naming every
 * question whose rank or matched chunk differs from the reference's, every question that fell back with its trace's reason, every errored
 * question, and then every run property that differs from the reference's (all of the reference's properties are compared; the reference
 * records {@code trace} true, so the run's must equal it). The snapshot is saved inside a transaction that is rolled back, so nothing is
 * left in the shared database. After the comparison passes it also checks, on the live data, one trace per question, each RERANKED, the
 * trace invariants (fused positions, every rerank input once, the first topK of the rerank order are the returned chunks, window scores
 * reduce to each score), that the stored traces read back equal, and prints the stored document's size with and without traces. With
 * -Drag.evaluation.trace-out=<file> the stored row (row_to_json) is written there before the rollback.
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
    /** The 2026-09-13 reference (the fixture of TraceReproductionCheckTests); its chunk ids are of a store that no longer exists, so ranks only. */
    static final Path SNAPSHOT_297 = Path.of("src/main/java/documentation/live-runs/2026-09-13-reranker-windows/measurement/snapshot-297-rerank-candidates-20.json");
    static final Path EVIDENCE = Path.of("src/main/java/documentation/live-runs/2026-09-18-rag29-section-key");
    /** The reference: snapshot 1892, a traced run at snapshot 297's settings on the store of 2026-09-18 (row_to_json export). */
    static final Path REFERENCE = EVIDENCE.resolve("snapshot-1892-r-traced-297-settings-rerank-candidates-20.json");
    static final long REFERENCE_ID = 1892L;
    /** chunk-hashes.sql over the store the reference was run on: id, filingId, chunkIndex, sectionKey, chars, contentMd5 per chunk. */
    static final Path CHUNK_HASHES = EVIDENCE.resolve("chunk-hashes.json");
    /** filings.sql over the same store: filingId to accessionNo. */
    static final Path FILINGS = EVIDENCE.resolve("filings.json");

    @Autowired RetrievalEvaluationService service;
    @Autowired RetrievalEvaluationRepository repository;
    @Autowired FilingRetrievalService retrieval;
    @Autowired JdbcTemplate jdbc;

    @Test void aTracedRunAtSnapshot297sSettingsReproducesTheReferencesRanksAndMatchedChunkContents() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        JsonNode reference = json.readTree(Files.readString(REFERENCE));
        assertThat(reference.get("id").asLong()).isEqualTo(REFERENCE_ID);
        JsonNode snapshot297 = json.readTree(Files.readString(SNAPSHOT_297));
        assertThat(snapshot297.get("id").asLong()).isEqualTo(297L);
        ChunkIdentity referenceChunks = exportedIdentity(json);
        ChunkIdentity runChunks = this::storedIdentity;

        // Warm-up, as the reference harness did after each start, so no evaluation call is a cold start.
        var warmUp = retrieval.retrieve(new RetrievalRequest("NVDA", "What was NVIDIA gross margin in fiscal 2026?", null, null, null, null, null, null, null, true));
        System.out.println("RETRIEVAL_TRACE warmUp strategy=" + warmUp.retrievalStrategy() + " results=" + warmUp.results().size());

        long started = System.nanoTime();
        RetrievalEvaluation evaluation = service.evaluate(null, true, true);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        Map<String, Object> properties = evaluation.properties();
        System.out.println("RETRIEVAL_TRACE snapshot id=" + evaluation.id() + " elapsedMs=" + elapsedMs + " hitAt1=" + evaluation.hitAt1()
                + " hitAt3=" + evaluation.hitAt3() + " hitAt5=" + evaluation.hitAt5() + " mrr=" + evaluation.mrr() + " properties=" + properties);

        // C5: every question's rank and matched chunk content, every fallback, every error, and every property against the reference's; all
        // problems are collected and printed first, then the test fails once with all of them.
        List<String> problems = TraceReproductionCheck.problems(reference, evaluation, Set.of(), referenceChunks, runChunks);
        System.out.println("RETRIEVAL_TRACE reproduction reference=" + REFERENCE_ID + " questions=" + reference.get("results").get("questions").size()
                + " results=" + evaluation.results().size() + " problems=" + problems.size());
        problems.forEach(problem -> System.out.println("RETRIEVAL_TRACE problem " + problem));
        for (QuestionResult result : evaluation.results()) {
            if (result.matchedChunkId() != null) System.out.println("RETRIEVAL_TRACE matched question=" + result.id() + " rank=" + result.rank()
                    + " run chunk " + result.matchedChunkId() + " (" + runChunks.of(result.matchedChunkId()) + ")");
        }
        TraceReproductionCheck.assertReproduces(reference, evaluation, Set.of(), referenceChunks, runChunks);

        // The ranks of snapshot 297 (the 2026-09-13 store) for the same questions, rank only.
        List<String> rankDifferences = new ArrayList<>();
        Map<String, QuestionResult> byId = new HashMap<>();
        evaluation.results().forEach(r -> byId.put(r.id(), r));
        for (JsonNode q : snapshot297.get("results").get("questions")) {
            QuestionResult result = byId.get(q.get("id").asString());
            Integer expected = q.get("rank").isNull() ? null : q.get("rank").asInt();
            if (result == null || !java.util.Objects.equals(expected, result.rank())) {
                rankDifferences.add(q.get("id").asString() + ": snapshot 297 rank " + expected + ", run rank " + (result == null ? "absent" : result.rank()));
            }
        }
        System.out.println("RETRIEVAL_TRACE ranks against snapshot 297: questions=" + snapshot297.get("results").get("questions").size()
                + " differing=" + rankDifferences.size() + " " + rankDifferences);
        assertThat(rankDifferences).as("ranks differing from snapshot 297").isEmpty();

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
        System.out.println("RETRIEVAL_TRACE size " + size + " referenceResultsBytes=" + reference.get("results").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + " totalWindows=" + totalWindows);
        String out = System.getProperty("rag.evaluation.trace-out");
        if (out != null && !out.isBlank()) {
            String row = jdbc.queryForObject("SELECT row_to_json(r)::text FROM retrieval_evaluations r WHERE id = ?", String.class, evaluation.id());
            Files.writeString(Path.of(out), row);
            System.out.println("RETRIEVAL_TRACE wrote " + out + " bytes=" + row.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }
    }

    /** The reference's chunk identities from the committed exports: null for an id the export does not list. */
    static ChunkIdentity exportedIdentity(JsonMapper json) throws Exception {
        Map<Long, String> accessions = new HashMap<>();
        for (JsonNode filing : json.readTree(Files.readString(FILINGS))) accessions.put(filing.get("filingId").asLong(), filing.get("accessionNo").asString());
        Map<Long, String> identities = new HashMap<>();
        for (JsonNode chunk : json.readTree(Files.readString(CHUNK_HASHES))) {
            String accession = accessions.get(chunk.get("filingId").asLong());
            assertThat(accession).as("filing %s of chunk %s in %s", chunk.get("filingId"), chunk.get("id"), FILINGS).isNotNull();
            identities.put(chunk.get("id").asLong(), identity(accession, chunk.get("sectionKey").asString(), chunk.get("chunkIndex").asInt(),
                    chunk.get("chars").asInt(), chunk.get("contentMd5").asString()));
        }
        System.out.println("RETRIEVAL_TRACE reference chunk export " + CHUNK_HASHES + " chunks=" + identities.size() + " filings=" + accessions.size());
        return identities::get;
    }

    /** The run's chunk identity from the shared database at test time: null for an id no stored chunk has. */
    private String storedIdentity(long chunkId) {
        List<String> rows = jdbc.query("""
                SELECT f.accession_no, c.section_key, c.chunk_index, length(c.content) AS chars, md5(c.content) AS md5
                FROM sec_filing_chunks c JOIN sec_filings f ON f.id = c.filing_id WHERE c.id = ?""",
                (rs, n) -> identity(rs.getString("accession_no"), rs.getString("section_key"), rs.getInt("chunk_index"), rs.getInt("chars"), rs.getString("md5")),
                chunkId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static String identity(String accession, String sectionKey, int chunkIndex, int chars, String md5) {
        return "accession " + accession + " section " + sectionKey + " index " + chunkIndex + " chars " + chars + " md5 " + md5;
    }
}

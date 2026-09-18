package project.stockrecommendationengine.rag.evaluation;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionResult;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionTrace;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationQuestion.Kind;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.Outcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/**
 * The C5 reproduction comparison ({@link TraceReproductionCheck}, used by {@code RetrievalEvaluationTraceLiveTests}) against the
 * committed snapshot 297, with no database or model (plan 2026-09-13-evaluation-evidence, Milestone 1, remediation 1, finding 1): a run
 * rebuilt from 297 reproduces it; a run modified so one rank differs, one question falls back with a rank difference, or one question
 * errors fails once with a message naming every differing question, every fallback with its trace reason, every error, and then every
 * property mismatch. Each failure message is printed (lines starting {@code TRACE_REPRODUCTION_MESSAGE}) so it can be read in the build
 * output.
 */
class TraceReproductionCheckTests {
    private JsonNode reference;

    @BeforeEach
    void load() throws Exception {
        reference = JsonMapper.builder().build().readTree(Files.readString(RetrievalEvaluationTraceLiveTests.SNAPSHOT_297));
        assertThat(reference.get("id").asLong()).isEqualTo(297L);
        assertThat(reference.get("results").get("questions").size()).isEqualTo(42);
    }

    @Test
    void aRunRebuiltFromSnapshot297ReproducesIt() {
        RetrievalEvaluation run = run(results -> results, Map.of());
        assertThat(run.properties()).hasSize(reference.get("properties").size() + 1).containsEntry("trace", true)
                .containsEntry("rerankedQuestions", 42).containsEntry("rerankFallbackQuestions", 0);
        assertThat(TraceReproductionCheck.problems(reference, run)).isEmpty();
        assertThatCode(() -> TraceReproductionCheck.assertReproduces(reference, run)).doesNotThrowAnyException();
    }

    @Test
    void oneRankDifferenceNamesTheQuestion() {
        RetrievalEvaluation run = run(results -> replace(results, "msft-05", r -> result(r, 9, 460L, r.retrievalStrategy(), null)), Map.of());
        assertThat(failure(run)).isEqualTo(message(1,
                "question msft-05: snapshot 297 rank 10 chunk 460, run rank 9 chunk 460"));
    }

    @Test
    void aFallbackWithARankDifferenceNamesTheQuestionTheFallbackAndItsReasonAndTheCounts() {
        // The case that previously failed on rerankFallbackQuestions alone: a timeout left nvda-11 in fused order at rank 7.
        RetrievalEvaluation run = run(results -> replace(results, "nvda-11", r -> result(r, 7, 805L, "HYBRID_RRF", null)),
                Map.of("nvda-11", new RetrievalTrace.Rerank(Outcome.FALLBACK, "timeout", 20, null, null)));
        assertThat(run.properties()).containsEntry("rerankedQuestions", 41).containsEntry("rerankFallbackQuestions", 1);
        assertThat(failure(run)).isEqualTo(message(4,
                "question nvda-11: snapshot 297 rank 5 chunk 805, run rank 7 chunk 805",
                "question nvda-11 fell back: strategy HYBRID_RRF (not _RERANKED), trace outcome FALLBACK reason timeout",
                "property rerankedQuestions: snapshot 297 42, run 41",
                "property rerankFallbackQuestions: snapshot 297 0, run 1"));
    }

    @Test
    void aFallbackWithoutAnErrorWhoseTraceIsNullIsNamedWithoutThrowing() {
        // Plan amendment 1: the fallback message looked the trace up with map then findFirst, which throws on a null trace.
        RetrievalEvaluation base = run(results -> replace(results, "nvda-11", r -> result(r, 5, 805L, "HYBRID_RRF", null)), Map.of());
        List<QuestionTrace> traces = base.traces().stream().map(t -> t.id().equals("nvda-11") ? new QuestionTrace(t.id(), null) : t).toList();
        RetrievalEvaluation run = new RetrievalEvaluation(base.id(), base.evaluatedAt(), base.setVersion(), base.questionCount(), base.hitAt1(),
                base.hitAt3(), base.hitAt5(), base.mrr(), base.window(), base.retrievalStrategy(), base.properties(), base.results(), base.tickerHitAt5(),
                base.misses(), base.slices(), traces);
        assertThat(run.results()).filteredOn(r -> r.id().equals("nvda-11")).singleElement().satisfies(r -> assertThat(r.error()).isNull());
        assertThatCode(() -> TraceReproductionCheck.problems(reference, run)).doesNotThrowAnyException();
        assertThat(failure(run)).isEqualTo(message(3,
                "question nvda-11 fell back: strategy HYBRID_RRF (not _RERANKED), no trace recorded for the question",
                "property rerankedQuestions: snapshot 297 42, run 41",
                "property rerankFallbackQuestions: snapshot 297 0, run 1"));
    }

    @Test
    void anErroredQuestionIsNamedWithItsError() {
        RetrievalEvaluation run = run(results -> replace(results, "aapl-04",
                r -> result(r, null, null, null, "IllegalStateException: Embedding request failed")), Map.of());
        assertThat(run.properties()).containsEntry("rerankedQuestions", 41).containsEntry("rerankFallbackQuestions", 0);
        assertThat(failure(run)).isEqualTo(message(3,
                "question aapl-04: snapshot 297 rank 1 chunk 210, run rank null chunk null",
                "question aapl-04 errored: IllegalStateException: Embedding request failed",
                "property rerankedQuestions: snapshot 297 42, run 41"));
    }

    @Test
    void allThreeTogetherFailOnceNamingEveryItemInOrder() {
        RetrievalEvaluation run = run(results -> {
            List<QuestionResult> out = replace(results, "msft-05", r -> result(r, 9, 460L, r.retrievalStrategy(), null));
            out = replace(out, "nvda-11", r -> result(r, 7, 805L, "HYBRID_RRF", null));
            return replace(out, "aapl-04", r -> result(r, null, null, null, "IllegalStateException: Embedding request failed"));
        }, Map.of("nvda-11", new RetrievalTrace.Rerank(Outcome.FALLBACK, "timeout", 20, null, null)));
        assertThat(failure(run)).isEqualTo(message(7,
                "question aapl-04: snapshot 297 rank 1 chunk 210, run rank null chunk null",
                "question msft-05: snapshot 297 rank 10 chunk 460, run rank 9 chunk 460",
                "question nvda-11: snapshot 297 rank 5 chunk 805, run rank 7 chunk 805",
                "question nvda-11 fell back: strategy HYBRID_RRF (not _RERANKED), trace outcome FALLBACK reason timeout",
                "question aapl-04 errored: IllegalStateException: Embedding request failed",
                "property rerankedQuestions: snapshot 297 42, run 40",
                "property rerankFallbackQuestions: snapshot 297 0, run 1"));
    }

    @Test
    void everyPropertyExceptTraceIsComparedAndTraceMustBeTrue() {
        RetrievalEvaluation base = run(results -> results, Map.of());
        Map<String, Object> properties = new LinkedHashMap<>(base.properties());
        properties.put("rerankerScoring", "head");
        properties.remove("rerankCandidates");
        properties.put("rerankTimeoutMs", 4000);
        properties.put("trace", false);
        RetrievalEvaluation run = new RetrievalEvaluation(base.id(), base.evaluatedAt(), base.setVersion(), base.questionCount(), base.hitAt1(),
                base.hitAt3(), base.hitAt5(), base.mrr(), base.window(), base.retrievalStrategy(), properties, base.results(), base.tickerHitAt5(),
                base.misses(), base.slices(), base.traces());
        assertThat(failure(run)).isEqualTo(message(4,
                "property rerankerScoring: snapshot 297 max-window/overlap=64/maxWindows=4, run head",
                "property rerankCandidates: snapshot 297 20, absent from the run",
                "property trace: expected true, run false",
                "property rerankTimeoutMs: absent from snapshot 297, run 4000"));
    }

    @Test
    void namedExemptPropertiesTheReferenceLacksAreAllowedWhenPresentAndComparedWhenBothRecordThem() {
        // Plan 2026-09-17-chunk-size.md, Milestone 3: a run against a store that records chunkMaxChars, chunkOverlapChars, and storeVersions
        // reproduces a reference stored before those properties existed, so the caller names them; everything else stays compared.
        RetrievalEvaluation base = run(results -> results, Map.of());
        Map<String, Object> properties = new LinkedHashMap<>(base.properties());
        properties.put("chunkMaxChars", 4000);
        properties.put("chunkOverlapChars", 500);
        properties.put("storeVersions", List.of("sections-v2-context-v2"));
        RetrievalEvaluation run = new RetrievalEvaluation(base.id(), base.evaluatedAt(), base.setVersion(), base.questionCount(), base.hitAt1(),
                base.hitAt3(), base.hitAt5(), base.mrr(), base.window(), base.retrievalStrategy(), properties, base.results(), base.tickerHitAt5(),
                base.misses(), base.slices(), base.traces());
        java.util.Set<String> exempt = new java.util.LinkedHashSet<>(List.of("chunkMaxChars", "chunkOverlapChars", "storeVersions"));
        assertThat(TraceReproductionCheck.problems(reference, run)).containsExactly(
                "property chunkMaxChars: absent from snapshot 297, run 4000",
                "property chunkOverlapChars: absent from snapshot 297, run 500",
                "property storeVersions: absent from snapshot 297, run [sections-v2-context-v2]");
        assertThat(TraceReproductionCheck.problems(reference, run, exempt)).isEmpty();
        // An exempt property the run does not carry is still a problem, and an exempt name the reference also records is still compared.
        properties.remove("storeVersions");
        properties.put("rerankCandidates", 40);
        assertThat(TraceReproductionCheck.problems(reference, run, new java.util.LinkedHashSet<>(List.of("storeVersions", "rerankCandidates")))).containsExactly(
                "property rerankCandidates: snapshot 297 20, run 40",
                "property chunkMaxChars: absent from snapshot 297, run 4000",
                "property chunkOverlapChars: absent from snapshot 297, run 500",
                "property storeVersions: exempt from the comparison with snapshot 297 but absent from the run");
    }

    @Test
    void aListPropertyTheReferenceRecordsIsComparedWithTheRunsList() throws Exception {
        // Plan 2026-09-17-chunk-size-pool.md, G2: the reference (snapshot 1615) records storeVersions, a list; it is compared, not exempt.
        tools.jackson.databind.node.ObjectNode withList = (tools.jackson.databind.node.ObjectNode) reference.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) withList.get("properties")).putArray("storeVersions").add("sections-v2-context-v2-chunk4000-500");
        RetrievalEvaluation base = run(results -> results, Map.of());
        Map<String, Object> properties = new LinkedHashMap<>(base.properties());
        properties.put("storeVersions", List.of("sections-v2-context-v2-chunk4000-500"));
        RetrievalEvaluation run = new RetrievalEvaluation(base.id(), base.evaluatedAt(), base.setVersion(), base.questionCount(), base.hitAt1(),
                base.hitAt3(), base.hitAt5(), base.mrr(), base.window(), base.retrievalStrategy(), properties, base.results(), base.tickerHitAt5(),
                base.misses(), base.slices(), base.traces());
        assertThat(TraceReproductionCheck.problems(withList, run)).isEmpty();
        properties.put("storeVersions", List.of("sections-v2-context-v2-chunk1650-250"));
        assertThat(TraceReproductionCheck.problems(withList, run)).containsExactly(
                "property storeVersions: snapshot 297 [sections-v2-context-v2-chunk4000-500], run [sections-v2-context-v2-chunk1650-250]");
    }

    @Test
    void matchedChunksAreComparedByContentWhenBothSidesResolveThem() {
        // Plan 2026-09-17-chunk-size.md, Milestone 4 (H4): a rebuild renumbers chunks, so a run whose matched chunk ids all differ from the
        // reference's reproduces it when each pair resolves to the same content identity; a differing identity, or an id a side cannot
        // resolve, is a problem printing both ids with their identities. The identity here is a stand-in text keyed by id.
        Map<Long, String> referenceIdentity = new LinkedHashMap<>();
        Map<Long, String> runIdentity = new LinkedHashMap<>();
        for (JsonNode q : reference.get("results").get("questions")) {
            if (q.get("matchedChunkId").isNull()) continue;
            long id = q.get("matchedChunkId").asLong();
            String identity = "accession A section S index 0 chars 100 md5 " + id;
            referenceIdentity.put(id, identity);
            runIdentity.put(id + 10_000, identity);
        }
        RetrievalEvaluation run = run(results -> results.stream().map(r -> r.matchedChunkId() == null ? r
                : result(r, r.rank(), r.matchedChunkId() + 10_000, r.retrievalStrategy(), null)).toList(), Map.of());
        assertThat(run.results()).noneMatch(r -> r.matchedChunkId() != null && referenceIdentity.containsKey(r.matchedChunkId()));
        long matched = run.results().stream().filter(r -> r.matchedChunkId() != null).count();
        assertThat(TraceReproductionCheck.problems(reference, run)).as("by id, every matched question differs").hasSize((int) matched);
        assertThat(matched).isEqualTo(35).isGreaterThan(referenceIdentity.size()); // three chunk ids are matched by two questions each
        TraceReproductionCheck.ChunkIdentity ofReference = referenceIdentity::get;
        TraceReproductionCheck.ChunkIdentity ofRun = runIdentity::get;
        assertThat(TraceReproductionCheck.problems(reference, run, java.util.Set.of(), ofReference, ofRun)).isEmpty();
        assertThatCode(() -> TraceReproductionCheck.assertReproduces(reference, run, java.util.Set.of(), ofReference, ofRun)).doesNotThrowAnyException();

        // msft-05's run chunk holds other content; aapl-04's run id is unknown to the run's resolver; nvda-11 has no matched chunk on the run side.
        runIdentity.put(460L + 10_000, "accession A section S index 1 chars 200 md5 other");
        runIdentity.remove(210L + 10_000);
        RetrievalEvaluation changed = run(results -> replace(run.results(), "nvda-11", r -> result(r, null, null, r.retrievalStrategy(), null)), Map.of());
        assertThat(TraceReproductionCheck.problems(reference, changed, java.util.Set.of(), ofReference, ofRun)).containsExactly(
                "question aapl-04: snapshot 297 rank 1 chunk 210 (accession A section S index 0 chars 100 md5 210), run rank 1 chunk 10210 (unknown to the run)",
                "question msft-05: snapshot 297 rank 10 chunk 460 (accession A section S index 0 chars 100 md5 460), run rank 10 chunk 10460 (accession A section S index 1 chars 200 md5 other)",
                "question nvda-11: snapshot 297 rank 5 chunk 805 (accession A section S index 0 chars 100 md5 805), run rank null chunk null (no matched chunk)");
        assertThatThrownBy(() -> TraceReproductionCheck.problems(reference, run, java.util.Set.of(), ofReference, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private String failure(RetrievalEvaluation run) {
        Throwable thrown = catchThrowable(() -> TraceReproductionCheck.assertReproduces(reference, run));
        assertThat(thrown).isInstanceOf(AssertionError.class);
        System.out.println("TRACE_REPRODUCTION_MESSAGE " + thrown.getMessage());
        return thrown.getMessage();
    }

    private static String message(int count, String... problems) {
        StringBuilder out = new StringBuilder("The traced run does not reproduce snapshot 297: " + count + " problem" + (count == 1 ? "" : "s"));
        for (String problem : problems) out.append(System.lineSeparator()).append("  - ").append(problem);
        assertThat(problems).hasSize(count);
        return out.toString();
    }

    /**
     * A traced run rebuilt from snapshot 297: its question results, a RERANKED trace per question (or {@code reranks}' record, null trace
     * for an errored question), its properties with {@code trace} true, and the reranked and fallback counts recomputed from the
     * results by the service's rule.
     */
    private RetrievalEvaluation run(UnaryOperator<List<QuestionResult>> modify, Map<String, RetrievalTrace.Rerank> reranks) {
        List<QuestionResult> results = new ArrayList<>();
        for (JsonNode q : reference.get("results").get("questions")) {
            results.add(new QuestionResult(q.get("id").asString(), q.get("ticker").asString(), Kind.valueOf(q.get("kind").asString()),
                    q.get("rank").isNull() ? null : q.get("rank").asInt(), q.get("matchedChunkId").isNull() ? null : q.get("matchedChunkId").asLong(),
                    null, q.get("retrievalStrategy").asString()));
        }
        results = modify.apply(results);
        List<QuestionTrace> traces = results.stream().map(r -> new QuestionTrace(r.id(), r.error() != null ? null
                : new RetrievalTrace(40, 40, null, List.of(), reranks.getOrDefault(r.id(), new RetrievalTrace.Rerank(Outcome.RERANKED, null, 20, null, List.of())),
                        List.of()))).toList();
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : reference.get("properties").properties()) {
            JsonNode value = field.getValue();
            properties.put(field.getKey(), value.isNull() ? null : value.isBoolean() ? value.booleanValue() : value.isInt() ? value.intValue()
                    : value.isFloatingPointNumber() ? value.doubleValue() : value.asString());
        }
        properties.put("rerankedQuestions", (int) results.stream().filter(RetrievalEvaluationService::reranked).count());
        properties.put("rerankFallbackQuestions", (int) results.stream().filter(r -> r.error() == null && !RetrievalEvaluationService.reranked(r)).count());
        properties.put("trace", true);
        return new RetrievalEvaluation(1L, Instant.now(), reference.get("set_version").asString(), results.size(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 10, "HYBRID_RRF_RERANKED", properties, List.copyOf(results), Map.of(), List.of(), Map.of(), traces);
    }

    private static List<QuestionResult> replace(List<QuestionResult> results, String id, UnaryOperator<QuestionResult> change) {
        assertThat(results).extracting(QuestionResult::id).contains(id);
        return results.stream().map(r -> r.id().equals(id) ? change.apply(r) : r).toList();
    }

    private static QuestionResult result(QuestionResult r, Integer rank, Long chunk, String strategy, String error) {
        return new QuestionResult(r.id(), r.ticker(), r.kind(), rank, chunk, error, strategy);
    }
}

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

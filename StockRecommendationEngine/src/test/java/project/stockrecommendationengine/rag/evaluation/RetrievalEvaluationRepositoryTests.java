package project.stockrecommendationengine.rag.evaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.Miss;
import org.springframework.jdbc.core.JdbcTemplate;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionResult;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.RankedQuestion;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.SliceMetrics;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.TopChunk;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationQuestion.Kind;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class RetrievalEvaluationRepositoryTests {
    @Autowired RetrievalEvaluationRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test void snapshotsRoundTripAndTheNewestIsReturned() {
        // Real snapshots may exist in the shared database; timestamps in the future keep these rows the newest.
        Instant base = Instant.now().plusSeconds(3600);
        var results = List.of(new QuestionResult("aapl-1", "AAPL", Kind.FIGURE, 1, 101L, null, "HYBRID_RRF_RERANKED"),
                new QuestionResult("aapl-2", "AAPL", Kind.NARRATIVE, null, null, null, "HYBRID_RRF"),
                new QuestionResult("msft-1", "MSFT", Kind.NARRATIVE, null, null, "IllegalStateException: embedding unavailable", null));
        var misses = List.of(new Miss("aapl-2", List.of(new TopChunk(5L, "0000320193-25-000079", "ITEM_8", new BigDecimal("0.812345")),
                        new TopChunk(6L, "0000320193-26-000020", "ITEM_2", new BigDecimal("0.700000"))), null),
                new Miss("msft-1", List.of(), "IllegalStateException: embedding unavailable"));
        var first = repository.save(new RetrievalEvaluation(null, base, "v1", 3, new BigDecimal("0.333333"), new BigDecimal("0.333333"),
                new BigDecimal("0.333333"), new BigDecimal("0.333333"), 10, "FILTERED_VECTOR",
                Map.of("window", 10, "latestFilingsOnly", true), results, Map.of("AAPL", new BigDecimal("0.500000"), "MSFT", new BigDecimal("0.000000")), misses, null));
        assertThat(first.id()).isNotNull();
        var second = repository.save(new RetrievalEvaluation(null, base.plusSeconds(60), "v1", 3, new BigDecimal("0.666667"), new BigDecimal("1"),
                new BigDecimal("1"), new BigDecimal("0.833333"), 10, "FILTERED_VECTOR_RERANKED", Map.of("window", 10), results, Map.of(), List.of(), null));
        assertThat(second.id()).isNotNull().isNotEqualTo(first.id());

        var latest = repository.latest().orElseThrow();
        assertThat(latest.id()).isEqualTo(second.id());
        assertThat(latest.retrievalStrategy()).isEqualTo("FILTERED_VECTOR_RERANKED");
        assertThat(latest.hitAt3()).isEqualByComparingTo("1");
        assertThat(latest.evaluatedAt()).isEqualTo(base.plusSeconds(60));

        var stored = repository.findById(first.id()).orElseThrow();
        assertThat(stored.id()).isEqualTo(first.id());
        assertThat(stored.evaluatedAt()).isEqualTo(base);
        assertThat(stored.setVersion()).isEqualTo("v1");
        assertThat(stored.questionCount()).isEqualTo(3);
        assertThat(stored.window()).isEqualTo(10);
        assertThat(stored.hitAt1()).isEqualByComparingTo("0.333333");
        assertThat(stored.mrr()).isEqualByComparingTo("0.333333");
        assertThat(stored.properties()).containsEntry("window", 10).containsEntry("latestFilingsOnly", true);
        assertThat(stored.results()).extracting(QuestionResult::id).containsExactly("aapl-1", "aapl-2", "msft-1");
        assertThat(stored.results()).extracting(QuestionResult::rank).containsExactly(1, null, null);
        // Reranker milestone 2 (Amendment 1): the per-question strategy round-trips, null on an errored question.
        assertThat(stored.results()).extracting(QuestionResult::retrievalStrategy).containsExactly("HYBRID_RRF_RERANKED", "HYBRID_RRF", null);
        assertThat(stored.results().get(0).matchedChunkId()).isEqualTo(101L);
        assertThat(stored.results().get(0).kind()).isEqualTo(Kind.FIGURE);
        assertThat(stored.results().get(2).error()).contains("embedding unavailable");
        assertThat(stored.tickerHitAt5()).containsEntry("AAPL", new BigDecimal("0.500000")).containsEntry("MSFT", new BigDecimal("0.000000"));
        assertThat(stored.misses()).extracting(Miss::id).containsExactly("aapl-2", "msft-1");
        assertThat(stored.misses().get(0).top()).extracting(TopChunk::chunkId).containsExactly(5L, 6L);
        assertThat(stored.misses().get(0).top().get(0).sectionKey()).isEqualTo("ITEM_8");
        assertThat(stored.misses().get(0).top().get(0).similarity()).isEqualByComparingTo("0.812345");
        assertThat(stored.misses().get(1).error()).contains("embedding unavailable");
        assertThat(stored.misses().get(1).top()).isEmpty();
        assertThat(repository.findById(-1L)).isEmpty();
    }

    @Test void slicesRoundTripAndARowWrittenBeforeSlicesReadsBackWithNullSlices() {
        Instant base = Instant.now().plusSeconds(7200);
        var results = List.of(new QuestionResult("aapl-1", "AAPL", Kind.FIGURE, 1, 101L, null, null),
                new QuestionResult("aapl-2", "AAPL", Kind.NARRATIVE, null, null, null, null));
        var slices = new java.util.LinkedHashMap<String, SliceMetrics>();
        slices.put("figure", new SliceMetrics(1, new BigDecimal("1.000000"), new BigDecimal("1.000000"), new BigDecimal("1.000000"), new BigDecimal("1.000000"), List.of(),
                List.of(new RankedQuestion("aapl-3", 7))));
        slices.put("nonFigure", new SliceMetrics(1, new BigDecimal("0.000000"), new BigDecimal("0.000000"), new BigDecimal("0.000000"), new BigDecimal("0.000000"), List.of("aapl-2"),
                List.of(new RankedQuestion("aapl-2", null))));
        var saved = repository.save(new RetrievalEvaluation(null, base, "v2", 2, new BigDecimal("0.500000"), new BigDecimal("0.500000"),
                new BigDecimal("0.500000"), new BigDecimal("0.500000"), 10, "HYBRID_RRF", Map.of("window", 10), results, Map.of("AAPL", new BigDecimal("0.500000")),
                List.of(new Miss("aapl-2", List.of(), null)), slices));

        var stored = repository.findById(saved.id()).orElseThrow();
        assertThat(stored.slices()).containsOnlyKeys("figure", "nonFigure");
        assertThat(stored.slices().get("figure")).isEqualTo(slices.get("figure"));
        assertThat(stored.slices().get("nonFigure")).isEqualTo(slices.get("nonFigure"));
        assertThat(stored.slices().get("nonFigure").hitAt5().scale()).isEqualTo(6);
        assertThat(stored.slices().get("figure").notInTop5()).containsExactly(new RankedQuestion("aapl-3", 7));
        assertThat(stored.slices().get("nonFigure").notInTop5()).containsExactly(new RankedQuestion("aapl-2", null));
        var emptySlice = new SliceMetrics(0, null, null, null, null, List.of(), List.of());
        assertThat(repository.save(saved.withId(null)).slices()).as("withId carries the slices").isEqualTo(slices);

        var emptySaved = repository.save(new RetrievalEvaluation(null, base.plusSeconds(1), "v2", 0, new BigDecimal("0.000000"), new BigDecimal("0.000000"),
                new BigDecimal("0.000000"), new BigDecimal("0.000000"), 10, "UNAVAILABLE", Map.of(), List.of(), Map.of(), List.of(),
                Map.of("figure", emptySlice, "nonFigure", emptySlice)));
        assertThat(repository.findById(emptySaved.id()).orElseThrow().slices()).containsEntry("figure", emptySlice).containsEntry("nonFigure", emptySlice);

        // A row as stored before slices existed: the results document has only questions, tickerHitAt5, and misses.
        Long legacyId = jdbc.queryForObject("""
                INSERT INTO retrieval_evaluations (evaluated_at, set_version, question_count, hit_at_1, hit_at_3, hit_at_5, mrr,
                    window_size, retrieval_strategy, properties, results)
                VALUES (?, 'v2', 1, 1, 1, 1, 1, 10, 'HYBRID_RRF', CAST('{"window": 10}' AS jsonb),
                    CAST('{"questions": [{"id": "aapl-1", "ticker": "AAPL", "kind": "FIGURE", "rank": 1, "matchedChunkId": 101, "error": null}], "tickerHitAt5": {"AAPL": 1.000000}, "misses": []}' AS jsonb))
                RETURNING id
                """, Long.class, java.sql.Timestamp.from(base.plusSeconds(2)));
        var legacy = repository.findById(legacyId).orElseThrow();
        assertThat(legacy.slices()).isNull();
        assertThat(legacy.traces()).isNull();
        // Reranker milestone 1, C4: a snapshot stored before rerank existed reads back with its properties unchanged.
        assertThat(legacy.properties()).containsExactly(Map.entry("window", 10));
        assertThat(legacy.results()).extracting(QuestionResult::rank).containsExactly(1);
        // Reranker milestone 2: a question stored before the per-question strategy existed reads back with it null.
        assertThat(legacy.results()).extracting(QuestionResult::retrievalStrategy).containsOnlyNulls();
        assertThat(repository.latest().orElseThrow().id()).isEqualTo(legacyId);
        assertThat(repository.latest().orElseThrow().slices()).isNull();
    }

    // Evaluation evidence Milestone 1, C4: traces round-trip; an untraced document has no traces key; older rows read back with null.

    @Test void tracesRoundTripAndAnUntracedSnapshotStoresNoTracesKey() {
        Instant base = Instant.now().plusSeconds(14400);
        var results = List.of(new QuestionResult("msft-05", "MSFT", Kind.NARRATIVE, 2, 460L, null, "HYBRID_RRF_RERANKED"),
                new QuestionResult("msft-06", "MSFT", Kind.NARRATIVE, null, null, "IllegalStateException: embedding unavailable", null));
        var reranked = new RetrievalTrace(40, 38, null,
                List.of(new RetrievalTrace.FusedCandidate(571, 1, 1, 2, null), new RetrievalTrace.FusedCandidate(460, 2, null, 1, null),
                        new RetrievalTrace.FusedCandidate(515, 3, 2, null, null)),
                new RetrievalTrace.Rerank(RetrievalTrace.Outcome.RERANKED, null, 3, null, List.of(
                        new RetrievalTrace.RerankedCandidate(515, 3, 1, 6.25f, 3, List.of(-11.087456f, 6.25f, 0.1f)),
                        new RetrievalTrace.RerankedCandidate(460, 2, 2, 5.0303345f, 1, List.of(5.0303345f)),
                        new RetrievalTrace.RerankedCandidate(571, 1, 3, -2.5f, 2, List.of(-2.5f, -3.75f)))),
                List.of(515L, 460L));
        var fallback = new RetrievalTrace(40, null, null, List.of(new RetrievalTrace.FusedCandidate(7, 1, 1, null, null)),
                new RetrievalTrace.Rerank(RetrievalTrace.Outcome.FALLBACK, "timeout", 1, null, null), List.of(7L));
        var traces = List.of(new RetrievalEvaluation.QuestionTrace("msft-05", reranked), new RetrievalEvaluation.QuestionTrace("msft-06", null),
                new RetrievalEvaluation.QuestionTrace("msft-07", fallback));
        var saved = repository.save(new RetrievalEvaluation(null, base, "v2", 2, new BigDecimal("0.000000"), new BigDecimal("0.500000"),
                new BigDecimal("0.500000"), new BigDecimal("0.250000"), 10, "HYBRID_RRF_RERANKED", Map.of("window", 10, "trace", true), results,
                Map.of("MSFT", new BigDecimal("0.500000")), List.of(), null, traces));

        var stored = repository.findById(saved.id()).orElseThrow();
        assertThat(stored.traces()).isEqualTo(traces);
        assertThat(stored.traces().get(0).trace().rerank().candidates().get(0).windowScores()).containsExactly(-11.087456f, 6.25f, 0.1f);
        assertThat(stored.properties()).containsEntry("trace", true);
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(results, 'traces') FROM retrieval_evaluations WHERE id = ?", Boolean.class, saved.id())).isTrue();

        var untraced = repository.save(new RetrievalEvaluation(null, base.plusSeconds(1), "v2", 2, new BigDecimal("0.000000"), new BigDecimal("0.500000"),
                new BigDecimal("0.500000"), new BigDecimal("0.250000"), 10, "HYBRID_RRF", Map.of("window", 10, "trace", false), results,
                Map.of("MSFT", new BigDecimal("0.500000")), List.of(), null));
        assertThat(repository.findById(untraced.id()).orElseThrow().traces()).isNull();
        // The untraced document keeps exactly the keys it had before traces existed.
        assertThat(jdbc.queryForList("SELECT jsonb_object_keys(results) FROM retrieval_evaluations WHERE id = ?", String.class, untraced.id()))
                .containsExactlyInAnyOrder("questions", "tickerHitAt5", "misses", "slices");
    }

    @Test void theCommittedSnapshot297StoredBeforeTracesReadsBackUnchangedWithTracesNull() throws Exception {
        // Snapshot 297 as committed (row_to_json of the stored row, 2026-09-13), inserted with its own properties and results documents.
        var file = java.nio.file.Path.of("src/main/java/documentation/live-runs/2026-09-13-reranker-windows/measurement/snapshot-297-rerank-candidates-20.json");
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var row = mapper.readTree(java.nio.file.Files.readString(file));
        Long id = jdbc.queryForObject("""
                INSERT INTO retrieval_evaluations (evaluated_at, set_version, question_count, hit_at_1, hit_at_3, hit_at_5, mrr,
                    window_size, retrieval_strategy, properties, results)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb)) RETURNING id
                """, Long.class, java.sql.Timestamp.from(Instant.now().plusSeconds(18000)), row.get("set_version").asString(), row.get("question_count").asInt(),
                row.get("hit_at_1").decimalValue(), row.get("hit_at_3").decimalValue(), row.get("hit_at_5").decimalValue(), row.get("mrr").decimalValue(),
                row.get("window_size").asInt(), row.get("retrieval_strategy").asString(), row.get("properties").toString(), row.get("results").toString());
        var stored = repository.findById(id).orElseThrow();
        assertThat(stored.traces()).isNull();
        assertThat(stored.properties()).doesNotContainKey("trace").containsEntry("rerankCandidates", 20)
                .containsEntry("rerankerScoring", "max-window/overlap=64/maxWindows=4");
        assertThat(stored.results()).hasSize(42);
        var questions = row.get("results").get("questions");
        for (int i = 0; i < 42; i++) {
            var question = questions.get(i);
            assertThat(stored.results().get(i).id()).isEqualTo(question.get("id").asString());
            assertThat(stored.results().get(i).rank()).isEqualTo(question.get("rank").isNull() ? null : question.get("rank").asInt());
            assertThat(stored.results().get(i).matchedChunkId()).isEqualTo(question.get("matchedChunkId").isNull() ? null : question.get("matchedChunkId").asLong());
        }
        assertThat(stored.hitAt5()).isEqualByComparingTo("0.785714");
        assertThat(stored.slices()).containsOnlyKeys("figure", "nonFigure");
        assertThat(stored.misses()).hasSize(row.get("results").get("misses").size());
        // Written back unchanged by this code, the document has the same keys (no traces key is added).
        var rewritten = repository.save(stored.withId(null));
        assertThat(jdbc.queryForList("SELECT jsonb_object_keys(results) FROM retrieval_evaluations WHERE id = ?", String.class, rewritten.id()))
                .containsExactlyInAnyOrder("questions", "tickerHitAt5", "misses", "slices");
    }

    // Plan 2026-09-14-retrieval-recall, Milestone 3, D3: a trace's removed list round-trips; traces stored before it read back with it null.

    @Test void removedListsRoundTripAndTheCommittedSnapshot598ReadsBackWithRemovedNull() throws Exception {
        Instant base = Instant.now().plusSeconds(21600);
        var results = List.of(new QuestionResult("aapl-01", "AAPL", Kind.FIGURE, 1, 7L, null, "HYBRID_RRF"),
                new QuestionResult("aapl-02", "AAPL", Kind.FIGURE, 1, 8L, null, "HYBRID_RRF"));
        var withRemovals = new RetrievalTrace(40, 40, null, List.of(new RetrievalTrace.FusedCandidate(7, 1, 1, 1, null)),
                new RetrievalTrace.Rerank(RetrievalTrace.Outcome.OFF, null, null, null, null), List.of(7L),
                List.of(new RetrievalTrace.RemovedCandidate(9, 2, 3, null, null, 7)));
        var nothingRemoved = new RetrievalTrace(40, 40, null, List.of(new RetrievalTrace.FusedCandidate(8, 1, 1, 1, null)),
                new RetrievalTrace.Rerank(RetrievalTrace.Outcome.OFF, null, null, null, null), List.of(8L), List.of());
        var traces = List.of(new RetrievalEvaluation.QuestionTrace("aapl-01", withRemovals), new RetrievalEvaluation.QuestionTrace("aapl-02", nothingRemoved));
        var saved = repository.save(new RetrievalEvaluation(null, base, "v2", 2, new BigDecimal("1.000000"), new BigDecimal("1.000000"),
                new BigDecimal("1.000000"), new BigDecimal("1.000000"), 10, "HYBRID_RRF", Map.of("window", 10, "trace", true), results,
                Map.of("AAPL", new BigDecimal("1.000000")), List.of(), null, traces));
        var stored = repository.findById(saved.id()).orElseThrow();
        assertThat(stored.traces()).isEqualTo(traces);
        assertThat(stored.traces().get(1).trace().removed()).as("nothing removed stays an empty list").isNotNull().isEmpty();

        var file = java.nio.file.Path.of("src/main/java/documentation/live-runs/2026-09-13-evaluation-evidence/measurement/snapshot-598-traced-snapshot-295-reference-rerank-off.json");
        var row = tools.jackson.databind.json.JsonMapper.builder().build().readTree(java.nio.file.Files.readString(file));
        Long id = jdbc.queryForObject("""
                INSERT INTO retrieval_evaluations (evaluated_at, set_version, question_count, hit_at_1, hit_at_3, hit_at_5, mrr,
                    window_size, retrieval_strategy, properties, results)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb)) RETURNING id
                """, Long.class, java.sql.Timestamp.from(base.plusSeconds(1)), row.get("set_version").asString(), row.get("question_count").asInt(),
                row.get("hit_at_1").decimalValue(), row.get("hit_at_3").decimalValue(), row.get("hit_at_5").decimalValue(), row.get("mrr").decimalValue(),
                row.get("window_size").asInt(), row.get("retrieval_strategy").asString(), row.get("properties").toString(), row.get("results").toString());
        var legacy = repository.findById(id).orElseThrow();
        assertThat(legacy.traces()).hasSize(42).allSatisfy(trace -> assertThat(trace.trace().removed()).as(trace.id()).isNull());
        // Written back, the older traces gain no removed key.
        var rewritten = repository.save(legacy.withId(null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval_evaluations, jsonb_array_elements(results->'traces') t WHERE id = ? AND jsonb_exists(t->'trace', 'removed')",
                Integer.class, rewritten.id())).isZero();
    }

    @Test void aRowWithSlicesButNoNotInTop5ReadsBackWithoutAnExceptionAndANullList() {
        // As stored by the first slice version (snapshot 91): each slice carries missIds but no notInTop5.
        Long id = jdbc.queryForObject("""
                INSERT INTO retrieval_evaluations (evaluated_at, set_version, question_count, hit_at_1, hit_at_3, hit_at_5, mrr,
                    window_size, retrieval_strategy, properties, results)
                VALUES (?, 'v2', 2, 0.5, 0.5, 0.5, 0.5, 10, 'HYBRID_RRF', CAST('{"window": 10}' AS jsonb),
                    CAST('{"questions": [{"id": "aapl-1", "ticker": "AAPL", "kind": "FIGURE", "rank": 1, "matchedChunkId": 101, "error": null},
                                         {"id": "aapl-2", "ticker": "AAPL", "kind": "NARRATIVE", "rank": null, "matchedChunkId": null, "error": null}],
                           "tickerHitAt5": {"AAPL": 0.500000}, "misses": [{"id": "aapl-2", "top": [], "error": null}],
                           "slices": {"figure": {"questionCount": 1, "hitAt1": 1.000000, "hitAt3": 1.000000, "hitAt5": 1.000000, "mrr": 1.000000, "missIds": []},
                                      "nonFigure": {"questionCount": 1, "hitAt1": 0.000000, "hitAt3": 0.000000, "hitAt5": 0.000000, "mrr": 0.000000, "missIds": ["aapl-2"]}}}' AS jsonb))
                RETURNING id
                """, Long.class, java.sql.Timestamp.from(Instant.now().plusSeconds(10800)));
        var stored = repository.findById(id).orElseThrow();
        assertThat(stored.slices()).containsOnlyKeys("figure", "nonFigure");
        assertThat(stored.slices().get("nonFigure").missIds()).containsExactly("aapl-2");
        assertThat(stored.slices().get("nonFigure").notInTop5()).isNull();
        assertThat(stored.slices().get("figure").notInTop5()).isNull();
        assertThat(RetrievalEvaluationFloors.describe(stored.slices().get("nonFigure").notInTop5())).isEqualTo("not recorded");
    }
}

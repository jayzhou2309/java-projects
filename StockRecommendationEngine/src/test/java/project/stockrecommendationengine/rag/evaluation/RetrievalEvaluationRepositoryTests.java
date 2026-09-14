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
        // Reranker milestone 1, C4: a snapshot stored before rerank existed reads back with its properties unchanged.
        assertThat(legacy.properties()).containsExactly(Map.entry("window", 10));
        assertThat(legacy.results()).extracting(QuestionResult::rank).containsExactly(1);
        // Reranker milestone 2: a question stored before the per-question strategy existed reads back with it null.
        assertThat(legacy.results()).extracting(QuestionResult::retrievalStrategy).containsOnlyNulls();
        assertThat(repository.latest().orElseThrow().id()).isEqualTo(legacyId);
        assertThat(repository.latest().orElseThrow().slices()).isNull();
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

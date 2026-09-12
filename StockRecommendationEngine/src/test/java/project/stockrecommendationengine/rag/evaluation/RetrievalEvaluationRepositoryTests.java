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
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionResult;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.TopChunk;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationQuestion.Kind;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class RetrievalEvaluationRepositoryTests {
    @Autowired RetrievalEvaluationRepository repository;

    @Test void snapshotsRoundTripAndTheNewestIsReturned() {
        // Real snapshots may exist in the shared database; timestamps in the future keep these rows the newest.
        Instant base = Instant.now().plusSeconds(3600);
        var results = List.of(new QuestionResult("aapl-1", "AAPL", Kind.FIGURE, 1, 101L, null),
                new QuestionResult("aapl-2", "AAPL", Kind.NARRATIVE, null, null, null),
                new QuestionResult("msft-1", "MSFT", Kind.NARRATIVE, null, null, "IllegalStateException: embedding unavailable"));
        var misses = List.of(new Miss("aapl-2", List.of(new TopChunk(5L, "0000320193-25-000079", "ITEM_8", new BigDecimal("0.812345")),
                        new TopChunk(6L, "0000320193-26-000020", "ITEM_2", new BigDecimal("0.700000"))), null),
                new Miss("msft-1", List.of(), "IllegalStateException: embedding unavailable"));
        var first = repository.save(new RetrievalEvaluation(null, base, "v1", 3, new BigDecimal("0.333333"), new BigDecimal("0.333333"),
                new BigDecimal("0.333333"), new BigDecimal("0.333333"), 10, "FILTERED_VECTOR",
                Map.of("window", 10, "latestFilingsOnly", true), results, Map.of("AAPL", new BigDecimal("0.500000"), "MSFT", new BigDecimal("0.000000")), misses));
        assertThat(first.id()).isNotNull();
        var second = repository.save(new RetrievalEvaluation(null, base.plusSeconds(60), "v1", 3, new BigDecimal("0.666667"), new BigDecimal("1"),
                new BigDecimal("1"), new BigDecimal("0.833333"), 10, "FILTERED_VECTOR_RERANKED", Map.of("window", 10), results, Map.of(), List.of()));
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
}

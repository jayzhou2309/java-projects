package project.stockrecommendationengine.rag.retrieval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import project.stockrecommendationengine.rag.dto.RetrievalRequest;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.ingestion.FilingEmbeddingService;
import project.stockrecommendationengine.rag.repository.FilingRetrievalRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Ordering of the cross-encoder reranker against scripted scores (no model), and its fallback through retrieval. */
@ExtendWith(OutputCaptureExtension.class)
class CrossEncoderRerankerTests {

    /** Scores each passage by a fixed table keyed on its text; records the calls. */
    static final class TableScorer implements PairScorer {
        final Map<String, Float> table;
        final AtomicInteger calls = new AtomicInteger();
        volatile List<String> lastPassages;
        volatile String lastQuery;

        TableScorer(Map<String, Float> table) {
            this.table = table;
        }

        @Override
        public float[] score(String query, List<String> passages) {
            calls.incrementAndGet();
            lastQuery = query;
            lastPassages = passages;
            float[] scores = new float[passages.size()];
            for (int i = 0; i < scores.length; i++) scores[i] = table.getOrDefault(passages.get(i), 0f);
            return scores;
        }
    }

    @Test
    void ordersByScoreDescendingAndReturnsTopKOfTheSameRecords() {
        var candidates = List.of(chunk(1, "a"), chunk(2, "b"), chunk(3, "c"), chunk(4, "d"));
        var scorer = new TableScorer(Map.of("a", 0.1f, "b", 3.5f, "c", -2f, "d", 7.25f));
        var reranker = new CrossEncoderReranker(scorer, "5d3e70fd0c9f");

        var ranked = reranker.rerank("query", candidates, 3);

        assertThat(ranked).extracting(RetrievedFilingChunk::chunkId).containsExactly(4L, 2L, 1L);
        // Records are returned unchanged: the very instances given, similarity scores and content included.
        assertThat(ranked.get(0)).isSameAs(candidates.get(3));
        assertThat(ranked.get(1)).isSameAs(candidates.get(1));
        assertThat(ranked.get(2)).isSameAs(candidates.get(0));
        assertThat(ranked).allSatisfy(chunk -> assertThat(candidates).contains(chunk));
        assertThat(scorer.lastQuery).isEqualTo("query");
        assertThat(scorer.lastPassages).containsExactly("a", "b", "c", "d");
        assertThat(reranker.version()).isEqualTo("5d3e70fd0c9f");
        assertThat(reranker.rerank("query", candidates, 10)).extracting(RetrievedFilingChunk::chunkId).containsExactly(4L, 2L, 1L, 3L);
    }

    @Test
    void tiesKeepTheInputFusedOrder() {
        var candidates = List.of(chunk(9, "x"), chunk(3, "y"), chunk(7, "z"), chunk(1, "w"));
        var scorer = new TableScorer(Map.of("x", 1f, "y", 2f, "z", 2f, "w", 1f));
        var ranked = new CrossEncoderReranker(scorer, null).rerank("q", candidates, 4);
        // y and z tie at 2 (y first in input), x and w tie at 1 (x first in input); chunk ids play no part.
        assertThat(ranked).extracting(RetrievedFilingChunk::chunkId).containsExactly(3L, 7L, 9L, 1L);
    }

    @Test
    void identicalInputGivesAnIdenticalOrder() {
        var candidates = LongStream.rangeClosed(1, 20).mapToObj(id -> chunk(id, "p" + (id % 4))).toList();
        var scorer = new TableScorer(Map.of("p0", 1f, "p1", 4f, "p2", 4f, "p3", -1f));
        var reranker = new CrossEncoderReranker(scorer, null);
        assertThat(reranker.rerank("q", candidates, 10)).containsExactlyElementsOf(reranker.rerank("q", candidates, 10));
    }

    @Test
    void emptyCandidatesOrNonPositiveTopKDoNotCallTheScorer() {
        var scorer = new TableScorer(Map.of());
        var reranker = new CrossEncoderReranker(scorer, null);
        assertThat(reranker.rerank("q", List.of(), 5)).isEmpty();
        assertThat(reranker.rerank("q", List.of(chunk(1, "a")), 0)).isEmpty();
        assertThat(scorer.calls).hasValue(0);
    }

    @Test
    void aNullContentIsScoredAsEmptyText() {
        var scorer = new TableScorer(Map.of("", 5f, "a", 1f));
        var ranked = new CrossEncoderReranker(scorer, null).rerank("q", List.of(chunk(1, "a"), chunk(2, null)), 2);
        assertThat(ranked).extracting(RetrievedFilingChunk::chunkId).containsExactly(2L, 1L);
    }

    @Test
    void aScorerExceptionOrMalformedScoresPropagateAsRuntimeExceptions() {
        var candidates = List.of(chunk(1, "a"), chunk(2, "b"));
        PairScorer throwing = (query, passages) -> { throw new IllegalStateException("Cross-encoder inference failed: OrtException"); };
        assertThatThrownBy(() -> new CrossEncoderReranker(throwing, null).rerank("q", candidates, 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("inference failed");
        PairScorer shortScores = (query, passages) -> new float[] {1f};
        assertThatThrownBy(() -> new CrossEncoderReranker(shortScores, null).rerank("q", candidates, 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("1 scores for 2 candidates");
        PairScorer nullScores = (query, passages) -> null;
        assertThatThrownBy(() -> new CrossEncoderReranker(nullScores, null).rerank("q", candidates, 2))
                .isInstanceOf(IllegalStateException.class);
        PairScorer nanScores = (query, passages) -> new float[] {Float.NaN, 1f};
        assertThatThrownBy(() -> new CrossEncoderReranker(nanScores, null).rerank("q", candidates, 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("NaN");
    }

    // Through retrieval: Milestone 1's fallback handles a scorer failure or a slow scorer, and the reranker input is max(rerank-candidates, topK).

    @Test
    void aScorerFailureFallsBackToTheFusedOrderThroughRetrieval(CapturedOutput output) {
        var ranking = LongStream.rangeClosed(1, 6).mapToObj(id -> chunk(id, "text " + id)).toList();
        PairScorer throwing = (query, passages) -> { throw new IllegalStateException("native detail that must not be logged"); };
        var service = service(ranking, new CrossEncoderReranker(throwing, "abc"), 2000);
        try {
            var response = service.retrieve(request(3));
            assertThat(response.retrievalStrategy()).isEqualTo("FILTERED_VECTOR");
            assertThat(response.results()).containsExactlyElementsOf(ranking.subList(0, 3));
            assertThat(output.getOut() + output.getErr()).contains("reason=failure").contains("error=IllegalStateException")
                    .doesNotContain("native detail that must not be logged");
        } finally {
            service.shutdownRerankExecutor();
        }
    }

    @Test
    void aScorerSlowerThanASmallTimeoutFallsBackThroughRetrieval(CapturedOutput output) {
        var ranking = LongStream.rangeClosed(1, 6).mapToObj(id -> chunk(id, "text " + id)).toList();
        PairScorer slow = (query, passages) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new float[passages.size()];
        };
        var service = service(ranking, new CrossEncoderReranker(slow, null), 100);
        try {
            long started = System.nanoTime();
            var response = service.retrieve(request(3));
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(3_000);
            assertThat(response.retrievalStrategy()).isEqualTo("FILTERED_VECTOR");
            assertThat(response.results()).containsExactlyElementsOf(ranking.subList(0, 3));
            assertThat(output.getOut() + output.getErr()).contains("reason=timeout");
        } finally {
            service.shutdownRerankExecutor();
        }
    }

    @Test
    void theRerankerReceivesMaxOfRerankCandidatesAndTopKSoAResponseIsNeverCutShort() {
        var ranking = LongStream.rangeClosed(1, 12).mapToObj(id -> chunk(id, "text " + id)).toList();
        // Later chunks score higher, so the reranked order reverses the input.
        var scorer = new TableScorer(Map.ofEntries(LongStream.rangeClosed(1, 12)
                .mapToObj(id -> Map.entry("text " + id, (float) id)).toArray(Map.Entry[]::new)));
        var service = service(ranking, new CrossEncoderReranker(scorer, null), 2000);
        try {
            var above = service.retrieve(request(8)); // rerank-candidates is 5 here, topK 8
            assertThat(scorer.lastPassages).hasSize(8);
            assertThat(above.retrievalStrategy()).isEqualTo("FILTERED_VECTOR_RERANKED");
            assertThat(above.results()).hasSize(8).extracting(RetrievedFilingChunk::chunkId).containsExactly(8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L);

            var below = service.retrieve(request(3)); // topK 3 below rerank-candidates 5
            assertThat(scorer.lastPassages).hasSize(5);
            assertThat(below.results()).extracting(RetrievedFilingChunk::chunkId).containsExactly(5L, 4L, 3L);
        } finally {
            service.shutdownRerankExecutor();
        }
    }

    @Test
    void theCompletionLogLineReportsTheWindowsTheScorerRan(CapturedOutput output) {
        var candidates = List.of(chunk(1, "a"), chunk(2, "b"), chunk(3, "c"));
        // A scorer reporting one row per passage (the default of scoreWithWindows) logs windows equal to the candidate count.
        new CrossEncoderReranker(new TableScorer(Map.of("a", 1f, "b", 2f, "c", 3f)), null).rerank("q", candidates, 3);
        assertThat(output.getOut()).contains("Cross-encoder scoring completed: candidates=3, windows=3, topK=3, elapsedMs=");
        // A windowed scorer reports its row count and the scores it reduced to.
        PairScorer windowed = new PairScorer() {
            @Override
            public float[] score(String query, List<String> passages) {
                return scoreWithWindows(query, passages).scores();
            }

            @Override
            public Scored scoreWithWindows(String query, List<String> passages) {
                return new Scored(new float[] {0.5f, 4f, -1f}, 7);
            }

            @Override
            public String scoring() {
                return "max-window/overlap=64/maxWindows=4";
            }
        };
        var reranker = new CrossEncoderReranker(windowed, "5d3e70fd0c9f");
        assertThat(reranker.rerank("q", candidates, 2)).extracting(RetrievedFilingChunk::chunkId).containsExactly(2L, 1L);
        assertThat(output.getOut()).contains("Cross-encoder scoring completed: candidates=3, windows=7, topK=2, elapsedMs=");
        assertThat(reranker.scoring()).isEqualTo("max-window/overlap=64/maxWindows=4");
        assertThat(new CrossEncoderReranker(new TableScorer(Map.of()), null).scoring()).isNull();
        // A null result from a scorer is a RuntimeException like a null score array, so retrieval falls back.
        PairScorer nullResult = new PairScorer() {
            @Override
            public float[] score(String query, List<String> passages) {
                return null;
            }

            @Override
            public Scored scoreWithWindows(String query, List<String> passages) {
                return null;
            }
        };
        assertThatThrownBy(() -> new CrossEncoderReranker(nullResult, null).rerank("q", candidates, 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no scores");
    }

    @Test
    void theRerankerNameUsesTheUserClassAndTheVersionComesFromTheReranker() {
        var ranking = List.of(chunk(1, "a"));
        ProxyFactory factory = new ProxyFactory(new CrossEncoderReranker(new TableScorer(Map.of()), "5d3e70fd0c9f"));
        factory.setProxyTargetClass(true);
        FilingReranker proxied = (FilingReranker) factory.getProxy();
        assertThat(proxied.getClass().getName()).contains("$$");
        var service = service(ranking, proxied, 2000);
        assertThat(service.rerankerName()).contains("CrossEncoderReranker");
        assertThat(service.rerankerVersion()).contains("5d3e70fd0c9f");
        assertThat(service.rerankerScoring()).as("a scorer without a scoring description").isEmpty();
        assertThat(service(ranking, new ReversingFilingReranker(), 2000).rerankerVersion()).isEmpty();
        assertThat(service(ranking, new ReversingFilingReranker(), 2000).rerankerScoring()).isEmpty();
        PairScorer describing = new PairScorer() {
            @Override
            public float[] score(String query, List<String> passages) {
                return new float[passages.size()];
            }

            @Override
            public String scoring() {
                return "head";
            }
        };
        assertThat(service(ranking, new CrossEncoderReranker(describing, null), 2000).rerankerScoring()).contains("head");
    }

    private static FilingRetrievalService service(List<RetrievedFilingChunk> ranking, FilingReranker reranker, long timeoutMs) {
        FilingEmbeddingService embeddings = mock(FilingEmbeddingService.class);
        FilingRetrievalRepository repository = mock(FilingRetrievalRepository.class);
        when(embeddings.embed(anyString())).thenReturn(new float[1536]);
        when(repository.findSimilarChunks(any(), any(), anyInt())).thenReturn(ranking);
        FilingRetrievalProperties properties = new FilingRetrievalProperties();
        properties.setHybridEnabled(false);
        properties.setRerankCandidates(5);
        properties.setRerankTimeoutMs(timeoutMs);
        return new FilingRetrievalService(embeddings, repository, properties, Optional.of(reranker));
    }

    private static RetrievalRequest request(int topK) {
        return new RetrievalRequest("NVDA", "What drove data center revenue?", null, null, null, null, topK, true, null, true);
    }

    private static RetrievedFilingChunk chunk(long id, String content) {
        return new RetrievedFilingChunk(id, 100L + id, "NVDA", "0001045810", "acc-" + id, "10-K", LocalDate.parse("2025-02-26"),
                null, "ITEM_7", "MD&A", (int) id, content, "https://example.invalid/" + id, 0.9 - id / 100.0);
    }
}

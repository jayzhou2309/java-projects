package project.stockrecommendationengine.rag.retrieval;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import project.stockrecommendationengine.rag.dto.RetrievalRequest;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.ingestion.FilingEmbeddingService;
import project.stockrecommendationengine.rag.repository.FilingRetrievalRepository;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Opt-in check of the real cross-encoder (the model files are not in git): loads model.onnx and tokenizer.json from the
 * default paths under models/ (override with -Drag.rerank.model-dir=<dir>), verifies the model's SHA-256 against the
 * application.yaml value passed as -Drag.rerank.model-sha256 when given, and checks ranking, determinism, latency for 20
 * candidates, and the timeout fallback. No database, no Spring context, no network. Run with -Drag.rerank.live=true.
 */
@EnabledIfSystemProperty(named = "rag.rerank.live", matches = "true")
class CrossEncoderRerankerLiveTests {
    private static final String EXPECTED_MODEL_SHA256 = "5d3e70fd0c9ff14b9b5169a51e957b7a9c74897afd0a35ce4bd318150c1d4d4a";
    private static CrossEncoderModelFiles files;
    private static OnnxCrossEncoderScorer scorer;
    private static CrossEncoderReranker reranker;

    @BeforeAll
    static void load() {
        Path dir = Path.of(System.getProperty("rag.rerank.model-dir", "models/cross-encoder-ms-marco-MiniLM-L-6-v2"));
        CrossEncoderProperties properties = new CrossEncoderProperties();
        properties.setEnabled(true);
        properties.setModelPath(dir.resolve("model.onnx").toString());
        properties.setTokenizerPath(dir.resolve("tokenizer.json").toString());
        properties.setModelSha256(System.getProperty("rag.rerank.model-sha256", EXPECTED_MODEL_SHA256));
        files = CrossEncoderModelFiles.verify(properties);
        long started = System.nanoTime();
        scorer = new OnnxCrossEncoderScorer(files.modelPath(), files.tokenizerPath(), properties.getMaxLength(), properties.getBatchSize());
        System.out.println("CROSS_ENCODER load elapsedMs=" + (System.nanoTime() - started) / 1_000_000 + " version=" + files.version());
        System.out.println("CROSS_ENCODER metadata " + scorer.describe());
        reranker = new CrossEncoderReranker(scorer, files.version());
    }

    @AfterAll
    static void close() {
        if (scorer != null) scorer.close();
    }

    @Test
    void theAnsweringPassageRanksFirstForEachHandwrittenQuery() {
        record Case(String query, String answer, String distractor1, String distractor2) { }
        List<Case> cases = List.of(
                new Case("What was total revenue for fiscal year 2025?",
                        "Total revenue for fiscal year 2025 was $130.5 billion, up 114% from a year ago, driven by Data Center.",
                        "The Company's headquarters are located in Santa Clara, California, and it employs approximately 36,000 people.",
                        "We may be subject to litigation relating to intellectual property, which could be costly and divert management attention."),
                new Case("Who is the chief executive officer of the company?",
                        "Jen-Hsun Huang co-founded the company in 1993 and has served as President and Chief Executive Officer since its inception.",
                        "Gross margin decreased to 75.0% primarily due to a higher mix of new products within Data Center.",
                        "Our products are manufactured by third-party foundries located in Taiwan and South Korea."),
                new Case("How much did the company spend on share repurchases?",
                        "During fiscal year 2025, we repurchased 310 million shares of our common stock for $33.7 billion.",
                        "Inventory provisions totaled $3.3 billion, reflecting excess inventory and purchase obligations.",
                        "Export controls restrict sales of certain data center products to customers in China."));
        for (Case c : cases) {
            // The answer is placed last so a model ignoring content would not rank it first by input order.
            List<RetrievedFilingChunk> candidates = List.of(chunk(1, c.distractor1()), chunk(2, c.distractor2()), chunk(3, c.answer()));
            float[] scores = scorer.score(c.query(), candidates.stream().map(RetrievedFilingChunk::content).toList());
            List<RetrievedFilingChunk> ranked = reranker.rerank(c.query(), candidates, 3);
            System.out.println("CROSS_ENCODER pairs query=\"" + c.query() + "\" scores(distractor1, distractor2, answer)="
                    + java.util.Arrays.toString(scores) + " order=" + ranked.stream().map(RetrievedFilingChunk::chunkId).toList());
            assertThat(ranked.get(0).chunkId()).as(c.query()).isEqualTo(3L);
            assertThat(ranked).containsExactlyInAnyOrderElementsOf(candidates);
        }
    }

    @Test
    void twoCallsOnIdenticalInputGiveIdenticalScoresAndOrder() {
        List<RetrievedFilingChunk> candidates = twentyLongCandidates();
        List<String> passages = candidates.stream().map(RetrievedFilingChunk::content).toList();
        String query = "What drove the increase in data center revenue?";
        float[] first = scorer.score(query, passages);
        float[] second = scorer.score(query, passages);
        assertThat(second).containsExactly(first);
        assertThat(reranker.rerank(query, candidates, 10)).containsExactlyElementsOf(reranker.rerank(query, candidates, 10));
        System.out.println("CROSS_ENCODER determinism identical=" + java.util.Arrays.equals(first, second) + " scores=" + java.util.Arrays.toString(first));
    }

    @Test
    void twentyPassagesOfAbout2000CharactersAreScoredAndTheLatencyIsPrinted() {
        List<RetrievedFilingChunk> candidates = twentyLongCandidates();
        String query = "What drove the increase in data center revenue?";
        reranker.rerank(query, candidates, 5); // warm-up
        List<Long> elapsed = new ArrayList<>();
        for (int run = 0; run < 3; run++) {
            long started = System.nanoTime();
            List<RetrievedFilingChunk> ranked = reranker.rerank(query, candidates, 5);
            elapsed.add((System.nanoTime() - started) / 1_000_000);
            assertThat(ranked).hasSize(5);
        }
        System.out.println("CROSS_ENCODER latency candidates=20 charsEach=" + candidates.get(0).content().length()
                + " elapsedMs(after warm-up)=" + elapsed);
    }

    @Test
    void anArtificiallySmallTimeoutFallsBackToTheFusedOrder() {
        FilingEmbeddingService embeddings = mock(FilingEmbeddingService.class);
        FilingRetrievalRepository repository = mock(FilingRetrievalRepository.class);
        when(embeddings.embed(anyString())).thenReturn(new float[1536]);
        List<RetrievedFilingChunk> ranking = twentyLongCandidates();
        when(repository.findSimilarChunks(any(), any(), anyInt())).thenReturn(ranking);
        FilingRetrievalProperties properties = new FilingRetrievalProperties();
        properties.setHybridEnabled(false);
        properties.setRerankTimeoutMs(1); // below the validated minimum of 100 on purpose: the real model cannot finish in 1 ms
        FilingRetrievalService service = new FilingRetrievalService(embeddings, repository, properties, Optional.of(reranker));
        try {
            var request = new RetrievalRequest("NVDA", "What drove the increase in data center revenue?", null, null, null, null, 5, true, false, true);
            long started = System.nanoTime();
            var timedOut = service.retrieve(request);
            System.out.println("CROSS_ENCODER timeout timeoutMs=1 strategy=" + timedOut.retrievalStrategy()
                    + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000);
            assertThat(timedOut.retrievalStrategy()).isEqualTo("FILTERED_VECTOR");
            assertThat(timedOut.results()).containsExactlyElementsOf(ranking.subList(0, 5));
            assertThat(timedOut.candidatesRetrieved()).isEqualTo(20);

            properties.setRerankTimeoutMs(30_000);
            var reranked = service.retrieve(request);
            System.out.println("CROSS_ENCODER timeout timeoutMs=30000 strategy=" + reranked.retrievalStrategy()
                    + " order=" + reranked.results().stream().map(RetrievedFilingChunk::chunkId).toList());
            assertThat(reranked.retrievalStrategy()).isEqualTo("FILTERED_VECTOR_RERANKED");
        } finally {
            service.shutdownRerankExecutor();
        }
    }

    private static List<RetrievedFilingChunk> twentyLongCandidates() {
        String[] topics = {
                "Data Center revenue increased due to strong demand for accelerated computing platforms used for large language models.",
                "Gaming revenue reflected higher sales of GeForce RTX GPUs to partners ahead of the holiday season.",
                "Professional Visualization revenue grew on adoption of RTX workstation GPUs for design and simulation.",
                "Automotive revenue rose on sales of self-driving platforms and AI cockpit solutions to automakers.",
                "Operating expenses increased because of compensation and benefits related to employee growth."};
        return IntStream.rangeClosed(1, 20).mapToObj(id -> {
            StringBuilder text = new StringBuilder("Passage " + id + ". ");
            while (text.length() < 2000) text.append(topics[(id + text.length() / 120) % topics.length]).append(' ');
            // A distinct filing per candidate so retrieval's near-duplicate filter keeps all 20.
            return chunk(id, 100L + id, text.substring(0, 2000));
        }).toList();
    }

    private static RetrievedFilingChunk chunk(long id, String content) {
        return chunk(id, 7L, content);
    }

    private static RetrievedFilingChunk chunk(long id, long filingId, String content) {
        return new RetrievedFilingChunk(id, filingId, "NVDA", "0001045810", "0001045810-25-000023", "10-K",
                LocalDate.parse("2025-02-26"), LocalDate.parse("2025-01-26"), "ITEM_7", "MD&A", (int) id, content,
                "https://example.invalid/filing/7", 0.9 - id / 100.0);
    }

}

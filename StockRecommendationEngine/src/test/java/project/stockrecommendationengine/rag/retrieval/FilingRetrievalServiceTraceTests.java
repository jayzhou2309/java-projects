package project.stockrecommendationengine.rag.retrieval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import project.stockrecommendationengine.rag.dto.RetrievalRequest;
import project.stockrecommendationengine.rag.dto.RetrievalResponse;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.ingestion.FilingEmbeddingService;
import project.stockrecommendationengine.rag.repository.FilingRetrievalRepository;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.FusedCandidate;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.Outcome;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.RerankedCandidate;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The traced retrieval path (plan 2026-09-13-evaluation-evidence, Milestone 1, C1 and C2), against a scripted repository and
 * scripted rerankers: the traced and untraced paths return identical responses for rerank off, on, timeout, reranker exception,
 * and invalid evidence; the reranker is invoked exactly once per retrieval, through the same method on both paths, so a reranker whose
 * two methods disagree still returns identical results traced and untraced; a fallback records its reason and no scores; and the
 * trace's fused positions, leg ranks, rerank order, and returned ids agree with what retrieval actually did.
 */
class FilingRetrievalServiceTraceTests {
    /** Holds a figure ("64,377"), so the figure leg runs at the default figure weight. */
    private static final String QUERY = "net sales 64,377";
    private static final String SHARED = "Net sales disclosure repeated in two stored chunks of the same filing and section. ".repeat(4);

    private FilingEmbeddingService embeddings;
    private FilingRetrievalRepository repository;
    private FilingRetrievalProperties properties;
    private final List<FilingRetrievalService> services = new ArrayList<>();

    // Scripted legs. Vector: 1, 2, 3, 2 (repeat), 4, 5, 6, 7. Keyword: 3, 8, 1, 9. Figure: 8, 10. Chunk 9 repeats chunk 1's text
    // in the same filing and section, so diversification removes it.
    private final Map<Long, RetrievedFilingChunk> chunks = new HashMap<>();
    private List<RetrievedFilingChunk> vectorLeg;
    private List<RetrievedFilingChunk> keywordLeg;
    private List<RetrievedFilingChunk> figureLeg;

    @BeforeEach
    void setUp() {
        embeddings = mock(FilingEmbeddingService.class);
        repository = mock(FilingRetrievalRepository.class);
        properties = new FilingRetrievalProperties(); // hybrid on, weights 1.0 / 0.5 / 1.0, k 60
        properties.setRerankCandidates(5);
        when(embeddings.embed(anyString())).thenReturn(new float[1536]);
        chunks.put(1L, chunk(1, 0.90, SHARED));
        chunks.put(2L, chunk(2, 0.85, "c2"));
        chunks.put(3L, chunk(3, 0.80, "c3"));
        chunks.put(4L, chunk(4, 0.79, "c4"));
        chunks.put(5L, chunk(5, 0.78, "c5"));
        chunks.put(6L, chunk(6, 0.77, "c6"));
        chunks.put(7L, chunk(7, 0.76, "c7"));
        chunks.put(8L, chunk(8, 0.60, "c8"));
        chunks.put(9L, chunk(9, 0.50, SHARED));
        chunks.put(10L, chunk(10, 0.70, "c10"));
        vectorLeg = ids(1, 2, 3, 2, 4, 5, 6, 7);
        keywordLeg = ids(3, 8, 1, 9);
        figureLeg = ids(8, 10);
        when(repository.findSimilarChunks(any(), any(), anyInt())).thenAnswer(inv -> vectorLeg);
        when(repository.findKeywordChunks(any(), any(), any(), anyInt())).thenAnswer(inv -> keywordLeg);
        when(repository.findFigureChunks(any(), any(), any(), anyInt())).thenAnswer(inv -> figureLeg);
    }

    @AfterEach
    void shutDown() {
        services.forEach(FilingRetrievalService::shutdownRerankExecutor);
    }

    /** Fused order by weighted RRF: 8, 1, 3, then 2 and 10 tie on score (2 wins on similarity), 4, 5, 6, 7, 9; 9 is diversified away. */
    private static final List<Long> FUSED = List.of(8L, 1L, 3L, 2L, 10L, 4L, 5L, 6L, 7L);

    // C1: identical responses and one reranker invocation per traced retrieval.

    @Test
    void rerankOffTracedAndUntracedAreIdenticalAndTheRerankerIsNeverCalled() {
        var reranker = new CountingReranker(new CrossEncoderReranker(new WindowTableScorer(Map.of()), "v"));
        var service = service(reranker);
        for (Boolean rerank : new Boolean[] {false, null}) {
            var request = request(3, rerank);
            RetrievalResponse untraced = service.retrieve(request);
            var traced = service.retrieveTraced(request);
            assertThat(traced.response()).isEqualTo(untraced);
            assertThat(untraced.retrievalStrategy()).isEqualTo("HYBRID_RRF");
            assertThat(traced.trace().rerank()).isEqualTo(new RetrievalTrace.Rerank(Outcome.OFF, null, null, null, null));
            assertFusedTrace(traced.trace(), untraced);
        }
        assertThat(service.retrieve(request(3, false)).results()).extracting(RetrievedFilingChunk::chunkId).containsExactly(8L, 1L, 3L);
        assertThat(reranker.rerankCalls.get() + reranker.scoredCalls.get()).isZero();
    }

    @Test
    void rerankOnTracedAndUntracedAreIdenticalWithOneScoredCallAndTheScoresThatProducedTheOrder() {
        // Input 8, 1, 3, 2, 10 scored 0.5, 2.0, -1, 4.0, 2.0: order 2, 1, 10 (ties keep input order), 8, 3.
        var scorer = new WindowTableScorer(Map.of("c8", 0.5f, SHARED, 2.0f, "c3", -1f, "c2", 4.0f, "c10", 2.0f));
        var reranker = new CountingReranker(new CrossEncoderReranker(scorer, "v"));
        var service = service(reranker);
        var request = request(3, true);

        RetrievalResponse untraced = service.retrieve(request);
        assertThat(reranker.scoredCalls.get()).as("the untraced path calls the same scored method").isEqualTo(1);
        assertThat(scorer.calls.get()).isEqualTo(1);

        var traced = service.retrieveTraced(request);
        assertThat(traced.response()).isEqualTo(untraced);
        assertThat(untraced.retrievalStrategy()).isEqualTo("HYBRID_RRF_RERANKED");
        assertThat(untraced.results()).extracting(RetrievedFilingChunk::chunkId).containsExactly(2L, 1L, 10L);
        assertThat(reranker.scoredCalls.get()).as("one scored call per retrieval").isEqualTo(2);
        assertThat(reranker.rerankCalls.get()).as("no separate rerank call on either path").isZero();
        assertThat(scorer.calls.get()).as("one scoring call per retrieval, never a second for the trace").isEqualTo(2);
        assertThat(reranker.lastInput).extracting(RetrievedFilingChunk::chunkId).containsExactly(8L, 1L, 3L, 2L, 10L);

        RetrievalTrace.Rerank rerank = traced.trace().rerank();
        assertThat(rerank.outcome()).isEqualTo(Outcome.RERANKED);
        assertThat(rerank.fallbackReason()).isNull();
        assertThat(rerank.scoresNotRecorded()).isNull();
        assertThat(rerank.inputCount()).isEqualTo(5);
        assertThat(rerank.candidates()).extracting(RerankedCandidate::chunkId).containsExactly(2L, 1L, 10L, 8L, 3L);
        assertThat(rerank.candidates()).extracting(RerankedCandidate::rerankedPosition).containsExactly(1, 2, 3, 4, 5);
        assertThat(rerank.candidates()).extracting(RerankedCandidate::fusedPosition).containsExactly(4, 2, 5, 1, 3);
        assertThat(rerank.candidates()).extracting(RerankedCandidate::score).containsExactly(4.0f, 2.0f, 2.0f, 0.5f, -1f);
        // Chunk 1's text is long, so the scripted scorer gives it three windows whose maximum is its score; the others one window.
        assertThat(rerank.candidates().get(1).windowScores()).containsExactly(-1.0f, 2.0f, 1.0f);
        assertThat(rerank.candidates()).extracting(RerankedCandidate::windowCount).containsExactly(1, 3, 1, 1, 1);
        assertThat(rerank.candidates()).allSatisfy(candidate -> assertThat(candidate.windowScores().stream().max(Float::compare).orElseThrow())
                .isEqualTo(candidate.score()));
        assertFusedTrace(traced.trace(), untraced);
        assertRerankTrace(traced.trace(), untraced, reranker.lastInput);
    }

    @Test
    void aTimeoutFallsBackIdenticallyAndTheTraceRecordsTheReasonAndNoScores() {
        properties.setRerankTimeoutMs(100);
        var calls = new AtomicInteger();
        PairScorer slow = (query, passages) -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new float[passages.size()];
        };
        var service = service(new CrossEncoderReranker(slow, "v"));
        var request = request(3, true);
        RetrievalResponse untraced = service.retrieve(request);
        var traced = service.retrieveTraced(request);
        assertThat(traced.response()).isEqualTo(untraced);
        assertThat(untraced.retrievalStrategy()).isEqualTo("HYBRID_RRF");
        assertThat(untraced.results()).extracting(RetrievedFilingChunk::chunkId).containsExactly(8L, 1L, 3L);
        assertThat(traced.trace().rerank()).isEqualTo(new RetrievalTrace.Rerank(Outcome.FALLBACK, "timeout", 5, null, null));
        assertThat(calls.get()).as("one scoring call per retrieval").isEqualTo(2);
        assertFusedTrace(traced.trace(), untraced);
    }

    @Test
    void aRerankerExceptionFallsBackIdenticallyAndTheTraceRecordsTheReasonAndNoScores() {
        var calls = new AtomicInteger();
        PairScorer throwing = (query, passages) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("inference failed");
        };
        var reranker = new CountingReranker(new CrossEncoderReranker(throwing, "v"));
        var service = service(reranker);
        var request = request(3, true);
        RetrievalResponse untraced = service.retrieve(request);
        var traced = service.retrieveTraced(request);
        assertThat(traced.response()).isEqualTo(untraced);
        assertThat(untraced.retrievalStrategy()).isEqualTo("HYBRID_RRF");
        assertThat(traced.trace().rerank()).isEqualTo(new RetrievalTrace.Rerank(Outcome.FALLBACK, "failure", 5, null, null));
        assertThat(reranker.scoredCalls.get()).isEqualTo(2);
        assertThat(reranker.rerankCalls.get()).isZero();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void invalidEvidenceFallsBackIdenticallyAndTheTraceRecordsTheReasonAndNoScores() {
        var invented = chunk(99, 0.99, "invented");
        for (List<RetrievedFilingChunk> returned : List.of(List.of(invented), ids(8, 1, 3, 2))) {
            // Both methods return the same invalid evidence (an invented chunk, then more than topK), with scores for the input.
            var reranker = new ScriptedReranker(input -> returned, input -> scoredInOrder(input.size()));
            var service = service(reranker);
            var request = request(3, true);
            RetrievalResponse untraced = service.retrieve(request);
            var traced = service.retrieveTraced(request);
            assertThat(traced.response()).isEqualTo(untraced);
            assertThat(untraced.retrievalStrategy()).isEqualTo("HYBRID_RRF");
            assertThat(untraced.results()).extracting(RetrievedFilingChunk::chunkId).containsExactly(8L, 1L, 3L);
            assertThat(traced.trace().rerank()).isEqualTo(new RetrievalTrace.Rerank(Outcome.FALLBACK, "invalidEvidence", 5, null, null));
            assertThat(reranker.scoredCalls.get()).isEqualTo(2);
            assertThat(reranker.rerankCalls.get()).isZero();
        }
    }

    @Test
    void aRerankerWithoutScoresIsCalledOnceThroughRerankAndOnlyTheReturnedChunksCarryPositions() {
        var reranker = new ReversingFilingReranker();
        var service = service(reranker);
        var request = request(3, true);
        RetrievalResponse untraced = service.retrieve(request);
        var traced = service.retrieveTraced(request);
        assertThat(traced.response()).isEqualTo(untraced);
        assertThat(untraced.results()).extracting(RetrievedFilingChunk::chunkId).containsExactly(10L, 2L, 3L);
        assertThat(reranker.calls).isEqualTo(2);
        RetrievalTrace.Rerank rerank = traced.trace().rerank();
        assertThat(rerank.outcome()).isEqualTo(Outcome.RERANKED);
        assertThat(rerank.scoresNotRecorded()).isEqualTo("reranker reports no scores");
        assertThat(rerank.candidates()).extracting(RerankedCandidate::chunkId).containsExactly(10L, 2L, 3L, 8L, 1L);
        assertThat(rerank.candidates()).extracting(RerankedCandidate::rerankedPosition).containsExactly(1, 2, 3, null, null);
        assertThat(rerank.candidates()).extracting(RerankedCandidate::score).containsOnlyNulls();
        assertThat(rerank.candidates()).extracting(RerankedCandidate::windowScores).containsOnlyNulls();
        assertRerankTrace(traced.trace(), untraced, reranker.lastCandidates);
    }

    @Test
    void scoresInconsistentWithTheReturnedOrderAreNotRecordedAndTheResponseIsUnchanged() {
        // The scored order puts input 0 (chunk 8) first while the results start with chunk 2.
        var reranker = new ScriptedReranker(input -> List.of(input.get(3), input.get(1)), input -> scoredInOrder(input.size()));
        var service = service(reranker);
        var request = request(3, true);
        RetrievalResponse untraced = service.retrieve(request);
        var traced = service.retrieveTraced(request);
        assertThat(traced.response()).isEqualTo(untraced);
        assertThat(untraced.retrievalStrategy()).isEqualTo("HYBRID_RRF_RERANKED");
        RetrievalTrace.Rerank rerank = traced.trace().rerank();
        assertThat(rerank.scoresNotRecorded()).isEqualTo("scores inconsistent with the returned order");
        assertThat(rerank.candidates()).extracting(RerankedCandidate::chunkId).containsExactly(2L, 1L, 8L, 3L, 10L);
        assertThat(rerank.candidates()).extracting(RerankedCandidate::rerankedPosition).containsExactly(1, 2, null, null, null);
        assertThat(rerank.candidates()).extracting(RerankedCandidate::score).containsOnlyNulls();
        assertThat(reranker.scoredCalls.get()).isEqualTo(2);
    }

    // Remediation 1, finding 4: the reranker contract cannot be broken silently. There is no capability flag to set without
    // implementing the scored method (FilingReranker declares none), and both paths take their results from the one scored call.

    @Test
    void aRerankerCannotClaimScoresWithoutProducingThemBecauseThereIsNoFlagAndTheDefaultDelegatesToRerank() {
        // The former hazard: a reranker saying it reports scores while keeping the throwing default made traced retrieval fall back
        // with "failure" while untraced retrieval reranked. The flag no longer exists, and the default scored method is rerank.
        assertThat(Arrays.stream(FilingReranker.class.getMethods()).map(java.lang.reflect.Method::getName))
                .containsExactlyInAnyOrder("rerank", "rerankScored", "version", "scoring");
        var reranker = new ReversingFilingReranker(); // implements rerank only
        var service = service(reranker);
        var request = request(3, true);
        RetrievalResponse untraced = service.retrieve(request);
        assertThat(reranker.calls).as("one rerank call for the untraced retrieval").isEqualTo(1);
        var traced = service.retrieveTraced(request);
        assertThat(reranker.calls).as("one rerank call for the traced retrieval").isEqualTo(2);
        assertThat(traced.response()).isEqualTo(untraced);
        assertThat(untraced.retrievalStrategy()).isEqualTo("HYBRID_RRF_RERANKED");
        assertThat(traced.trace().rerank().outcome()).isEqualTo(Outcome.RERANKED);
        assertThat(traced.trace().rerank().scoresNotRecorded()).isEqualTo("reranker reports no scores");

        // A reranker whose scored method is overridden to refuse (the old default) now fails both paths alike, never only the trace.
        var refusing = new FilingReranker() {
            final AtomicInteger rerankCalls = new AtomicInteger();
            final AtomicInteger scoredCalls = new AtomicInteger();

            @Override
            public List<RetrievedFilingChunk> rerank(String query, List<RetrievedFilingChunk> candidates, int topK) {
                rerankCalls.incrementAndGet();
                return List.of(candidates.get(3));
            }

            @Override
            public ScoredReranking rerankScored(String query, List<RetrievedFilingChunk> candidates, int topK) {
                scoredCalls.incrementAndGet();
                throw new UnsupportedOperationException("does not report reranking scores");
            }
        };
        var refusingService = service(refusing);
        RetrievalResponse refusedUntraced = refusingService.retrieve(request);
        var refusedTraced = refusingService.retrieveTraced(request);
        assertThat(refusedTraced.response()).isEqualTo(refusedUntraced);
        assertThat(refusedUntraced.retrievalStrategy()).isEqualTo("HYBRID_RRF");
        assertThat(refusedTraced.trace().rerank()).isEqualTo(new RetrievalTrace.Rerank(Outcome.FALLBACK, "failure", 5, null, null));
        assertThat(refusing.scoredCalls.get()).isEqualTo(2);
        assertThat(refusing.rerankCalls.get()).isZero();
    }

    @Test
    void aRerankerWhoseScoredResultsDifferFromRerankStillReturnsIdenticalResultsTracedAndUntraced() {
        // rerank would return chunks 10, 2 (input 4, 3); the scored method returns chunks 1, 3 (input 1, 2) with a consistent order.
        var disagreeing = new FilingReranker() {
            final AtomicInteger rerankCalls = new AtomicInteger();
            final AtomicInteger scoredCalls = new AtomicInteger();

            @Override
            public List<RetrievedFilingChunk> rerank(String query, List<RetrievedFilingChunk> candidates, int topK) {
                rerankCalls.incrementAndGet();
                return List.of(candidates.get(4), candidates.get(3));
            }

            @Override
            public ScoredReranking rerankScored(String query, List<RetrievedFilingChunk> candidates, int topK) {
                scoredCalls.incrementAndGet();
                List<ScoredCandidate> order = List.of(new ScoredCandidate(1, 3f, new float[] {3f}), new ScoredCandidate(2, 2f, new float[] {2f}),
                        new ScoredCandidate(0, 1f, new float[] {1f}), new ScoredCandidate(3, 0f, new float[] {0f}),
                        new ScoredCandidate(4, -1f, new float[] {-1f}));
                return new ScoredReranking(List.of(candidates.get(1), candidates.get(2)), order);
            }
        };
        var service = service(disagreeing);
        var request = request(2, true);
        RetrievalResponse untraced = service.retrieve(request);
        var traced = service.retrieveTraced(request);
        assertThat(traced.response()).isEqualTo(untraced);
        assertThat(untraced.retrievalStrategy()).isEqualTo("HYBRID_RRF_RERANKED");
        assertThat(untraced.results()).extracting(RetrievedFilingChunk::chunkId).containsExactly(1L, 3L);
        assertThat(disagreeing.scoredCalls.get()).as("exactly one reranker call per retrieval").isEqualTo(2);
        assertThat(disagreeing.rerankCalls.get()).as("rerank is never called by retrieval, so it cannot make the paths differ").isZero();
        RetrievalTrace.Rerank rerank = traced.trace().rerank();
        assertThat(rerank.scoresNotRecorded()).isNull();
        assertThat(rerank.candidates()).extracting(RerankedCandidate::chunkId).containsExactly(1L, 3L, 8L, 2L, 10L);
        assertThat(rerank.candidates()).extracting(RerankedCandidate::score).containsExactly(3f, 2f, 1f, 0f, -1f);
        assertRerankTrace(traced.trace(), untraced, List.of(chunks.get(8L), chunks.get(1L), chunks.get(3L), chunks.get(2L), chunks.get(10L)));
    }

    @Test
    void noFusedCandidatesRecordsANoCandidatesFallbackWithoutCallingTheReranker() {
        vectorLeg = List.of();
        keywordLeg = List.of();
        figureLeg = List.of();
        var reranker = new CountingReranker(new CrossEncoderReranker(new WindowTableScorer(Map.of()), "v"));
        var service = service(reranker);
        var request = request(3, true);
        RetrievalResponse untraced = service.retrieve(request);
        var traced = service.retrieveTraced(request);
        assertThat(traced.response()).isEqualTo(untraced);
        assertThat(untraced.results()).isEmpty();
        assertThat(traced.trace().rerank()).isEqualTo(new RetrievalTrace.Rerank(Outcome.FALLBACK, "noCandidates", 0, null, null));
        assertThat(traced.trace().fused()).isEmpty();
        assertThat(traced.trace().returnedChunkIds()).isEmpty();
        assertThat(traced.trace().vectorCandidates()).isZero();
        assertThat(traced.trace().keywordCandidates()).isZero();
        assertThat(traced.trace().figureCandidates()).isZero();
        assertThat(reranker.rerankCalls.get() + reranker.scoredCalls.get()).isZero();
    }

    // C2: positions, leg ranks, and the rerank order against the scripted legs.

    @Test
    void legRanksMatchTheScriptedLegsAndLegsThatDidNotRunAreNull() {
        var service = service(null);
        var traced = service.retrieveTraced(request(3, null));
        RetrievalTrace trace = traced.trace();
        assertThat(trace.vectorCandidates()).isEqualTo(8);
        assertThat(trace.keywordCandidates()).isEqualTo(4);
        assertThat(trace.figureCandidates()).isEqualTo(2);
        // Hand-computed from the scripted legs; vector ranks count the repeated chunk 2 once, at its first position.
        assertThat(trace.fused()).containsExactly(
                new FusedCandidate(8, 1, null, 2, 1),
                new FusedCandidate(1, 2, 1, 3, null),
                new FusedCandidate(3, 3, 3, 1, null),
                new FusedCandidate(2, 4, 2, null, null),
                new FusedCandidate(10, 5, null, null, 2),
                new FusedCandidate(4, 6, 4, null, null),
                new FusedCandidate(5, 7, 5, null, null),
                new FusedCandidate(6, 8, 6, null, null),
                new FusedCandidate(7, 9, 7, null, null));
        assertThat(traced.response().candidatesRetrieved()).as("chunk 9 was fused, then diversified away").isEqualTo(10);

        // No figure in the query: the figure leg does not run, so its size and ranks are null.
        var yearOnly = service.retrieveTraced(new RetrievalRequest("AAPL", "net sales in fiscal 2025", null, null, null, null, 3, null, null, null));
        assertThat(yearOnly.trace().figureCandidates()).isNull();
        assertThat(yearOnly.trace().fused()).extracting(FusedCandidate::figureRank).containsOnlyNulls();
        assertThat(yearOnly.trace().fused()).extracting(FusedCandidate::chunkId).startsWith(1L, 3L);

        // Hybrid off: vector only, keyword and figure null; the fused list is the diversified vector list.
        var vectorOnly = service.retrieveTraced(new RetrievalRequest("AAPL", QUERY, null, null, null, null, 3, null, false, null));
        assertThat(vectorOnly.response()).isEqualTo(service.retrieve(new RetrievalRequest("AAPL", QUERY, null, null, null, null, 3, null, false, null)));
        assertThat(vectorOnly.trace().keywordCandidates()).isNull();
        assertThat(vectorOnly.trace().figureCandidates()).isNull();
        assertThat(vectorOnly.trace().fused()).containsExactly(
                new FusedCandidate(1, 1, 1, null, null), new FusedCandidate(2, 2, 2, null, null), new FusedCandidate(3, 3, 3, null, null),
                new FusedCandidate(4, 4, 4, null, null), new FusedCandidate(5, 5, 5, null, null), new FusedCandidate(6, 6, 6, null, null),
                new FusedCandidate(7, 7, 7, null, null));

        // A failing keyword search: FILTERED_VECTOR, keyword and figure legs null (the figure leg is not attempted).
        when(repository.findKeywordChunks(any(), any(), any(), anyInt())).thenThrow(new IllegalStateException("index"));
        var keywordFailed = service.retrieveTraced(request(3, null));
        assertThat(keywordFailed.response().retrievalStrategy()).isEqualTo("FILTERED_VECTOR");
        assertThat(keywordFailed.trace().keywordCandidates()).isNull();
        assertThat(keywordFailed.trace().figureCandidates()).isNull();
        assertThat(keywordFailed.trace().fused()).extracting(FusedCandidate::chunkId).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
    }

    @Test
    void theRerankOrderRestrictedToTopKIsTheResponseForManyScoreTablesAndTopKs() {
        // Scores drawn from a small set so ties are frequent; every topK from 1 to 9 (the input grows past rerank-candidates).
        var random = new java.util.Random(20260913);
        List<String> contents = List.of("c8", SHARED, "c3", "c2", "c10", "c4", "c5", "c6", "c7");
        for (int round = 0; round < 40; round++) {
            Map<String, Float> table = new HashMap<>();
            for (String content : contents) table.put(content, (float) (random.nextInt(5) - 2));
            var reranker = new CountingReranker(new CrossEncoderReranker(new WindowTableScorer(table), "v"));
            var service = service(reranker);
            int topK = 1 + random.nextInt(9);
            var request = request(topK, true);
            RetrievalResponse untraced = service.retrieve(request);
            var traced = service.retrieveTraced(request);
            assertThat(traced.response()).as("round %d topK %d", round, topK).isEqualTo(untraced);
            assertThat(reranker.scoredCalls.get()).isEqualTo(2);
            assertThat(reranker.rerankCalls.get()).isZero();
            assertThat(traced.trace().rerank().inputCount()).isEqualTo(Math.max(5, topK));
            assertFusedTrace(traced.trace(), untraced);
            assertRerankTrace(traced.trace(), untraced, reranker.lastInput);
        }
    }

    @Test
    void theRetrievalResponseCarriesNoTraceField() {
        // The trace never enters the response that /api/rag/retrieve and the recommendation tools receive.
        assertThat(Arrays.stream(RetrievalResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("ticker", "query", "retrievalStrategy", "latestFilingsOnly", "topK", "candidatesRetrieved", "results");
        assertThat(service(null).retrieveTraced(request(3, null)).response()).isNotNull();
    }

    /** C2 invariants of the fused list: positions 1..n without gaps, in the diversified order, and the returned ids. */
    private void assertFusedTrace(RetrievalTrace trace, RetrievalResponse response) {
        assertThat(trace.fused()).extracting(FusedCandidate::fusedPosition).containsExactlyElementsOf(IntStream.rangeClosed(1, trace.fused().size()).boxed().toList());
        assertThat(trace.fused()).extracting(FusedCandidate::chunkId).containsExactlyElementsOf(FUSED);
        assertThat(trace.returnedChunkIds()).containsExactlyElementsOf(response.results().stream().map(RetrievedFilingChunk::chunkId).toList());
    }

    /**
     * C2 invariants of the rerank record: every rerank input chunk once; fused positions match the order the reranker received and
     * the fused list; reranked positions 1..n with scores non-increasing; the first topK equal the response.
     */
    private static void assertRerankTrace(RetrievalTrace trace, RetrievalResponse response, List<RetrievedFilingChunk> received) {
        RetrievalTrace.Rerank rerank = trace.rerank();
        List<RerankedCandidate> candidates = rerank.candidates();
        assertThat(candidates).hasSize(received.size());
        assertThat(new HashSet<>(candidates.stream().map(RerankedCandidate::chunkId).toList())).hasSize(received.size())
                .containsExactlyInAnyOrderElementsOf(received.stream().map(RetrievedFilingChunk::chunkId).toList());
        for (RerankedCandidate candidate : candidates) {
            assertThat(received.get(candidate.fusedPosition() - 1).chunkId()).isEqualTo(candidate.chunkId());
            assertThat(trace.fused().get(candidate.fusedPosition() - 1).chunkId()).isEqualTo(candidate.chunkId());
        }
        List<Long> returned = response.results().stream().map(RetrievedFilingChunk::chunkId).toList();
        assertThat(candidates.subList(0, returned.size())).extracting(RerankedCandidate::chunkId).containsExactlyElementsOf(returned);
        if (rerank.scoresNotRecorded() == null) {
            assertThat(candidates).extracting(RerankedCandidate::rerankedPosition)
                    .containsExactlyElementsOf(IntStream.rangeClosed(1, candidates.size()).boxed().toList());
            for (int i = 1; i < candidates.size(); i++) {
                assertThat(candidates.get(i).score()).isLessThanOrEqualTo(candidates.get(i - 1).score());
                if (candidates.get(i).score().equals(candidates.get(i - 1).score())) {
                    assertThat(candidates.get(i).fusedPosition()).as("ties keep fused order").isGreaterThan(candidates.get(i - 1).fusedPosition());
                }
            }
        }
    }

    private FilingRetrievalService service(FilingReranker reranker) {
        var service = new FilingRetrievalService(embeddings, repository, properties, Optional.ofNullable(reranker));
        services.add(service);
        return service;
    }

    private static RetrievalRequest request(int topK, Boolean rerank) {
        return new RetrievalRequest("AAPL", QUERY, null, null, null, null, topK, null, null, rerank);
    }

    private List<RetrievedFilingChunk> ids(long... chunkIds) {
        return Arrays.stream(chunkIds).mapToObj(chunks::get).toList();
    }

    private static RetrievedFilingChunk chunk(long id, double similarity, String content) {
        return new RetrievedFilingChunk(id, 1L, "AAPL", "0000320193", "0000320193-25-000079", "10-K", LocalDate.parse("2025-10-31"), null,
                "ITEM_7", "MD&A", (int) id, content, "https://example.invalid/" + id, similarity);
    }

    /** Every input scored 0 in input order, one window each. */
    private static FilingReranker.ScoredReranking scoredInOrder(int size) {
        return new FilingReranker.ScoredReranking(null, IntStream.range(0, size)
                .mapToObj(index -> new FilingReranker.ScoredCandidate(index, 0f, new float[] {0f})).toList());
    }

    /**
     * Scores each passage from a table keyed on its text (0 when absent) and reports windows: three for text longer than 100
     * characters, with row scores score - 3, score, score - 1, one otherwise. Counts scoring calls.
     */
    static final class WindowTableScorer implements PairScorer {
        final Map<String, Float> table;
        final AtomicInteger calls = new AtomicInteger();

        WindowTableScorer(Map<String, Float> table) {
            this.table = table;
        }

        @Override
        public float[] score(String query, List<String> passages) {
            return scoreWithWindows(query, passages).scores();
        }

        @Override
        public Scored scoreWithWindows(String query, List<String> passages) {
            calls.incrementAndGet();
            float[] scores = new float[passages.size()];
            float[][] rows = new float[passages.size()][];
            int windows = 0;
            for (int i = 0; i < scores.length; i++) {
                float score = table.getOrDefault(passages.get(i), 0f);
                rows[i] = passages.get(i).length() > 100 ? new float[] {score - 3, score, score - 1} : new float[] {score};
                scores[i] = CrossEncoderPairAssembler.maxOverWindows(rows[i]);
                windows += rows[i].length;
            }
            return new Scored(scores, windows, rows);
        }
    }

    /** Delegates to a reranker and counts each method's calls, keeping the last input. */
    static final class CountingReranker implements FilingReranker {
        final FilingReranker delegate;
        final AtomicInteger rerankCalls = new AtomicInteger();
        final AtomicInteger scoredCalls = new AtomicInteger();
        volatile List<RetrievedFilingChunk> lastInput;

        CountingReranker(FilingReranker delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<RetrievedFilingChunk> rerank(String query, List<RetrievedFilingChunk> candidates, int topK) {
            rerankCalls.incrementAndGet();
            lastInput = candidates;
            return delegate.rerank(query, candidates, topK);
        }

        @Override
        public ScoredReranking rerankScored(String query, List<RetrievedFilingChunk> candidates, int topK) {
            scoredCalls.incrementAndGet();
            lastInput = candidates;
            return delegate.rerankScored(query, candidates, topK);
        }
    }

    /** Returns scripted results from both methods, with a scripted scored order on the scored one. */
    static final class ScriptedReranker implements FilingReranker {
        final java.util.function.Function<List<RetrievedFilingChunk>, List<RetrievedFilingChunk>> results;
        final java.util.function.Function<List<RetrievedFilingChunk>, ScoredReranking> order;
        final AtomicInteger rerankCalls = new AtomicInteger();
        final AtomicInteger scoredCalls = new AtomicInteger();

        ScriptedReranker(java.util.function.Function<List<RetrievedFilingChunk>, List<RetrievedFilingChunk>> results,
                java.util.function.Function<List<RetrievedFilingChunk>, ScoredReranking> order) {
            this.results = results;
            this.order = order;
        }

        @Override
        public List<RetrievedFilingChunk> rerank(String query, List<RetrievedFilingChunk> candidates, int topK) {
            rerankCalls.incrementAndGet();
            return results.apply(candidates);
        }

        @Override
        public ScoredReranking rerankScored(String query, List<RetrievedFilingChunk> candidates, int topK) {
            scoredCalls.incrementAndGet();
            return new ScoredReranking(results.apply(candidates), order.apply(candidates).order());
        }
    }
}

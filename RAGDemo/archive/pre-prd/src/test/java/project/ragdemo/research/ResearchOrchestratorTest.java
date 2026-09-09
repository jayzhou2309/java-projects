package project.ragdemo.research;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import project.ragdemo.monitoring.RunMonitor;
import project.ragdemo.rag.RagRetrievalService;
import project.ragdemo.recommendation.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResearchOrchestratorTest {
    private final QueryPlanningAgent planner = mock(QueryPlanningAgent.class);
    private final RagRetrievalService retrieval = mock(RagRetrievalService.class);
    private final MarketResearchAgent market = mock(MarketResearchAgent.class);
    private final RecommendationService recommendations = mock(RecommendationService.class);
    private final RunMonitor monitor = new RunMonitor();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final RuntimeConfig.Limits limits = new RuntimeConfig.Limits(Duration.ofSeconds(2), Duration.ofSeconds(5), 2);
    private final ResearchOrchestrator orchestrator = new ResearchOrchestrator(planner,
            new AgentRunner(executor, limits, monitor), new SecResearchAgent(retrieval), market, recommendations, monitor, limits);
    private final ResearchRequest request = new ResearchRequest("Analyze Apple filings", List.of(), null, null);

    @AfterEach
    void shutdown() { executor.shutdownNow(); }

    @Test
    void runsPlannedStagesWithScopedEvidenceAndOneTrace() {
        when(planner.plan(request)).thenReturn(plan(false, null));
        Document apple = document("AAPL");
        when(retrieval.retrieve(request.query(), "AAPL", null, null)).thenReturn(List.of(apple));
        var response = new RecommendationResponse("Review risks", 0.4, "N/A", "N/A", "Supplier exposure", List.of("https://example.test/AAPL"));
        when(recommendations.generateFromEvidence(request.query(), List.of(EvidenceItem.fromDocument(apple)))).thenReturn(response);
        var result = orchestrator.research(request);
        assertEquals(response, result.response());
        assertEquals(4, result.traces().size());
        assertEquals(1, monitor.snapshot().size());
        assertEquals(result.runId(), monitor.snapshot().get(0).id());
        verify(recommendations).generateFromEvidence(request.query(), List.of(EvidenceItem.fromDocument(apple)));
        verify(recommendations, never()).generateRecommendation(anyString());
    }

    @Test
    void unrelatedAndMarketDependentPlansSkipProviders() {
        when(planner.plan(request)).thenReturn(new QueryPlan(QueryPlan.Intent.UNRELATED_TOPIC, List.of(), null, null, false, null));
        assertEquals(RecommendationStatus.UNRELATED_TOPIC, orchestrator.research(request).response().status());
        when(planner.plan(request)).thenReturn(plan(true, null));
        when(retrieval.retrieve(request.query(), "AAPL", null, null)).thenReturn(List.of(document("AAPL")));
        when(market.research(any())).thenReturn(new MarketResearchAgent.MarketResearchResult(List.of(), List.of("Unavailable")));
        var result = orchestrator.research(request);
        assertEquals(RecommendationStatus.INSUFFICIENT_EVIDENCE, result.response().status());
        assertEquals(1, result.traces().stream().filter(trace -> trace.status() == AgentResult.StageStatus.SKIPPED).count());
        verifyNoInteractions(recommendations);
    }

    @Test
    void emptyScopedRetrievalDoesNotGenerate() {
        when(planner.plan(request)).thenReturn(plan(false, LocalDate.of(2025, 1, 1)));
        when(retrieval.retrieve(eq(request.query()), eq("AAPL"), any(), any())).thenReturn(List.of());
        assertEquals(RecommendationStatus.INSUFFICIENT_EVIDENCE, orchestrator.research(request).response().status());
        verifyNoInteractions(recommendations);
    }

    @Test
    void planningFailureReturnsTerminalStructuredFailure() {
        when(planner.plan(request)).thenThrow(new IllegalArgumentException("invalid plan"));
        var result = orchestrator.research(request);
        assertEquals(RecommendationStatus.FAILED, result.response().status());
        assertEquals(AgentResult.StageStatus.FAILED, result.traces().get(0).status());
        verifyNoInteractions(retrieval, recommendations);
        var events = monitor.snapshot().get(0).events();
        assertEquals("FAILED", events.get(events.size() - 1).status());
    }

    @Test
    void invalidRecommendationIsFailedWithoutCorrectionLoop() {
        when(planner.plan(request)).thenReturn(plan(false, null));
        when(retrieval.retrieve(eq(request.query()), eq("AAPL"), any(), any())).thenReturn(List.of(document("AAPL")));
        when(recommendations.generateFromEvidence(anyString(), anyList())).thenReturn(
                RecommendationResponse.outcome(RecommendationStatus.FAILED, "Invalid generated response"));
        var result = orchestrator.research(request);
        assertEquals(RecommendationStatus.FAILED, result.response().status());
        assertEquals(AgentResult.StageStatus.FAILED, result.traces().get(3).status());
        verify(recommendations, times(1)).generateFromEvidence(anyString(), anyList());
    }

    private QueryPlan plan(boolean market, LocalDate from) {
        return new QueryPlan(QueryPlan.Intent.SEC_RESEARCH, List.of("AAPL"), from, null, market, null);
    }

    private Document document(String symbol) {
        return new Document("Synthetic supplier risk", Map.of("symbol", symbol, "sourceUrl", "https://example.test/" + symbol));
    }
}

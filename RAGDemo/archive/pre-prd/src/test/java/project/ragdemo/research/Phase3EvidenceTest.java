package project.ragdemo.research;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import project.ragdemo.rag.RagRetrievalService;
import project.ragdemo.market.*;
import project.ragdemo.monitoring.RunMonitor;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Phase3EvidenceTest {
    @Test
    void vectorSearchReceivesCompanyTypeAndDateFilters() {
        VectorStore vectors = mock(VectorStore.class);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        new RagRetrievalService(vectors).retrieve("risk", "AAPL", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        var captured = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectors).similaritySearch(captured.capture());
        String filter = captured.getValue().getFilterExpression().toString();
        for (String value : List.of("symbol", "AAPL", "filingType", "10-K", "filedDate", "2025-01-01", "2025-12-31"))
            assertTrue(filter.contains(value), filter);
        assertEquals(5, captured.getValue().getTopK());
    }

    @Test
    void independentStagesStartBeforeEitherIsAwaited() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var monitor = new RunMonitor();
            var limits = new RuntimeConfig.Limits(Duration.ofSeconds(2), Duration.ofSeconds(3), 1);
            var runner = new AgentRunner(executor, limits, monitor);
            var context = new RunContext(monitor.start(), limits.runTimeout());
            CountDownLatch started = new CountDownLatch(2);
            Callable<String> action = () -> {
                started.countDown();
                if (!started.await(1, TimeUnit.SECONDS)) throw new IllegalStateException("Not concurrent");
                return "evidence";
            };
            var sec = runner.start("SEC research", action, context);
            var market = runner.start("Market research", action, context);
            assertEquals(AgentResult.StageStatus.COMPLETED, runner.await(sec).status());
            assertEquals(AgentResult.StageStatus.COMPLETED, runner.await(market).status());
            assertEquals(2, context.traces().size());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void marketEvidencePreservesProvenanceAndOmitsStaleQuotes() {
        MarketDataService service = mock(MarketDataService.class);
        Instant now = Instant.parse("2026-01-01T12:00:00Z");
        when(service.getMarketData("AAPL")).thenReturn(new MarketData("AAPL", "123", new BigDecimal("201.25"), now, now, "RpB", MarketData.Status.AVAILABLE));
        when(service.getMarketData("MSFT")).thenReturn(new MarketData("MSFT", "456", null, null, now, "D", MarketData.Status.STALE));
        var result = new MarketResearchAgent(service).research(new QueryPlan(QueryPlan.Intent.SEC_RESEARCH,
                List.of("AAPL", "MSFT"), null, null, true, null));
        assertEquals(1, result.evidence().size());
        assertEquals("ibkr://snapshot/123", result.evidence().get(0).sourceUrl());
        assertTrue(result.evidence().get(0).text().contains(now.toString()));
        assertEquals(1, result.warnings().size());
    }
}

package project.stockrecommendationengine.outcome;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import project.stockrecommendationengine.broker.BrokerException;
import project.stockrecommendationengine.broker.BrokerReadService;
import project.stockrecommendationengine.broker.BrokerData.DailyBar;
import project.stockrecommendationengine.broker.BrokerData.PriceHistory;
import project.stockrecommendationengine.quant.PriceBarRepository;
import project.stockrecommendationengine.recommendation.RecommendationRecord;
import project.stockrecommendationengine.recommendation.RecommendationRepository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutcomeEvaluationServiceTests {
    private static final Instant NOW = Instant.parse("2026-09-12T02:00:00Z");
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);
    private final RecommendationRepository recommendations = mock(RecommendationRepository.class);
    private final OutcomeRepository outcomes = mock(OutcomeRepository.class);
    private final PriceBarRepository bars = mock(PriceBarRepository.class);
    private final BrokerReadService broker = mock(BrokerReadService.class);
    private final OutcomeProperties properties = new OutcomeProperties();
    private OutcomeEvaluationService service;

    @BeforeEach void setup() {
        properties.setHorizons(List.of(2, 5));
        service = new OutcomeEvaluationService(recommendations, outcomes, bars, broker, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        when(bars.findLatest(anyLong(), anyInt())).thenReturn(Optional.empty());
    }

    @Test void writesReachableHorizonsLeavesTheRestPendingAndRefreshesStaleBarsOnce() {
        var rec = record("run-1", 265598L, "BULLISH");
        when(recommendations.findPendingEvaluation(2, 500)).thenReturn(List.of(rec, record("run-2", 265598L, "NEUTRAL")));
        when(outcomes.evaluatedHorizons("run-1")).thenReturn(List.of());
        when(outcomes.evaluatedHorizons("run-2")).thenReturn(List.of(2));
        var series = history(265598L, 3);
        when(broker.getDailyBars(265598L, 250)).thenReturn(series);
        when(bars.findLatest(265598L, 400)).thenReturn(Optional.empty(), Optional.of(series));
        when(broker.getDailyBars(756733L, 250)).thenThrow(new BrokerException(BrokerException.Code.UNAVAILABLE));
        var run = service.evaluateAll();
        assertThat(run.runsConsidered()).isEqualTo(2);
        assertThat(run.outcomesWritten()).as("run-1 horizon 2 only; horizon 5 lacks bars; run-2 horizon 2 already done").isEqualTo(1);
        assertThat(run.pendingHorizons()).isEqualTo(2);
        assertThat(run.barRefreshFailures()).containsExactly("756733:UNAVAILABLE");
        assertThat(run.failedRuns()).isEmpty();
        verify(broker, times(1)).getDailyBars(265598L, 250);
        verify(bars).upsert(series);
        var captor = org.mockito.ArgumentCaptor.forClass(OutcomeRecord.class);
        verify(outcomes).upsert(captor.capture());
        assertThat(captor.getValue().runId()).isEqualTo("run-1");
        assertThat(captor.getValue().horizonDays()).isEqualTo(2);
        assertThat(captor.getValue().benchmarkReturnPct()).isNull();
    }

    @Test void freshStoredBarsAreUsedWithoutTheBrokerAndWithoutABrokerAtAll() {
        var rec = record("run-1", 265598L, "NEUTRAL");
        when(recommendations.findPendingEvaluation(2, 500)).thenReturn(List.of(rec));
        when(outcomes.evaluatedHorizons("run-1")).thenReturn(List.of());
        var fresh = new PriceHistory(265598L, "AAPL", "USD", NOW.minus(Duration.ofHours(1)), history(265598L, 6).bars());
        when(bars.findLatest(265598L, 400)).thenReturn(Optional.of(fresh));
        var run = service.evaluateAll();
        assertThat(run.outcomesWritten()).isEqualTo(2);
        verify(broker, never()).getDailyBars(eq(265598L), anyInt());
        var offline = new OutcomeEvaluationService(recommendations, outcomes, bars, null, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(offline.evaluateAll().outcomesWritten()).isEqualTo(2);
    }

    @Test void oneFailingRunDoesNotStopTheOthersAndSingleRunEvaluationReturnsStoredOutcomes() {
        var bad = record("run-bad", 1L, "BULLISH");
        var good = record("run-good", 265598L, "NEUTRAL");
        when(recommendations.findPendingEvaluation(2, 500)).thenReturn(List.of(bad, good));
        when(outcomes.evaluatedHorizons("run-bad")).thenThrow(new IllegalStateException("db"));
        when(outcomes.evaluatedHorizons("run-good")).thenReturn(List.of());
        when(bars.findLatest(265598L, 400)).thenReturn(Optional.of(new PriceHistory(265598L, "AAPL", "USD", NOW, history(265598L, 6).bars())));
        var run = service.evaluateAll();
        assertThat(run.failedRuns()).containsExactly("run-bad");
        assertThat(run.outcomesWritten()).isEqualTo(2);
        when(recommendations.findByRunId("run-good")).thenReturn(Optional.of(good));
        when(outcomes.findByRunId("run-good")).thenReturn(List.of());
        assertThat(service.evaluate("run-good")).isEmpty();
        verify(outcomes, times(4)).upsert(any());
        assertThatThrownBy(() -> service.evaluate("missing")).isInstanceOf(java.util.NoSuchElementException.class);
    }

    private static RecommendationRecord record(String runId, Long conid, String assessment) {
        return new RecommendationRecord(runId, "AAPL", conid, NOW, NOW, "q", "COMPLETE", assessment,
                assessment.equals("BULLISH") ? new BigDecimal("110") : null, assessment.equals("BULLISH") ? new BigDecimal("95") : null,
                null, new BigDecimal("100"), AS_OF, "DELAYED", List.of(), List.of(), 0, 0, "p", "m", null, "v", "{}");
    }
    private static PriceHistory history(long conid, int barsAfter) {
        var list = new java.util.ArrayList<DailyBar>();
        list.add(new DailyBar(AS_OF, new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"), new BigDecimal("100"), BigDecimal.ONE));
        for (int i = 1; i <= barsAfter; i++) {
            BigDecimal close = new BigDecimal(100 + i);
            list.add(new DailyBar(AS_OF.plusDays(i), close, close.add(BigDecimal.ONE), close.subtract(BigDecimal.ONE), close, BigDecimal.ONE));
        }
        return new PriceHistory(conid, "AAPL", "USD", NOW.minus(Duration.ofDays(1)), list);
    }
}

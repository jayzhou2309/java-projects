package project.stockrecommendationengine.outcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.stockrecommendationengine.recommendation.RecommendationRecord;
import project.stockrecommendationengine.recommendation.RecommendationRepository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrackRecordServiceTests {
    private final RecommendationRepository recommendations = mock(RecommendationRepository.class);
    private final OutcomeRepository outcomes = mock(OutcomeRepository.class);
    private final TrackRecordService service = new TrackRecordService(recommendations, outcomes, new OutcomeProperties());

    @Test void groupsRunsByAssessmentAndScoresOnlyTheReferenceHorizon() {
        when(recommendations.findByTicker("AAPL", 10)).thenReturn(List.of(
                rec("r1", "BULLISH"), rec("r2", "BULLISH"), rec("r3", "NEUTRAL"), rec("r4", "BULLISH")));
        when(outcomes.findByRunId("r1")).thenReturn(List.of(outcome("r1", 5, "0.010000", true), outcome("r1", 20, "0.080000", true)));
        when(outcomes.findByRunId("r2")).thenReturn(List.of(outcome("r2", 20, "-0.040000", false)));
        when(outcomes.findByRunId("r3")).thenReturn(List.of(outcome("r3", 20, "0.020000", null)));
        when(outcomes.findByRunId("r4")).thenReturn(List.of(outcome("r4", 5, "0.005000", true))); // 20-day not reached yet
        var record = service.trackRecord("aapl", 10);
        assertThat(record.ticker()).isEqualTo("AAPL");
        assertThat(record.runsConsidered()).isEqualTo(4);
        assertThat(record.runs()).extracting(TrackRecord.PriorRun::runId).containsExactly("r1", "r2", "r3", "r4");
        assertThat(record.runs().get(0).outcomes()).extracting(TrackRecord.HorizonOutcome::horizonDays).containsExactly(5, 20);
        var bullish = record.stats().stream().filter(s -> s.assessment().equals("BULLISH")).findFirst().orElseThrow();
        assertThat(bullish.runs()).isEqualTo(3);
        assertThat(bullish.scored()).isEqualTo(2);
        assertThat(bullish.directionCorrect()).isEqualTo(1);
        assertThat(bullish.averageReturnPct()).isEqualByComparingTo("0.020000");
        assertThat(bullish.referenceHorizonDays()).isEqualTo(20);
        var neutral = record.stats().stream().filter(s -> s.assessment().equals("NEUTRAL")).findFirst().orElseThrow();
        assertThat(neutral.directionCorrect()).isZero();
        assertThat(neutral.averageReturnPct()).isEqualByComparingTo("0.020000");
        assertThat(record.caveat()).contains("not proof");
    }

    @Test void noRunsYieldsAnEmptyRecordWithNoStatistics() {
        when(recommendations.findByTicker("NEW", 10)).thenReturn(List.of());
        var record = service.trackRecord("NEW", 10);
        assertThat(record.runsConsidered()).isZero();
        assertThat(record.stats()).isEmpty();
        var unscored = new TrackRecordService(recommendations, outcomes, new OutcomeProperties());
        when(recommendations.findByTicker("X", 10)).thenReturn(List.of(rec("r9", "BEARISH")));
        when(outcomes.findByRunId("r9")).thenReturn(List.of());
        var stats = unscored.trackRecord("X", 10).stats().get(0);
        assertThat(stats.scored()).isZero();
        assertThat(stats.averageReturnPct()).isNull();
    }

    private static RecommendationRecord rec(String runId, String assessment) {
        return new RecommendationRecord(runId, "AAPL", 265598L, Instant.now(), Instant.now(), "q", "COMPLETE", assessment,
                null, null, new BigDecimal("0.5"), new BigDecimal("100"), LocalDate.of(2026, 6, 1), "DELAYED", List.of(), List.of(),
                0, 0, "p", "m", null, "v", "{}");
    }
    private static OutcomeRecord outcome(String runId, int horizon, String ret, Boolean correct) {
        return new OutcomeRecord(runId, horizon, Instant.now(), LocalDate.of(2026, 6, 1), new BigDecimal("100"), LocalDate.of(2026, 7, 1),
                new BigDecimal("105"), new BigDecimal(ret), null, null, null, correct, correct == null ? "NO_LEVELS" : "NONE", null, null,
                BigDecimal.ONE, BigDecimal.ONE.negate(), horizon);
    }
}

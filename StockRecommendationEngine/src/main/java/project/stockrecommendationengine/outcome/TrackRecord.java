package project.stockrecommendationengine.outcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The agent's own prior recommendations for a ticker with their realized outcomes. Supplied to the manager as
 * untrusted evidence; sample sizes travel with every statistic so small samples cannot masquerade as proof.
 */
public record TrackRecord(String ticker, int runsConsidered, List<PriorRun> runs, List<AssessmentStats> stats, String caveat) {
    public record PriorRun(String runId, Instant requestedAt, String status, String assessment, BigDecimal confidence,
            BigDecimal lastClose, LocalDate barsAsOf, BigDecimal takeProfit, BigDecimal stopLoss, List<HorizonOutcome> outcomes) { }
    public record HorizonOutcome(int horizonDays, BigDecimal returnPct, BigDecimal excessReturnPct, Boolean directionCorrect,
            String firstTouch) { }
    /** Per assessment: how many runs, how many have a scored reference horizon, and what they realized. */
    public record AssessmentStats(String assessment, int runs, int scored, int directionCorrect, BigDecimal averageReturnPct,
            int referenceHorizonDays) { }
}

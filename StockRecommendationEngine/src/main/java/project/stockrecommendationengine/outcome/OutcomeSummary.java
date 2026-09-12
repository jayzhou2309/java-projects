package project.stockrecommendationengine.outcome;

import java.math.BigDecimal;

/** Aggregate over stored outcomes for one assessment and horizon. Rates are fractions; null when nothing scored. */
public record OutcomeSummary(String assessment, int horizonDays, long outcomes, BigDecimal averageReturnPct,
        BigDecimal averageExcessReturnPct, BigDecimal directionHitRate, BigDecimal takeProfitFirstRate,
        BigDecimal stopLossFirstRate) { }

package project.stockrecommendationengine.outcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One recommendation scored over one horizon. Returns are fractions (0.05 = 5%). directionCorrect is null for
 * assessments without a direction. firstTouch reports which stored level the price path reached first.
 */
public record OutcomeRecord(String runId, int horizonDays, Instant evaluatedAt, LocalDate asOf, BigDecimal entryPrice,
        LocalDate exitDate, BigDecimal exitPrice, BigDecimal returnPct, Long benchmarkConid, BigDecimal benchmarkReturnPct,
        BigDecimal excessReturnPct, Boolean directionCorrect, String firstTouch, LocalDate touchDate, Integer daysToTouch,
        BigDecimal maxFavorablePct, BigDecimal maxAdversePct, int barsUsed) { }

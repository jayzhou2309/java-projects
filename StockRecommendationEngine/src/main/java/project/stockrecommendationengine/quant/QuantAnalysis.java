package project.stockrecommendationengine.quant;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Deterministic statistics computed from stored daily bars. No field is model-generated.
 * Levels are null when the series is stale; every omission is named in limitations.
 */
public record QuantAnalysis(long conid, String symbol, String currency, String source, boolean fromCache,
        Instant observedAt, LocalDate asOf, int barCount, BigDecimal lastClose,
        BigDecimal atr, int atrPeriod, BigDecimal realizedVolatility, int volatilityPeriod,
        BigDecimal shortSma, int shortSmaPeriod, BigDecimal longSma, int longSmaPeriod,
        BigDecimal momentum, String trend, Levels longLevels, Levels shortLevels, String levelMethod,
        List<String> limitations) {
    /** Take-profit and stop-loss prices for one direction, derived from ATR multiples of the last close. */
    public record Levels(BigDecimal takeProfit, BigDecimal stopLoss) { }
}

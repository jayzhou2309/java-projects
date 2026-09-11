package project.stockrecommendationengine.quant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import project.stockrecommendationengine.broker.BrokerData.DailyBar;

/** Pure functions over ascending daily bars. Each returns null when the series is too short. */
final class QuantIndicators {
    static final int TRADING_DAYS_PER_YEAR = 252;
    private QuantIndicators() { }

    /** Wilder's average true range: simple mean of the first {@code period} true ranges, then smoothed. */
    static Double atr(List<DailyBar> bars, int period) {
        if (period < 1 || bars.size() < period + 1) return null;
        double atr = 0;
        for (int i = 1; i <= period; i++) atr += trueRange(bars, i);
        atr /= period;
        for (int i = period + 1; i < bars.size(); i++) atr = (atr * (period - 1) + trueRange(bars, i)) / period;
        return atr;
    }
    private static double trueRange(List<DailyBar> bars, int index) {
        double high = bars.get(index).high().doubleValue(), low = bars.get(index).low().doubleValue();
        double previousClose = bars.get(index - 1).close().doubleValue();
        return Math.max(high - low, Math.max(Math.abs(high - previousClose), Math.abs(low - previousClose)));
    }

    /** Annualized sample standard deviation of the last {@code period} daily log returns. */
    static Double realizedVolatility(List<DailyBar> bars, int period) {
        if (period < 2 || bars.size() < period + 1) return null;
        double[] returns = new double[period];
        double mean = 0;
        for (int i = 0; i < period; i++) {
            int index = bars.size() - period + i;
            returns[i] = Math.log(bars.get(index).close().doubleValue() / bars.get(index - 1).close().doubleValue());
            mean += returns[i];
        }
        mean /= period;
        double variance = 0;
        for (double value : returns) variance += (value - mean) * (value - mean);
        variance /= period - 1;
        return Math.sqrt(variance) * Math.sqrt(TRADING_DAYS_PER_YEAR);
    }

    /** Simple moving average of the last {@code period} closes. */
    static Double sma(List<DailyBar> bars, int period) {
        if (period < 1 || bars.size() < period) return null;
        double sum = 0;
        for (int i = bars.size() - period; i < bars.size(); i++) sum += bars.get(i).close().doubleValue();
        return sum / period;
    }

    /** Fractional change of the last close versus the close {@code period} bars earlier. */
    static Double momentum(List<DailyBar> bars, int period) {
        if (period < 1 || bars.size() < period + 1) return null;
        double previous = bars.get(bars.size() - 1 - period).close().doubleValue();
        return bars.get(bars.size() - 1).close().doubleValue() / previous - 1;
    }

    static BigDecimal round(Double value, int scale) {
        if (value == null || !Double.isFinite(value)) return null;
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }
}

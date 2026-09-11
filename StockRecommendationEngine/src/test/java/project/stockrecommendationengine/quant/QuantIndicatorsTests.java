package project.stockrecommendationengine.quant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.stockrecommendationengine.broker.BrokerData.DailyBar;
import static org.assertj.core.api.Assertions.*;

class QuantIndicatorsTests {
    @Test void atrUsesWilderSmoothingOverTrueRanges() {
        // Closes 100..104 with a fixed 2-point range; gap on the third bar widens the true range.
        var bars = List.of(bar(1, 99, 101, 100), bar(2, 100, 102, 101), bar(3, 104, 106, 105),
                bar(4, 104, 106, 105), bar(5, 104, 106, 105));
        // TR: bar2 = max(2, |102-100|, |100-100|) = 2; bar3 = max(2, |106-101|, |104-101|) = 5; bar4 = 2; bar5 = 2.
        // period 2: seed = (2 + 5) / 2 = 3.5; then (3.5 + 2) / 2 = 2.75; then (2.75 + 2) / 2 = 2.375.
        assertThat(QuantIndicators.atr(bars, 2)).isCloseTo(2.375, within(1e-9));
        assertThat(QuantIndicators.atr(bars, 5)).isNull();
    }
    @Test void realizedVolatilityIsAnnualizedSampleDeviationOfLogReturns() {
        var bars = List.of(bar(1, 100), bar(2, 110), bar(3, 100), bar(4, 110));
        double up = Math.log(1.1), down = Math.log(100.0 / 110);
        double mean = (up + down + up) / 3;
        double variance = (Math.pow(up - mean, 2) + Math.pow(down - mean, 2) + Math.pow(up - mean, 2)) / 2;
        assertThat(QuantIndicators.realizedVolatility(bars, 3)).isCloseTo(Math.sqrt(variance) * Math.sqrt(252), within(1e-9));
        assertThat(QuantIndicators.realizedVolatility(bars, 4)).isNull();
    }
    @Test void constantSeriesHasZeroVolatilityAndFlatIndicators() {
        var bars = new ArrayList<DailyBar>();
        for (int i = 1; i <= 30; i++) bars.add(bar(i, 50));
        assertThat(QuantIndicators.realizedVolatility(bars, 20)).isZero();
        assertThat(QuantIndicators.atr(bars, 14)).isCloseTo(2.0, within(1e-9)); // constant 2-point range
        assertThat(QuantIndicators.sma(bars, 20)).isEqualTo(50);
        assertThat(QuantIndicators.momentum(bars, 20)).isZero();
    }
    @Test void smaAndMomentumUseOnlyTheTrailingWindow() {
        var bars = List.of(bar(1, 10), bar(2, 20), bar(3, 30), bar(4, 40));
        assertThat(QuantIndicators.sma(bars, 2)).isEqualTo(35);
        assertThat(QuantIndicators.sma(bars, 5)).isNull();
        assertThat(QuantIndicators.momentum(bars, 3)).isCloseTo(3.0, within(1e-12));
        assertThat(QuantIndicators.momentum(bars, 4)).isNull();
    }
    @Test void roundingRejectsNonFiniteValues() {
        assertThat(QuantIndicators.round(1.23456, 4)).isEqualByComparingTo("1.2346");
        assertThat(QuantIndicators.round(Double.NaN, 4)).isNull();
        assertThat(QuantIndicators.round(null, 4)).isNull();
    }

    static DailyBar bar(int day, double close) { return bar(day, close - 1, close + 1, close); }
    static DailyBar bar(int day, double low, double high, double close) {
        return new DailyBar(LocalDate.of(2026, 1, 1).plusDays(day), BigDecimal.valueOf(close), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), BigDecimal.valueOf(1000));
    }
}

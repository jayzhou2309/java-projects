package project.stockrecommendationengine.quant;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import project.stockrecommendationengine.broker.BrokerException;
import project.stockrecommendationengine.broker.BrokerReadService;
import project.stockrecommendationengine.broker.BrokerData.DailyBar;
import project.stockrecommendationengine.broker.BrokerData.PriceHistory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuantAnalysisServiceTests {
    private static final Instant NOW = Instant.parse("2026-09-11T06:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final BrokerReadService broker = mock(BrokerReadService.class);
    private final PriceBarRepository repository = mock(PriceBarRepository.class);
    private final QuantProperties properties = new QuantProperties();
    private QuantAnalysisService service;

    @BeforeEach void setup() {
        properties.setEnabled(true);
        properties.setMinBars(60);
        var beans = new StaticListableBeanFactory();
        beans.addBean("broker", broker);
        service = new QuantAnalysisService(beans.getBeanProvider(BrokerReadService.class), repository, properties, clock);
        when(repository.findLatest(anyLong(), anyInt())).thenReturn(Optional.empty());
    }

    @Test void requiresTheBrokerIntegration() {
        assertThatThrownBy(() -> new QuantAnalysisService(new StaticListableBeanFactory().getBeanProvider(BrokerReadService.class),
                repository, properties, clock)).isInstanceOf(IllegalStateException.class);
    }

    @Test void fetchesStoresAndComputesLevelsFromBrokerBars() {
        when(broker.getDailyBars(265598, 120)).thenReturn(history(trending(80, LocalDate.of(2026, 9, 10)), NOW));
        var analysis = service.analyze(265598);
        verify(repository).upsert(any());
        assertThat(analysis.fromCache()).isFalse();
        assertThat(analysis.symbol()).isEqualTo("AAPL");
        assertThat(analysis.source()).isEqualTo(PriceBarRepository.SOURCE_TWS_DAILY);
        assertThat(analysis.barCount()).isEqualTo(80);
        assertThat(analysis.asOf()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(analysis.lastClose()).isEqualByComparingTo("179");
        assertThat(analysis.atr()).isEqualByComparingTo("2.0000");
        assertThat(analysis.trend()).isEqualTo("ABOVE_LONG_SMA");
        assertThat(analysis.momentum()).isPositive();
        assertThat(analysis.realizedVolatility()).isPositive();
        assertThat(analysis.longLevels().takeProfit()).isEqualByComparingTo("183.00");
        assertThat(analysis.longLevels().stopLoss()).isEqualByComparingTo("177.00");
        assertThat(analysis.shortLevels().takeProfit()).isEqualByComparingTo("175.00");
        assertThat(analysis.shortLevels().stopLoss()).isEqualByComparingTo("181.00");
        assertThat(analysis.levelMethod()).isEqualTo(QuantAnalysisService.LEVEL_METHOD);
        assertThat(analysis.limitations()).isEmpty();
    }

    @Test void freshStoredBarsSkipTheBroker() {
        when(repository.findLatest(265598, 120)).thenReturn(Optional.of(history(trending(80, LocalDate.of(2026, 9, 10)),
                NOW.minus(Duration.ofHours(1)))));
        var analysis = service.analyze(265598);
        assertThat(analysis.fromCache()).isTrue();
        verifyNoInteractions(broker);
        verify(repository, never()).upsert(any());
    }

    @Test void expiredStoredBarsAreRefreshedAndKeptWhenTheRefreshFails() {
        var stored = history(trending(80, LocalDate.of(2026, 9, 10)), NOW.minus(Duration.ofHours(13)));
        when(repository.findLatest(265598, 120)).thenReturn(Optional.of(stored));
        when(broker.getDailyBars(265598, 120)).thenThrow(new BrokerException(BrokerException.Code.SESSION_NOT_READY));
        var analysis = service.analyze(265598);
        assertThat(analysis.fromCache()).isTrue();
        assertThat(analysis.limitations()).containsExactly("HISTORY_REFRESH_FAILED:SESSION_NOT_READY");
        assertThat(analysis.longLevels()).isNotNull();
        verify(repository, never()).upsert(any());
    }

    @Test void brokerFailureWithoutStoredBarsPropagates() {
        when(broker.getDailyBars(265598, 120)).thenThrow(new BrokerException(BrokerException.Code.UNAVAILABLE));
        assertThatThrownBy(() -> service.analyze(265598)).isInstanceOf(BrokerException.class).hasMessage("UNAVAILABLE");
    }

    @Test void tooFewBarsIsAnExplicitFailureNotWeakStatistics() {
        when(broker.getDailyBars(265598, 120)).thenReturn(history(trending(59, LocalDate.of(2026, 9, 10)), NOW));
        assertThatThrownBy(() -> service.analyze(265598)).isInstanceOf(IllegalArgumentException.class).hasMessage("INSUFFICIENT_BARS");
    }

    @Test void staleSeriesKeepsStatisticsButSuppressesLevels() {
        when(broker.getDailyBars(265598, 120)).thenReturn(history(trending(80, LocalDate.of(2026, 9, 1)), NOW));
        var analysis = service.analyze(265598);
        assertThat(analysis.limitations()).containsExactly("STALE_BARS");
        assertThat(analysis.longLevels()).isNull();
        assertThat(analysis.shortLevels()).isNull();
        assertThat(analysis.atr()).isNotNull();
    }

    @Test void rejectsInvalidContractIdsBeforeAnyLookup() {
        assertThatThrownBy(() -> service.analyze(0)).isInstanceOf(BrokerException.class).hasMessage("INVALID_ARGUMENT");
        verifyNoInteractions(repository, broker);
    }

    /** Closes rise by one per bar with a constant two-point range, so ATR converges to exactly 2. */
    static List<DailyBar> trending(int count, LocalDate last) {
        var bars = new ArrayList<DailyBar>();
        for (int i = 0; i < count; i++) {
            double close = 100 + i;
            bars.add(new DailyBar(last.minusDays(count - 1 - i), BigDecimal.valueOf(close - 0.5), BigDecimal.valueOf(close + 1),
                    BigDecimal.valueOf(close - 1), BigDecimal.valueOf(close), BigDecimal.valueOf(1000)));
        }
        return bars;
    }
    static PriceHistory history(List<DailyBar> bars, Instant observed) { return new PriceHistory(265598, "AAPL", "USD", observed, bars); }
}

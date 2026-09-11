package project.stockrecommendationengine.quant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import project.stockrecommendationengine.broker.BrokerException;
import project.stockrecommendationengine.broker.BrokerReadService;
import project.stockrecommendationengine.broker.BrokerData.DailyBar;
import project.stockrecommendationengine.broker.BrokerData.PriceHistory;
import project.stockrecommendationengine.quant.QuantAnalysis.Levels;
import static project.stockrecommendationengine.quant.QuantIndicators.round;

/**
 * Deterministic price statistics and ATR-based levels from broker daily bars.
 * Never calls a model. Broker failures with no stored history propagate as BrokerException.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "quant.enabled", havingValue = "true")
public class QuantAnalysisService {
    public static final String LEVEL_METHOD = "ATR_MULTIPLE_OF_LAST_CLOSE";
    private final BrokerReadService broker;
    private final PriceBarRepository repository;
    private final QuantProperties properties;
    private final Clock clock;

    @Autowired
    public QuantAnalysisService(ObjectProvider<BrokerReadService> brokers, PriceBarRepository repository, QuantProperties properties) {
        this(brokers, repository, properties, Clock.systemUTC());
    }

    QuantAnalysisService(ObjectProvider<BrokerReadService> brokers, PriceBarRepository repository,
            QuantProperties properties, Clock clock) {
        this.broker = brokers.getIfAvailable();
        if (broker == null) throw new IllegalStateException("Enable the broker integration before enabling quant analysis");
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    public QuantAnalysis analyze(long conid) {
        if (conid <= 0) throw new BrokerException(BrokerException.Code.INVALID_ARGUMENT);
        var limitations = new LinkedHashSet<String>();
        Instant now = clock.instant();
        var stored = repository.findLatest(conid, properties.getHistoryDays());
        PriceHistory history;
        boolean fromCache;
        if (stored.isPresent() && stored.get().observedAt().isAfter(now.minus(Duration.ofHours(properties.getRefreshHours())))) {
            history = stored.get();
            fromCache = true;
        } else {
            try {
                history = broker.getDailyBars(conid, properties.getHistoryDays());
                repository.upsert(history);
                fromCache = false;
            } catch (BrokerException ex) {
                if (stored.isEmpty()) throw ex;
                // Stored bars remain usable evidence; the failed refresh is disclosed rather than hidden.
                history = stored.get();
                fromCache = true;
                limitations.add("HISTORY_REFRESH_FAILED:" + ex.code());
            }
        }
        List<DailyBar> bars = history.bars();
        if (bars.size() < properties.getMinBars()) throw new IllegalArgumentException("INSUFFICIENT_BARS");
        LocalDate asOf = bars.get(bars.size() - 1).date();
        boolean stale = asOf.isBefore(LocalDate.ofInstant(now, clock.getZone()).minusDays(properties.getMaxBarAgeDays()));
        if (stale) limitations.add("STALE_BARS");

        BigDecimal lastClose = bars.get(bars.size() - 1).close();
        Double atr = QuantIndicators.atr(bars, properties.getAtrPeriod());
        Double shortSma = QuantIndicators.sma(bars, properties.getShortSmaPeriod());
        Double longSma = QuantIndicators.sma(bars, properties.getLongSmaPeriod());
        String trend = longSma == null ? "UNAVAILABLE"
                : lastClose.doubleValue() >= longSma ? "ABOVE_LONG_SMA" : "BELOW_LONG_SMA";
        Levels longLevels = null, shortLevels = null;
        if (!stale && atr != null) {
            BigDecimal reward = BigDecimal.valueOf(atr * properties.getTakeProfitAtrMultiple());
            BigDecimal risk = BigDecimal.valueOf(atr * properties.getStopLossAtrMultiple());
            longLevels = new Levels(scale(lastClose.add(reward)), scale(lastClose.subtract(risk)));
            shortLevels = new Levels(scale(lastClose.subtract(reward)), scale(lastClose.add(risk)));
            if (longLevels.stopLoss().signum() <= 0 || shortLevels.takeProfit().signum() <= 0) {
                longLevels = shortLevels = null;
                limitations.add("LEVELS_OUT_OF_RANGE");
            }
        }
        var analysis = new QuantAnalysis(conid, history.symbol(), history.currency(), PriceBarRepository.SOURCE_TWS_DAILY,
                fromCache, history.observedAt(), asOf, bars.size(), lastClose,
                round(atr, 4), properties.getAtrPeriod(),
                round(QuantIndicators.realizedVolatility(bars, properties.getVolatilityPeriod()), 4), properties.getVolatilityPeriod(),
                round(shortSma, 4), properties.getShortSmaPeriod(), round(longSma, 4), properties.getLongSmaPeriod(),
                round(QuantIndicators.momentum(bars, properties.getVolatilityPeriod()), 4), trend,
                longLevels, shortLevels, LEVEL_METHOD, List.copyOf(limitations));
        log.info("Quant analysis conid={} bars={} asOf={} fromCache={} stale={} limitations={}",
                conid, bars.size(), asOf, fromCache, stale, limitations);
        return analysis;
    }

    private static BigDecimal scale(BigDecimal price) { return price.setScale(2, RoundingMode.HALF_UP); }
}

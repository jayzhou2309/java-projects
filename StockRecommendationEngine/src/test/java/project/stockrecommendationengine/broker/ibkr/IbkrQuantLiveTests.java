package project.stockrecommendationengine.broker.ibkr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.stockrecommendationengine.broker.BrokerReadService;
import project.stockrecommendationengine.quant.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in end-to-end check: real TWS daily bars through the real repository; lives here for package-private transport access. Rolls back stored rows. */
@SpringBootTest
@Transactional
@EnabledIfSystemProperty(named = "quant.live", matches = "true")
class IbkrQuantLiveTests {
    @Autowired PriceBarRepository repository;

    @Test void analyzesAaplFromTwsThenServesTheSecondCallFromStoredBars() {
        var ibkr = new IbkrProperties();
        ibkr.setHost(System.getenv().getOrDefault("IBKR_HOST", "127.0.0.1"));
        ibkr.setPort(Integer.parseInt(System.getenv().getOrDefault("IBKR_PORT", "7497")));
        ibkr.setClientId(Integer.parseInt(System.getenv().getOrDefault("IBKR_CLIENT_ID", "73")));
        var properties = new QuantProperties();
        properties.setEnabled(true);
        try (var client = new TwsClient(new TwsSocketTransport(), ibkr)) {
            var beans = new StaticListableBeanFactory();
            beans.addBean("broker", new IbkrBrokerAdapter(client));
            var service = new QuantAnalysisService(beans.getBeanProvider(BrokerReadService.class), repository, properties);
            var first = service.analyze(265598);
            System.out.println("QUANT_LIVE first=" + first);
            assertThat(first.fromCache()).isFalse();
            assertThat(first.barCount()).isGreaterThanOrEqualTo(properties.getMinBars());
            assertThat(first.atr()).isPositive();
            assertThat(first.realizedVolatility()).isPositive();
            assertThat(first.longSma()).isPositive();
            var second = service.analyze(265598);
            System.out.println("QUANT_LIVE second.fromCache=" + second.fromCache() + " limitations=" + second.limitations());
            assertThat(second.fromCache()).isTrue();
            assertThat(second.lastClose()).isEqualByComparingTo(first.lastClose());
            assertThat(second.atr()).isEqualByComparingTo(first.atr());
        }
    }
}

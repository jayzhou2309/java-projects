package project.ragdemo.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import project.ragdemo.config.IBKRConfig;
import java.math.BigDecimal;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real gateway test. No mocks, model calls, synthetic data, or orders. */
@EnabledIfSystemProperty(named = "ibkr.live", matches = "true")
class IBKRLiveTest {
    @Test
    void receivesActualBrokerSnapshot() throws Exception {
        IBKRProperties properties = new IBKRProperties();
        properties.setBaseUrl(System.getProperty("ibkr.url", "https://localhost:5001/v1/api"));
        properties.setTrustedCertificate(System.getProperty("ibkr.certificate"));
        properties.setHost(System.getProperty("ibkr.host", "127.0.0.1"));
        properties.setPort(Integer.getInteger("ibkr.port", 4002));
        properties.setClientId(Integer.getInteger("ibkr.clientId", 72));
        properties.setMarketDataType(Integer.getInteger("ibkr.marketDataType", 3));
        BrokerQuoteClient client = "rest".equals(System.getProperty("ibkr.transport", "socket"))
                ? new IBKRClient(new IBKRConfig().ibkrRestClient(properties))
                : new IBKRSocketQuoteClient(properties);
        String symbol = System.getProperty("ibkr.symbol", "AAPL");
        String conid = client.findContractsId(symbol);
        var quote = client.getCurrentPrice(conid);
        assertNotNull(quote.lastPrice(), "Broker did not supply a price");
        assertNotNull(quote.updated(), "Broker did not supply a snapshot timestamp");
        assertNotNull(quote.availability(), "Broker did not identify data availability");
        String numeric = quote.lastPrice().replaceFirst("^[CH]", "");
        assertTrue(new BigDecimal(numeric).signum() > 0, "Broker price must be positive");
        assertTrue(quote.availability().matches("[RDZY].*"), "No real/delayed/frozen broker data available");
        System.out.printf("BROKER_SNAPSHOT symbol=%s conid=%s rawPrice=%s availability=%s snapshotAt=%s fetchedAt=%s%n",
                symbol, conid, quote.lastPrice(), quote.availability(), Instant.ofEpochMilli(quote.updated()), Instant.now());
    }
}

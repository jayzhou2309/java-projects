package project.stockrecommendationengine.broker.ibkr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.*;

/** Opt-in TWS smoke tests. No orders or model calls; prints no account IDs or positions. */
@EnabledIfSystemProperty(named = "ibkr.live", matches = "true")
class IbkrLiveTests {
    private IbkrProperties properties() {
        var properties = new IbkrProperties();
        properties.setAccountId(System.getenv().getOrDefault("IBKR_ACCOUNT_ID", ""));
        properties.setHost(System.getenv().getOrDefault("IBKR_HOST", "127.0.0.1"));
        properties.setPort(Integer.parseInt(System.getenv().getOrDefault("IBKR_PORT", "7497")));
        properties.setClientId(Integer.parseInt(System.getenv().getOrDefault("IBKR_CLIENT_ID", "72")));
        properties.setMarketDataType(Integer.parseInt(System.getenv().getOrDefault("IBKR_MARKET_DATA_TYPE", "4")));
        return properties;
    }

    @Test void readsSessionAndDiscoversStock() {
        try (var client = new TwsClient(new TwsSocketTransport(), properties())) {
            var adapter = new IbkrBrokerAdapter(client);
            assertThat(adapter.sessionStatus().ready()).as("TWS handshake and heartbeat").isTrue();
            assertThat(adapter.searchInstruments("AAPL"))
                    .as("AAPL discovery includes its US stock contract")
                    .anyMatch(i -> i.conid() == 265598 && "USD".equals(i.currency()));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "IBKR_ACCOUNT_ID", matches = ".+")
    void readsConfiguredAccountAndOptionalPositions() {
        var properties = properties();
        properties.setEnabled(true);
        assertThat(properties.isConnectionValid()).as("Set a valid IBKR_ACCOUNT_ID").isTrue();
        try (var client = new TwsClient(new TwsSocketTransport(), properties)) {
            var adapter = new IbkrBrokerAdapter(client);
            assertThat(adapter.getAccounts()).hasSize(1);
            if (Boolean.getBoolean("ibkr.live.positions")) assertThat(adapter.getPositions().observedAt()).isNotNull();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "ibkr.live.history", matches = "true")
    void receivesDailyBars() {
        try (var client = new TwsClient(new TwsSocketTransport(), properties())) {
            var history = new IbkrBrokerAdapter(client).getDailyBars(265598, 120);
            System.out.println("TWS_HISTORY bars=" + history.bars().size() + " first="
                    + (history.bars().isEmpty() ? null : history.bars().get(0).date()) + " last="
                    + (history.bars().isEmpty() ? null : history.bars().get(history.bars().size() - 1).date()));
            assertThat(history.bars()).as("TWS must deliver daily bars without a market-data subscription").isNotEmpty();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "ibkr.live.conid", matches = ".+")
    void receivesUsableQuote() {
        try (var client = new TwsClient(new TwsSocketTransport(), properties())) {
            var quote = new IbkrBrokerAdapter(client).getQuote(Long.parseLong(System.getProperty("ibkr.live.conid")));
            System.out.println("TWS_QUOTE availability=" + quote.availability() + " hasPrice=" + quote.hasPrice());
            assertThat(quote.hasPrice()).as("TWS must deliver a usable price to pass the optional quote check").isTrue();
        }
    }
}

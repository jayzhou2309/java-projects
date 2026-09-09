package project.stockrecommendationengine.broker.ibkr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.*;

/** Opt-in read-only smoke test. Never prints account IDs, positions, credentials, or raw responses. */
@EnabledIfSystemProperty(named = "ibkr.live", matches = "true")
class IbkrLiveTests {
    @Test void readsConfiguredAccountPositionsAndOptionalQuote() throws Exception {
        var properties = new IbkrProperties();
        properties.setEnabled(true);
        properties.setAccountId(System.getenv().getOrDefault("IBKR_ACCOUNT_ID", ""));
        properties.setBaseUrl(System.getenv().getOrDefault("IBKR_BASE_URL", "https://localhost:5000/v1/api"));
        properties.setCertificate(System.getenv().getOrDefault("IBKR_CERTIFICATE", ""));
        assertThat(properties.isConnectionValid()).as("Set IBKR_ACCOUNT_ID and a valid HTTPS gateway URL").isTrue();
        var client = new IbkrConfiguration().ibkrApiClient(properties);
        var adapter = new IbkrBrokerAdapter(client, new IbkrSessionManager(client), properties);
        assertThat(adapter.getAccounts().size()).isEqualTo(1);
        assertThat(adapter.getPositions().observedAt()).isNotNull();
        String conid = System.getProperty("ibkr.live.conid");
        if (conid != null) {
            assertThat(adapter.sessionStatus().ready()).isTrue();
            assertThat(adapter.getQuote(Long.parseLong(conid)).hasPrice()).isTrue();
        }
    }
}

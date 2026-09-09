package project.ragdemo.market;
import org.junit.jupiter.api.Test;
import project.ragdemo.market.dto.IBKRMarketDataResponse;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MarketDataServiceTest {
    @Test
    void distinguishesFreshDelayedStaleAndPrefixedPrices() {
        var service = new MarketDataServiceImpl(null);
        Instant now = Instant.parse("2026-01-01T12:00:00Z");
        assertEquals(MarketData.Status.AVAILABLE, service.normalize("AAPL", "1",
                new IBKRMarketDataResponse("1", "100.25", "RpB", now.toEpochMilli()), now).status());
        assertEquals(MarketData.Status.STALE, service.normalize("AAPL", "1",
                new IBKRMarketDataResponse("1", "100.25", "DpB", now.toEpochMilli()), now).status());
        assertEquals(MarketData.Status.STALE, service.normalize("AAPL", "1",
                new IBKRMarketDataResponse("1", "100.25", "RpB", now.minusSeconds(300).toEpochMilli()), now).status());
        assertEquals(MarketData.Status.UNAVAILABLE, service.normalize("AAPL", "1",
                new IBKRMarketDataResponse("1", "C100.25", "RpB", now.toEpochMilli()), now).status());
    }
    @Test
    void providerFailureReturnsUnavailable() {
        var client = mock(IBKRClient.class);
        when(client.findContractsId("AAPL")).thenThrow(new IllegalStateException("Not authenticated"));
        assertEquals(MarketData.Status.UNAVAILABLE, new MarketDataServiceImpl(client).getMarketData("aapl").status());
    }
}

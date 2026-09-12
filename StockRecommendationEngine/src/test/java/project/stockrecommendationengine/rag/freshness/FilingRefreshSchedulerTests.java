package project.stockrecommendationengine.rag.freshness;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FilingRefreshSchedulerTests {
    @Test void refreshesEveryStoredTickerAndIsolatesFailures() {
        var freshness = mock(FilingFreshnessService.class);
        when(freshness.storedTickers()).thenReturn(List.of("AAPL", "BAD", "MSFT"));
        var ok = new FilingRefreshResult("AAPL", Instant.now(), 3, List.of(), List.of(), List.of());
        when(freshness.refresh("AAPL")).thenReturn(ok);
        when(freshness.refresh("BAD")).thenThrow(new IllegalStateException("SEC unavailable"));
        when(freshness.refresh("MSFT")).thenReturn(new FilingRefreshResult("MSFT", Instant.now(), 2, List.of("x"), List.of("x"), List.of()));
        var results = new FilingRefreshScheduler(freshness).refreshAll();
        assertThat(results).extracting(FilingRefreshResult::ticker).containsExactly("AAPL", "MSFT");
        verify(freshness).refresh("MSFT");
    }
}

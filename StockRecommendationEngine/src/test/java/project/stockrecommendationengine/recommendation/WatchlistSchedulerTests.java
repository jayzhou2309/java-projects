package project.stockrecommendationengine.recommendation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WatchlistSchedulerTests {
    private final RecommendationService service = mock(RecommendationService.class);
    private final RecommendationProperties properties = new RecommendationProperties();
    private final WatchlistScheduler scheduler = new WatchlistScheduler(service, properties, Clock.fixed(Instant.parse("2026-09-14T14:45:00Z"), ZoneOffset.UTC));

    @Test void runsEveryTickerInOrderWithTheDirectionalQuestionAndIsolatesFailures() {
        properties.getSchedule().setEnabled(true);
        properties.getSchedule().setTickers(List.of("aapl", "BAD", "MSFT"));
        properties.getSchedule().setPauseMs(0);
        when(service.recommend(any())).thenAnswer(invocation -> {
            RecommendationRequest request = invocation.getArgument(0);
            if (request == null) return null; // verification with a captor re-enters the answer with a null argument
            if (request.ticker().equals("BAD")) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "busy");
            return response(request.ticker(), request.ticker().equals("AAPL") ? "BULLISH" : "BEARISH");
        });
        var run = scheduler.run("MANUAL");
        assertThat(run.trigger()).isEqualTo("MANUAL");
        assertThat(run.tickers()).isEqualTo(3);
        assertThat(run.completed()).isEqualTo(2);
        assertThat(run.failed()).isEqualTo(1);
        assertThat(run.results()).extracting(WatchlistScheduler.TickerResult::ticker).containsExactly("AAPL", "BAD", "MSFT");
        assertThat(run.results().get(0).runId()).isEqualTo("run-AAPL");
        assertThat(run.results().get(0).assessment()).isEqualTo("BULLISH");
        assertThat(run.results().get(0).critic()).isEqualTo("ACCEPT");
        assertThat(run.results().get(1).error()).isEqualTo("HTTP_429");
        assertThat(run.results().get(1).runId()).isNull();
        assertThat(run.results().get(2).calibratedConfidence()).isEqualByComparingTo("0.42");
        var requests = org.mockito.ArgumentCaptor.forClass(RecommendationRequest.class);
        verify(service, times(3)).recommend(requests.capture());
        var first = requests.getAllValues().get(0);
        assertThat(first.ticker()).isEqualTo("AAPL");
        assertThat(first.question()).startsWith("Based on the latest filings").contains("is AAPL more likely to rise or fall over the next 20 trading days")
                .doesNotContain("{ticker}");
        assertThat(first.conid()).isNull();
        assertThat(first.includePortfolio()).isFalse();
        assertThat(scheduler.lastRun()).contains(run);
        // Unexpected failures are named by class, and the pass continues.
        when(service.recommend(any())).thenThrow(new IllegalStateException("model down"));
        var broken = scheduler.run("SCHEDULE");
        assertThat(broken.failed()).isEqualTo(3);
        assertThat(broken.results()).allSatisfy(r -> assertThat(r.error()).isEqualTo("IllegalStateException"));
        assertThat(scheduler.lastRun()).contains(broken);
    }

    @Test void noTickersMeansAnEmptyPassAndTheScheduleValidationRequiresTickersWhenEnabled() {
        assertThat(scheduler.lastRun()).isEmpty();
        var run = scheduler.run("MANUAL");
        assertThat(run.tickers()).isZero();
        assertThat(run.results()).isEmpty();
        verifyNoInteractions(service);
        var schedule = properties.getSchedule();
        assertThat(schedule.isTickersConfigured()).as("disabled schedule needs no tickers").isTrue();
        schedule.setEnabled(true);
        assertThat(schedule.isTickersConfigured()).isFalse();
        schedule.setTickers(List.of("AAPL"));
        assertThat(schedule.isTickersConfigured()).isTrue();
        assertThat(schedule.getCron()).isEqualTo("0 45 10 * * MON-FRI");
        assertThat(schedule.getZone()).isEqualTo("America/New_York");
        assertThat(schedule.getPauseMs()).as("consecutive runs sit in separate provider TPM windows").isEqualTo(60000);
    }

    private static RecommendationResponse response(String ticker, String assessment) {
        return new RecommendationResponse("run-" + ticker, ticker, "PARTIAL", assessment, "reasoning", List.of(), List.of(), List.of(), List.of(),
                5, 1000, null, null, new BigDecimal("0.30"), null, null, null,
                new RecommendationResponse.Critique("ACCEPT", List.of(), List.of(), false, List.of()), new BigDecimal("0.42"), null);
    }
}

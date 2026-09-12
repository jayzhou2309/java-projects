package project.stockrecommendationengine.rag.freshness;

import java.time.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import project.stockrecommendationengine.rag.dto.SECFilingMetadata;
import project.stockrecommendationengine.rag.entity.SECFiling;
import project.stockrecommendationengine.rag.ingestion.FilingIngestionService;
import project.stockrecommendationengine.rag.ingestion.SECClient;
import project.stockrecommendationengine.rag.ingestion.UnknownTickerException;
import project.stockrecommendationengine.rag.repository.SECFilingRepository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FilingFreshnessServiceTests {
    private static final Instant NOW = Instant.parse("2026-09-12T02:00:00Z");
    private final SECClient sec = mock(SECClient.class);
    private final FilingIngestionService ingestion = mock(FilingIngestionService.class);
    private final SECFilingRepository filings = mock(SECFilingRepository.class);
    private final FilingRefreshProperties properties = new FilingRefreshProperties();
    private FilingFreshnessService service;

    @BeforeEach void setup() {
        service = new FilingFreshnessService(sec, ingestion, filings, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void assessReportsLatestDatesAndCadence() {
        stored("10-K", "2025-10-31"); stored("10-Q", "2026-07-31");
        var fresh = service.assess("aapl");
        assertThat(fresh.ticker()).isEqualTo("AAPL");
        assertThat(fresh.latestFilingDates()).containsEntry("10-K", LocalDate.parse("2025-10-31")).containsEntry("10-Q", LocalDate.parse("2026-07-31"));
        assertThat(fresh.newestQuarterly()).isEqualTo(LocalDate.parse("2026-07-31"));
        assertThat(fresh.cadenceExceeded()).isFalse();
        assertThat(fresh.mayBeStale()).isFalse();
        stored("10-Q", "2026-05-01");
        var stale = service.assess("AAPL");
        assertThat(stale.cadenceExceeded()).isTrue();
        assertThat(stale.mayBeStale()).isTrue();
        assertThat(stale.lastVerifiedAt()).isNull();
    }

    @Test void refreshIngestsOnlyNewFilingsWithinPerTypeLimitsAndRecordsVerification() {
        when(sec.getRecentFilings(eq("AAPL"), anyList(), eq(40))).thenReturn(List.of(
                meta("8-K", "0001-new-8k-1"), meta("10-Q", "0002-stored-10q"), meta("8-K", "0003-stored-8k"),
                meta("8-K", "0004-new-8k-3"), meta("8-K", "0005-beyond-limit"), meta("10-K", "0006-new-10k")));
        when(filings.existsByAccessionNoAndIngestionStatus("0002-stored-10q", "EMBEDDED")).thenReturn(true);
        when(filings.existsByAccessionNoAndIngestionStatus("0003-stored-8k", "EMBEDDED")).thenReturn(true);
        stored("10-Q", "2026-05-01");
        var result = service.refresh("AAPL");
        assertThat(result.indexFilings()).isEqualTo(6);
        assertThat(result.newAccessions()).containsExactly("0001-new-8k-1", "0004-new-8k-3", "0006-new-10k");
        assertThat(result.ingestedAccessions()).containsExactly("0001-new-8k-1", "0004-new-8k-3", "0006-new-10k");
        assertThat(result.succeeded()).isTrue();
        verify(ingestion, times(3)).ingestOne(any());
        verify(ingestion, never()).ingestOne(argThat(m -> m.accessionNo().equals("0002-stored-10q") || m.accessionNo().equals("0005-beyond-limit")));
        var after = service.assess("AAPL");
        assertThat(after.lastVerifiedAt()).isEqualTo(NOW);
        assertThat(after.cadenceExceeded()).isTrue();
        assertThat(after.mayBeStale()).as("verified within the recheck window").isFalse();
    }

    @Test void refreshFailuresAreIsolatedPerFilingAndTheIndexStillCountsAsCompared() {
        when(sec.getRecentFilings(eq("AAPL"), anyList(), eq(40))).thenReturn(List.of(meta("8-K", "bad"), meta("8-K", "good"), meta("10-K", "k")));
        doThrow(new IllegalStateException("No filing content could be extracted")).when(ingestion).ingestOne(argThat(m -> m.accessionNo().equals("bad")));
        var result = service.refresh("AAPL");
        assertThat(result.failedAccessions()).containsExactly("bad");
        assertThat(result.ingestedAccessions()).containsExactly("good", "k");
        assertThat(result.succeeded()).isFalse();
        assertThat(service.assess("AAPL").lastVerifiedAt()).as("the index was compared").isEqualTo(NOW);
        when(sec.getRecentFilings(eq("AAPL"), anyList(), eq(40))).thenThrow(new IllegalStateException("SEC down"));
        assertThatThrownBy(() -> service.refresh("AAPL")).isInstanceOf(RuntimeException.class);
    }

    @Test void ensureIngestsMissingRefreshesStaleAndLeavesFreshAlone() {
        when(sec.getRecentFilings(eq("MSFT"), anyList(), eq(40))).thenReturn(List.of(meta("10-K", "k1"), meta("10-Q", "q1")));
        var missing = service.ensure("MSFT");
        assertThat(missing.action()).isEqualTo("INGESTED");
        verify(ingestion, times(2)).ingestOne(argThat(m -> m.ticker().equals("AAPL")));

        stored("10-Q", "2026-05-01");
        when(sec.getRecentFilings(eq("AAPL"), anyList(), eq(40))).thenReturn(List.of(meta("10-Q", "q-stored")));
        when(filings.existsByAccessionNoAndIngestionStatus("q-stored", "EMBEDDED")).thenReturn(true);
        var stale = service.ensure("AAPL");
        assertThat(stale.action()).as("index compared, nothing newer at SEC").isEqualTo("VERIFIED");
        assertThat(stale.freshness().mayBeStale()).isFalse();
        clearInvocations(sec);
        assertThat(service.ensure("AAPL").action()).as("verified within recheck window").isEqualTo("FRESH");
        verifyNoInteractions(sec);

        when(sec.getRecentFilings(eq("NOPE"), anyList(), eq(40))).thenReturn(List.of());
        assertThat(service.ensure("NOPE").action()).isEqualTo("NO_FILINGS_AVAILABLE");
        when(sec.getRecentFilings(eq("BAD"), anyList(), eq(40))).thenThrow(new UnknownTickerException("BAD"));
        assertThatThrownBy(() -> service.ensure("BAD")).isInstanceOf(UnknownTickerException.class);
        assertThatThrownBy(() -> service.ensure("A&B")).isInstanceOf(UnknownTickerException.class);
    }

    private void stored(String type, String date) {
        var filing = SECFiling.builder().ticker("AAPL").filingType(type).filingDate(LocalDate.parse(date)).ingestionStatus("EMBEDDED").build();
        when(filings.findFirstByTickerAndFilingTypeAndIngestionStatusOrderByFilingDateDesc("AAPL", type, "EMBEDDED")).thenReturn(Optional.of(filing));
    }
    private static SECFilingMetadata meta(String type, String accession) {
        return new SECFilingMetadata("AAPL", "0000320193", accession, type, LocalDate.parse("2026-09-01"), null, "doc.htm", "https://sec.example/" + accession);
    }
}

package project.stockrecommendationengine.rag.freshness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import project.stockrecommendationengine.rag.dto.SECFilingMetadata;
import project.stockrecommendationengine.rag.ingestion.FilingIngestionService;
import project.stockrecommendationengine.rag.ingestion.SECClient;
import project.stockrecommendationengine.rag.ingestion.UnknownTickerException;
import project.stockrecommendationengine.rag.repository.SECFilingRepository;

/**
 * Keeps stored filings current: compares the SEC submissions index with stored accession numbers and ingests
 * only what is new, using the existing ingestion service and its per-accession locks. Verification times are
 * kept in memory per application instance; a restart simply causes one extra index comparison per ticker.
 */
@Service
@Slf4j
public class FilingFreshnessService {
    public static final String EMBEDDED = "EMBEDDED";
    private final SECClient secClient;
    private final FilingIngestionService ingestion;
    private final SECFilingRepository filings;
    private final FilingRefreshProperties properties;
    private final Clock clock;
    private final Map<String, Instant> verifiedAt = new ConcurrentHashMap<>();

    @Autowired
    public FilingFreshnessService(SECClient secClient, FilingIngestionService ingestion, SECFilingRepository filings,
            FilingRefreshProperties properties) {
        this(secClient, ingestion, filings, properties, Clock.systemUTC());
    }
    FilingFreshnessService(SECClient secClient, FilingIngestionService ingestion, SECFilingRepository filings,
            FilingRefreshProperties properties, Clock clock) {
        this.secClient = secClient;
        this.ingestion = ingestion;
        this.filings = filings;
        this.properties = properties;
        this.clock = clock;
    }

    /** Read-only view of what is stored and whether it is past cadence without a recent verification. */
    public FilingFreshness assess(String ticker) {
        String normalized = normalize(ticker);
        var latest = new LinkedHashMap<String, LocalDate>();
        for (String type : properties.getLimits().keySet()) {
            filings.findFirstByTickerAndFilingTypeAndIngestionStatusOrderByFilingDateDesc(normalized, type, EMBEDDED)
                    .ifPresent(filing -> latest.put(type, filing.getFilingDate()));
        }
        LocalDate newestQuarterly = Optional.ofNullable(latest.get("10-Q")).map(q -> {
            LocalDate k = latest.get("10-K");
            return k != null && k.isAfter(q) ? k : q;
        }).orElse(latest.get("10-K"));
        LocalDate today = LocalDate.ofInstant(clock.instant(), clock.getZone());
        boolean cadenceExceeded = newestQuarterly == null
                || newestQuarterly.isBefore(today.minusDays(properties.getQuarterlyCadenceDays()));
        Instant verified = verifiedAt.get(normalized);
        boolean recentlyVerified = verified != null
                && verified.isAfter(clock.instant().minus(Duration.ofHours(properties.getRecheckHours())));
        return new FilingFreshness(normalized, Map.copyOf(latest), newestQuarterly, cadenceExceeded, verified,
                cadenceExceeded && !recentlyVerified);
    }

    /** Compare the SEC index with the store and ingest the newest filings per type that are missing or incomplete. */
    public FilingRefreshResult refresh(String ticker) {
        String normalized = normalize(ticker);
        Instant checkedAt = clock.instant();
        List<String> types = List.copyOf(properties.getLimits().keySet());
        List<SECFilingMetadata> index = secClient.getRecentFilings(normalized, types, properties.getIndexLimit());
        var newFilings = new ArrayList<SECFilingMetadata>();
        var seen = new HashMap<String, Integer>();
        for (SECFilingMetadata metadata : index) {
            int limit = properties.getLimits().getOrDefault(metadata.filingType(), 0);
            int count = seen.merge(metadata.filingType(), 1, Integer::sum);
            if (count > limit) continue;
            if (!filings.existsByAccessionNoAndIngestionStatus(metadata.accessionNo(), EMBEDDED)) newFilings.add(metadata);
        }
        var ingested = new ArrayList<String>();
        var failed = new ArrayList<String>();
        for (SECFilingMetadata metadata : newFilings) {
            // Each filing commits or fails on its own; one unparseable filing never blocks the others.
            try {
                ingestion.ingestOne(metadata);
                ingested.add(metadata.accessionNo());
            } catch (RuntimeException ex) {
                log.warn("Filing refresh failed: ticker={} accession={} type={} cause={}", normalized,
                        metadata.accessionNo(), metadata.filingType(), ex.getClass().getSimpleName());
                failed.add(metadata.accessionNo());
            }
        }
        // The index was compared even if a filing failed to parse; only a failure to compare leaves the ticker unverified.
        verifiedAt.put(normalized, checkedAt);
        var result = new FilingRefreshResult(normalized, checkedAt, index.size(),
                newFilings.stream().map(SECFilingMetadata::accessionNo).toList(), List.copyOf(ingested), List.copyOf(failed));
        log.info("Filing refresh: ticker={} indexFilings={} new={} ingested={} failed={}",
                normalized, index.size(), newFilings.size(), ingested.size(), failed.size());
        return result;
    }

    /** What the recommendation loop calls before retrieval: ingest a missing ticker, refresh a stale one, else nothing. */
    public EnsureOutcome ensure(String ticker) {
        String normalized = normalize(ticker);
        var before = assess(normalized);
        if (before.latestFilingDates().isEmpty()) {
            var result = refresh(normalized);
            String action = result.newAccessions().isEmpty() ? "NO_FILINGS_AVAILABLE"
                    : result.ingestedAccessions().isEmpty() ? "INGESTION_FAILED"
                    : result.succeeded() ? "INGESTED" : "INGESTED_PARTIALLY";
            return new EnsureOutcome(action, result, assess(normalized));
        }
        if (before.mayBeStale()) {
            var result = refresh(normalized);
            String action = result.newAccessions().isEmpty() ? "VERIFIED"
                    : result.ingestedAccessions().isEmpty() ? "REFRESH_FAILED"
                    : result.succeeded() ? "REFRESHED" : "REFRESHED_PARTIALLY";
            return new EnsureOutcome(action, result, assess(normalized));
        }
        return new EnsureOutcome("FRESH", null, before);
    }

    /** All tickers with any stored filing, for the nightly job. */
    public List<String> storedTickers() { return filings.findDistinctTickers(); }

    public record EnsureOutcome(String action, FilingRefreshResult refresh, FilingFreshness freshness) { }

    private static String normalize(String ticker) {
        if (ticker == null || !ticker.trim().matches("[A-Za-z0-9.-]{1,16}")) throw new UnknownTickerException(String.valueOf(ticker));
        return ticker.trim().toUpperCase(Locale.ROOT);
    }
}

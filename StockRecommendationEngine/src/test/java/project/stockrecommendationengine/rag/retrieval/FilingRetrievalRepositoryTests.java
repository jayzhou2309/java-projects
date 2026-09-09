package project.stockrecommendationengine.rag.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.stockrecommendationengine.rag.repository.FilingRetrievalRepository;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class FilingRetrievalRepositoryTests {
    @Autowired FilingRetrievalRepository retrievalRepository;
    @Autowired NamedParameterJdbcTemplate jdbcTemplate;
    private String ticker;

    @BeforeEach
    void setUp() {
        ticker = "RT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void ranksEligibleChunksByCosineAndReturnsCitationMetadata() {
        Long bestChunkId = insertChunk(ticker, "10-K", "2025-10-31", "EMBEDDED", "ITEM_1A", vector(1, 0));
        Long nextChunkId = insertChunk(ticker, "10-K", "2025-09-30", "EMBEDDED", "ITEM_1A", vector(0.8f, 0.6f));
        insertChunk(ticker + "X", "10-K", "2025-10-31", "EMBEDDED", "ITEM_1A", vector(1, 0));
        insertChunk(ticker, "10-K", "2025-10-31", "FAILED", "ITEM_1A", vector(1, 0));
        insertChunk(ticker, "10-K", "2025-10-31", "EMBEDDED", "ITEM_1A", null);
        insertChunk(ticker, "10-K", "2025-10-31", "EMBEDDED", "ITEM_1A", new float[1536]);

        var results = retrievalRepository.findSimilarChunks(vector(1, 0), filter(false), 20);
        assertThat(results).extracting(result -> result.chunkId()).containsExactly(bestChunkId, nextChunkId);
        var best = results.get(0);
        assertThat(best.similarityScore()).isCloseTo(1.0, within(0.000001));
        assertThat(results.get(1).similarityScore()).isCloseTo(0.8, within(0.000001));
        assertThat(best.ticker()).isEqualTo(ticker);
        assertThat(best.filingDate()).isEqualTo(LocalDate.parse("2025-10-31"));
        assertThat(best.reportDate()).isEqualTo(LocalDate.parse("2025-09-27"));
        assertThat(best.sectionKey()).isEqualTo("ITEM_1A");
        assertThat(best.content()).isEqualTo("Evidence for " + best.accessionNo());
        assertThat(best.sourceUrl()).isEqualTo("https://example.invalid/" + best.accessionNo());
        assertThat(retrievalRepository.findSimilarChunks(vector(1, 0), filter(false), 1)).hasSize(1);
    }

    @Test
    void latestPolicySelectsOneFilingPerTypeBeforeRankingChunks() {
        insertChunk(ticker, "10-K", "2024-10-31", "EMBEDDED", "ITEM_1A", vector(1, 0));
        Long latestAnnualChunk = insertChunk(ticker, "10-K", "2025-10-31", "EMBEDDED", "ITEM_1A", vector(0.8f, 0.6f));
        Long latestQuarterlyChunk = insertChunk(ticker, "10-Q", "2026-02-01", "EMBEDDED", "ITEM_1A", vector(0.6f, 0.8f));
        var results = retrievalRepository.findSimilarChunks(vector(1, 0), filter(true), 20);
        assertThat(results).extracting(result -> result.chunkId()).containsExactly(latestAnnualChunk, latestQuarterlyChunk);
    }

    @Test
    void appliesInclusiveFilingDateTypeAndSectionFiltersBeforeLimit() {
        Long expectedChunk = insertChunk(ticker, "10-K", "2025-10-31", "EMBEDDED", "ITEM_1A", vector(0.6f, 0.8f));
        insertChunk(ticker, "10-K", "2025-11-01", "EMBEDDED", "ITEM_1A", vector(1, 0));
        insertChunk(ticker, "10-K", "2025-10-30", "EMBEDDED", "ITEM_1A", vector(1, 0));
        insertChunk(ticker, "10-Q", "2025-10-31", "EMBEDDED", "ITEM_1A", vector(1, 0));
        insertChunk(ticker, "10-K", "2025-10-31", "EMBEDDED", "ITEM_7", vector(1, 0));
        var filter = new FilingRetrievalFilter(ticker, List.of("10-K"), LocalDate.parse("2025-10-31"),
                LocalDate.parse("2025-10-31"), List.of("ITEM_1A"), false);
        assertThat(retrievalRepository.findSimilarChunks(vector(1, 0), filter, 1))
                .extracting(result -> result.chunkId()).containsExactly(expectedChunk);
    }

    @Test
    void emptyScopeReturnsNoResultsAndFilterValuesAreBoundAsData() {
        insertChunk(ticker, "10-K", "2025-10-31", "EMBEDDED", "ITEM_1A", vector(1, 0));
        var maliciousFilter = new FilingRetrievalFilter("' OR true --", List.of(), null, null, List.of(), false);
        assertThat(retrievalRepository.findSimilarChunks(vector(1, 0), maliciousFilter, 5)).isEmpty();
    }

    @Test
    void rejectsInvalidQueryVectors() {
        assertThatThrownBy(() -> retrievalRepository.findSimilarChunks(new float[3], filter(false), 5))
                .hasMessageContaining("1536 dimensions");
        assertThatThrownBy(() -> retrievalRepository.findSimilarChunks(new float[1536], filter(false), 5))
                .hasMessageContaining("zero vector");
        assertThatThrownBy(() -> retrievalRepository.findSimilarChunks(vector(Float.NaN, 0), filter(false), 5))
                .hasMessageContaining("non-finite");
    }

    private FilingRetrievalFilter filter(boolean latestOnly) {
        return new FilingRetrievalFilter(ticker, List.of(), null, null, List.of(), latestOnly);
    }

    private float[] vector(float firstComponent, float secondComponent) {
        float[] embedding = new float[1536];
        embedding[0] = firstComponent;
        embedding[1] = secondComponent;
        return embedding;
    }

    private Long insertChunk(String filingTicker, String filingType, String filingDate,
                             String ingestionStatus, String sectionKey, float[] embedding) {
        String accession = UUID.randomUUID().toString().replace("-", "");
        var parameters = new MapSqlParameterSource()
                .addValue("ticker", filingTicker).addValue("filingType", filingType)
                .addValue("filingDate", LocalDate.parse(filingDate)).addValue("status", ingestionStatus)
                .addValue("accession", accession).addValue("sourceUrl", "https://example.invalid/" + accession);
        Long filingId = jdbcTemplate.queryForObject("""
                INSERT INTO sec_filings(ticker,cik,accession_no,filing_type,filing_date,report_date,source_url,ingestion_status)
                VALUES (:ticker,'0000000001',:accession,:filingType,:filingDate,'2025-09-27',:sourceUrl,:status)
                RETURNING id
                """, parameters, Long.class);
        parameters.addValue("filingId", filingId).addValue("sectionKey", sectionKey)
                .addValue("content", "Evidence for " + accession)
                .addValue("embedding", embedding == null ? null : Arrays.toString(embedding));
        return jdbcTemplate.queryForObject("""
                INSERT INTO sec_filing_chunks(filing_id,chunk_index,section_key,section_title,content,embedding)
                VALUES (:filingId,0,:sectionKey,'Section title',:content,CAST(:embedding AS vector)) RETURNING id
                """, parameters, Long.class);
    }
}

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

    @Test
    void keywordSearchRanksTheChunkHoldingTheFigureFirstAndCarriesCosineSimilarity() {
        Long chinaChunk = insertChunk(ticker, "10-K", "2025-10-31", "EMBEDDED", "ITEM_7", vector(0.6f, 0.8f),
                "Greater China net sales 64,377 (4) % compared with 66,952 in the prior year");
        Long americasChunk = insertChunk(ticker, "10-K", "2025-10-31", "EMBEDDED", "ITEM_7", vector(1, 0),
                "Americas net sales 178,000 up 3 % driven by iPhone");
        Long europeChunk = insertChunk(ticker, "10-K", "2025-10-31", "EMBEDDED", "ITEM_7", vector(0.8f, 0.6f),
                "Europe net sales 105,000 up 5 % driven by services");
        insertChunk(ticker, "10-K", "2025-10-31", "EMBEDDED", "ITEM_7", vector(1, 0),
                "Risk factors unrelated to regional results");

        float[] queryEmbedding = vector(1, 0);
        var results = retrievalRepository.findKeywordChunks(
                "What were Apple's Greater China net sales in fiscal 2025, 64,377?", queryEmbedding, filter(false), 20);
        assertThat(results).extracting(result -> result.chunkId())
                .containsExactly(chinaChunk, americasChunk, europeChunk);
        for (var result : results) {
            float[] chunkEmbedding = result.chunkId().equals(chinaChunk) ? vector(0.6f, 0.8f)
                    : result.chunkId().equals(americasChunk) ? vector(1, 0) : vector(0.8f, 0.6f);
            assertThat(result.similarityScore()).isCloseTo(cosine(queryEmbedding, chunkEmbedding), within(0.000001));
        }
        var best = results.get(0);
        assertThat(best.similarityScore()).isCloseTo(0.6, within(0.000001));
        assertThat(best.ticker()).isEqualTo(ticker);
        assertThat(best.sectionKey()).isEqualTo("ITEM_7");
        assertThat(best.content()).contains("64,377");
        assertThat(best.sourceUrl()).isEqualTo("https://example.invalid/" + best.accessionNo());

        // The figure alone finds exactly the chunk that contains it as one number, not the split digits.
        assertThat(retrievalRepository.findKeywordChunks("64,377", queryEmbedding, filter(false), 20))
                .extracting(result -> result.chunkId()).containsExactly(chinaChunk);
        assertThat(retrievalRepository.findKeywordChunks("net sales", queryEmbedding, filter(false), 1))
                .extracting(result -> result.chunkId()).containsExactly(americasChunk);
    }

    @Test
    void keywordSearchReturnsNothingWhenNoTermMatchesOrNoTermRemains() {
        insertChunk(ticker, "10-K", "2025-10-31", "EMBEDDED", "ITEM_1A", vector(1, 0),
                "Greater China net sales 64,377 (4) %");
        assertThat(retrievalRepository.findKeywordChunks("OpenAI data center backlog", vector(1, 0), filter(false), 20))
                .isEmpty();
        assertThat(retrievalRepository.findKeywordChunks("64,378 iPhone", vector(1, 0), filter(false), 20)).isEmpty();
        assertThat(retrievalRepository.findKeywordChunks("the and of", vector(1, 0), filter(false), 20)).isEmpty();
        assertThat(retrievalRepository.findKeywordChunks("?!", vector(1, 0), filter(false), 20)).isEmpty();
        assertThatThrownBy(() -> retrievalRepository.findKeywordChunks("net sales", new float[1536], filter(false), 5))
                .hasMessageContaining("zero vector");
    }

    @Test
    void keywordSearchAppliesTheSameEligibilityAsVectorSearch() {
        String content = "Greater China net sales 64,377 (4) %";
        Long olderAnnualChunk = insertChunk(ticker, "10-K", "2024-10-31", "EMBEDDED", "ITEM_7", vector(1, 0), content);
        Long latestAnnualChunk = insertChunk(ticker, "10-K", "2025-10-31", "EMBEDDED", "ITEM_7", vector(0.8f, 0.6f), content);
        Long latestQuarterlyChunk = insertChunk(ticker, "10-Q", "2026-02-01", "EMBEDDED", "ITEM_7", vector(0.6f, 0.8f), content);
        insertChunk(ticker, "10-K", "2025-10-30", "EMBEDDED", "ITEM_1A", vector(1, 0), content);
        insertChunk(ticker, "10-K", "2025-11-15", "FAILED", "ITEM_7", vector(1, 0), content);
        insertChunk(ticker + "X", "10-K", "2025-10-31", "EMBEDDED", "ITEM_7", vector(1, 0), content);
        insertChunk(ticker, "10-K", "2025-10-29", "EMBEDDED", "ITEM_7", null, content);
        insertChunk(ticker, "10-K", "2025-10-28", "EMBEDDED", "ITEM_7", new float[1536], content);

        // Section, ticker, status, and usable-embedding filters with every filing eligible by date.
        var sectionFilter = new FilingRetrievalFilter(ticker, List.of(), null, null, List.of("ITEM_7"), false);
        // Equal ts_rank_cd across identical content: the tie breaks by vector similarity, then chunk id.
        assertThat(retrievalRepository.findKeywordChunks("Greater China 64,377", vector(1, 0), sectionFilter, 20))
                .extracting(result -> result.chunkId())
                .containsExactly(olderAnnualChunk, latestAnnualChunk, latestQuarterlyChunk);
        assertThat(retrievalRepository.findSimilarChunks(vector(1, 0), sectionFilter, 20))
                .extracting(result -> result.chunkId())
                .containsExactly(olderAnnualChunk, latestAnnualChunk, latestQuarterlyChunk);

        // Latest filing per type: the later FAILED 10-K must not shadow the latest EMBEDDED one.
        assertThat(retrievalRepository.findKeywordChunks("Greater China 64,377", vector(1, 0), filter(true), 20))
                .extracting(result -> result.chunkId()).containsExactly(latestAnnualChunk, latestQuarterlyChunk);
        assertThat(retrievalRepository.findSimilarChunks(vector(1, 0), filter(true), 20))
                .extracting(result -> result.chunkId()).containsExactly(latestAnnualChunk, latestQuarterlyChunk);
        assertThat(retrievalRepository.findKeywordChunks("64,377", vector(1, 0), filter(true), 1)).hasSize(1);

        var typeAndDateFilter = new FilingRetrievalFilter(ticker, List.of("10-Q"), LocalDate.parse("2026-02-01"),
                LocalDate.parse("2026-02-01"), List.of(), false);
        assertThat(retrievalRepository.findKeywordChunks("64,377", vector(1, 0), typeAndDateFilter, 20))
                .extracting(result -> result.chunkId()).containsExactly(latestQuarterlyChunk);

        var maliciousFilter = new FilingRetrievalFilter("' OR true --", List.of(), null, null, List.of(), false);
        assertThat(retrievalRepository.findKeywordChunks("64,377", vector(1, 0), maliciousFilter, 5)).isEmpty();
    }

    @Test
    void keywordIndexExistsAndGeneratedColumnIsPopulatedForEveryRow() {
        insertChunk(ticker, "10-K", "2025-10-31", "EMBEDDED", "ITEM_7", vector(1, 0), "Greater China 64,377");
        insertChunk(ticker, "10-K", "2025-10-31", "EMBEDDED", "ITEM_7", null, "Data Center revenue");
        var noParameters = new MapSqlParameterSource();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('idx_sec_filing_chunks_content_tsv')", noParameters, String.class))
                .isEqualTo("idx_sec_filing_chunks_content_tsv");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sec_filing_chunks chunk JOIN sec_filings filing ON filing.id = chunk.filing_id
                WHERE filing.ticker = :ticker AND chunk.content_tsv IS NULL
                """, new MapSqlParameterSource("ticker", ticker), Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sec_filing_chunks WHERE content_tsv IS NULL", noParameters, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sec_filing_chunks chunk JOIN sec_filings filing ON filing.id = chunk.filing_id
                WHERE filing.ticker = :ticker AND chunk.content_tsv @@ to_tsquery('english', '''64,377''')
                """, new MapSqlParameterSource("ticker", ticker), Long.class)).isEqualTo(1L);
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

    private double cosine(float[] first, float[] second) {
        double dot = 0, firstNorm = 0, secondNorm = 0;
        for (int index = 0; index < first.length; index++) {
            dot += (double) first[index] * second[index];
            firstNorm += (double) first[index] * first[index];
            secondNorm += (double) second[index] * second[index];
        }
        return dot / (Math.sqrt(firstNorm) * Math.sqrt(secondNorm));
    }

    private Long insertChunk(String filingTicker, String filingType, String filingDate,
                             String ingestionStatus, String sectionKey, float[] embedding) {
        return insertChunk(filingTicker, filingType, filingDate, ingestionStatus, sectionKey, embedding, null);
    }

    private Long insertChunk(String filingTicker, String filingType, String filingDate,
                             String ingestionStatus, String sectionKey, float[] embedding, String content) {
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
                .addValue("content", content == null ? "Evidence for " + accession : content)
                .addValue("embedding", embedding == null ? null : Arrays.toString(embedding));
        return jdbcTemplate.queryForObject("""
                INSERT INTO sec_filing_chunks(filing_id,chunk_index,section_key,section_title,content,embedding)
                VALUES (:filingId,0,:sectionKey,'Section title',:content,CAST(:embedding AS vector)) RETURNING id
                """, parameters, Long.class);
    }
}

package project.stockrecommendationengine.rag.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalFilter;

import java.time.LocalDate;
import java.util.List;
import java.util.StringJoiner;

@Repository
@RequiredArgsConstructor
public class FilingRetrievalRepository {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<RetrievedFilingChunk> findSimilarChunks(
            float[] queryEmbedding,
            FilingRetrievalFilter retrievalFilter,
            int candidateCount
    ) {
        MapSqlParameterSource queryParameters = new MapSqlParameterSource()
                .addValue("ticker", retrievalFilter.ticker())
                .addValue("latestFilingsOnly", retrievalFilter.latestFilingsOnly())
                .addValue("queryEmbedding", serializeEmbedding(queryEmbedding))
                .addValue("candidateCount", candidateCount);

        StringBuilder filingConditions = new StringBuilder("""
                WHERE ticker = :ticker AND ingestion_status = 'EMBEDDED'
                """);
        if (!retrievalFilter.filingTypes().isEmpty()) {
            filingConditions.append(" AND filing_type IN (:filingTypes)");
            queryParameters.addValue("filingTypes", retrievalFilter.filingTypes());
        }
        if (retrievalFilter.filingDateFrom() != null) {
            filingConditions.append(" AND filing_date >= :filingDateFrom");
            queryParameters.addValue("filingDateFrom", retrievalFilter.filingDateFrom());
        }
        if (retrievalFilter.filingDateTo() != null) {
            filingConditions.append(" AND filing_date <= :filingDateTo");
            queryParameters.addValue("filingDateTo", retrievalFilter.filingDateTo());
        }
        String sectionCondition = "";
        if (!retrievalFilter.sectionKeys().isEmpty()) {
            sectionCondition = " AND filing_chunk.section_key IN (:sectionKeys)";
            queryParameters.addValue("sectionKeys", retrievalFilter.sectionKeys());
        }

        // Materialize eligible chunks before sorting by distance: exact search remains
        // the baseline even if an approximate index is added to the underlying table.
        String retrievalSql = """
                WITH eligible_filings AS (
                    SELECT *, row_number() OVER (
                        PARTITION BY filing_type ORDER BY filing_date DESC, accession_no DESC, id DESC
                    ) AS filing_rank
                    FROM public.sec_filings
                    %s
                ), eligible_chunks AS MATERIALIZED (
                    SELECT filing_chunk.id AS chunk_id, filing.id AS filing_id,
                           filing.ticker, filing.cik, filing.accession_no, filing.filing_type,
                           filing.filing_date, filing.report_date, filing.source_url,
                           filing_chunk.section_key, filing_chunk.section_title,
                           filing_chunk.chunk_index, filing_chunk.content, filing_chunk.embedding
                    FROM eligible_filings filing
                    JOIN public.sec_filing_chunks filing_chunk ON filing_chunk.filing_id = filing.id
                    WHERE (:latestFilingsOnly = FALSE OR filing.filing_rank = 1)
                      AND filing_chunk.embedding IS NOT NULL
                      AND vector_norm(filing_chunk.embedding) > 0
                      %s
                )
                SELECT *, 1 - (embedding <=> CAST(:queryEmbedding AS vector)) AS similarity_score
                FROM eligible_chunks
                ORDER BY embedding <=> CAST(:queryEmbedding AS vector), chunk_id
                LIMIT :candidateCount
                """.formatted(filingConditions, sectionCondition);

        return jdbcTemplate.query(retrievalSql, queryParameters, (resultSet, rowNumber) ->
                new RetrievedFilingChunk(
                        resultSet.getLong("chunk_id"), resultSet.getLong("filing_id"),
                        resultSet.getString("ticker"), resultSet.getString("cik"),
                        resultSet.getString("accession_no"), resultSet.getString("filing_type"),
                        resultSet.getObject("filing_date", LocalDate.class),
                        resultSet.getObject("report_date", LocalDate.class),
                        resultSet.getString("section_key"), resultSet.getString("section_title"),
                        resultSet.getInt("chunk_index"), resultSet.getString("content"),
                        resultSet.getString("source_url"), resultSet.getDouble("similarity_score")));
    }

    private String serializeEmbedding(float[] queryEmbedding) {
        if (queryEmbedding == null || queryEmbedding.length != 1536) {
            throw new IllegalStateException("Query embedding must contain 1536 dimensions");
        }
        boolean hasNonzeroComponent = false;
        StringJoiner vectorLiteral = new StringJoiner(",", "[", "]");
        for (float component : queryEmbedding) {
            if (!Float.isFinite(component)) {
                throw new IllegalStateException("Query embedding contains a non-finite value");
            }
            hasNonzeroComponent |= component != 0;
            vectorLiteral.add(Float.toString(component));
        }
        if (!hasNonzeroComponent) {
            throw new IllegalStateException("Query embedding must not be a zero vector");
        }
        return vectorLiteral.toString();
    }
}

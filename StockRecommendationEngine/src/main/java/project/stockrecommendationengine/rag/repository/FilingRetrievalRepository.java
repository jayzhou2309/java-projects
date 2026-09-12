package project.stockrecommendationengine.rag.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalFilter;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Repository
@RequiredArgsConstructor
public class FilingRetrievalRepository {
    /**
     * A run of letters or digits; a comma or period is kept only when digits sit on both sides of it, so
     * "64,377" and "40.4" survive as one token while "2025," and "U.S." split on the punctuation.
     */
    private static final Pattern KEYWORD_TOKEN = Pattern.compile(
            "[\\p{L}\\p{Nd}]+(?:(?<=\\p{Nd})[.,](?=\\p{Nd})\\p{Nd}+)*");

    /** A {@link #KEYWORD_TOKEN} made only of digits and their inner separators: a figure such as 215,938 or 40.4, or a year. */
    private static final Pattern NUMERIC_TOKEN = Pattern.compile("\\p{Nd}+(?:[.,]\\p{Nd}+)*");

    /** A four-digit token in this inclusive range is a year, which matches most chunks and so never makes a figure query alone. */
    private static final int FIRST_YEAR = 1900;
    private static final int LAST_YEAR = 2100;

    /**
     * PostgreSQL's english text-search stopwords (tsearch_data/english.stop), so a query made only of them
     * yields an empty term string and the caller skips the keyword path instead of sending a tsquery that
     * PostgreSQL reduces to nothing (with a NOTICE).
     */
    private static final Set<String> ENGLISH_STOPWORDS = Set.of(
            "i", "me", "my", "myself", "we", "our", "ours", "ourselves", "you", "your", "yours", "yourself",
            "yourselves", "he", "him", "his", "himself", "she", "her", "hers", "herself", "it", "its", "itself",
            "they", "them", "their", "theirs", "themselves", "what", "which", "who", "whom", "this", "that",
            "these", "those", "am", "is", "are", "was", "were", "be", "been", "being", "have", "has", "had",
            "having", "do", "does", "did", "doing", "a", "an", "the", "and", "but", "if", "or", "because", "as",
            "until", "while", "of", "at", "by", "for", "with", "about", "against", "between", "into", "through",
            "during", "before", "after", "above", "below", "to", "from", "up", "down", "in", "out", "on", "off",
            "over", "under", "again", "further", "then", "once", "here", "there", "when", "where", "why", "how",
            "all", "any", "both", "each", "few", "more", "most", "other", "some", "such", "no", "nor", "not",
            "only", "own", "same", "so", "than", "too", "very", "s", "t", "can", "will", "just", "don", "should",
            "now");

    private static final RowMapper<RetrievedFilingChunk> CHUNK_ROW_MAPPER = (resultSet, rowNumber) ->
            new RetrievedFilingChunk(
                    resultSet.getLong("chunk_id"), resultSet.getLong("filing_id"),
                    resultSet.getString("ticker"), resultSet.getString("cik"),
                    resultSet.getString("accession_no"), resultSet.getString("filing_type"),
                    resultSet.getObject("filing_date", LocalDate.class),
                    resultSet.getObject("report_date", LocalDate.class),
                    resultSet.getString("section_key"), resultSet.getString("section_title"),
                    resultSet.getInt("chunk_index"), resultSet.getString("content"),
                    resultSet.getString("source_url"), resultSet.getDouble("similarity_score"));

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<RetrievedFilingChunk> findSimilarChunks(
            float[] queryEmbedding,
            FilingRetrievalFilter retrievalFilter,
            int candidateCount
    ) {
        MapSqlParameterSource queryParameters = new MapSqlParameterSource()
                .addValue("queryEmbedding", serializeEmbedding(queryEmbedding))
                .addValue("candidateCount", candidateCount);

        // Materialize eligible chunks before sorting by distance: exact search remains
        // the baseline even if an approximate index is added to the underlying table.
        String retrievalSql = eligibleChunksCte(retrievalFilter, queryParameters, true) + """
                SELECT *, 1 - (embedding <=> CAST(:queryEmbedding AS vector)) AS similarity_score
                FROM eligible_chunks
                ORDER BY embedding <=> CAST(:queryEmbedding AS vector), chunk_id
                LIMIT :candidateCount
                """;

        return jdbcTemplate.query(retrievalSql, queryParameters, CHUNK_ROW_MAPPER);
    }

    /**
     * Full-text search over the same eligible pool as {@link #findSimilarChunks}: chunks whose generated
     * {@code content_tsv} matches the OR-join of the query's terms (see {@link #keywordTerms}), ordered by
     * {@code ts_rank_cd} descending, then cosine similarity to {@code queryEmbedding} descending, then chunk
     * id. Every row carries that cosine similarity as {@code similarityScore}, so the score keeps its meaning
     * whichever path found the chunk. An empty term string means no keyword search: the method returns an
     * empty list without touching the database.
     *
     * <p>Numeric tokens are sent as written ({@code '64,377'}, {@code '40.4'}): PostgreSQL's english parser
     * splits a comma-separated figure into adjacent lexemes ({@code '64' <-> '377'} in the query,
     * {@code '64':n '377':n+1} in the stored vector), so the phrase matches exactly the figure and not a chunk
     * that merely contains both numbers apart; a decimal such as {@code 40.4} stays one lexeme. Verified on
     * PostgreSQL 16, so no digit-only variants are added.
     */
    public List<RetrievedFilingChunk> findKeywordChunks(
            String query,
            float[] queryEmbedding,
            FilingRetrievalFilter retrievalFilter,
            int candidateCount
    ) {
        return findTextSearchChunks(keywordTerms(query), queryEmbedding, retrievalFilter, candidateCount);
    }

    /**
     * The figure leg: full-text search over the same eligible pool for chunks matching the AND-join of the
     * query's numeric tokens (see {@link #figureTerms}), ordered and scored exactly as {@link #findKeywordChunks}
     * orders and scores its rows. A chunk must contain every figure to appear, so the leg singles out the
     * passage holding a rare number that the OR-ranked keyword leg buries under common words. An empty term
     * string (no figure, or years only) means no figure search: the method returns an empty list without
     * touching the database.
     */
    public List<RetrievedFilingChunk> findFigureChunks(
            String query,
            float[] queryEmbedding,
            FilingRetrievalFilter retrievalFilter,
            int candidateCount
    ) {
        return findTextSearchChunks(figureTerms(query), queryEmbedding, retrievalFilter, candidateCount);
    }

    private List<RetrievedFilingChunk> findTextSearchChunks(
            String terms,
            float[] queryEmbedding,
            FilingRetrievalFilter retrievalFilter,
            int candidateCount
    ) {
        String serializedEmbedding = serializeEmbedding(queryEmbedding);
        if (terms.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource queryParameters = new MapSqlParameterSource()
                .addValue("queryEmbedding", serializedEmbedding)
                .addValue("terms", terms)
                .addValue("candidateCount", candidateCount);

        // Not materialized: the planner may push the @@ predicate down to the GIN index.
        String keywordSql = eligibleChunksCte(retrievalFilter, queryParameters, false) + """
                SELECT *, 1 - (embedding <=> CAST(:queryEmbedding AS vector)) AS similarity_score,
                       ts_rank_cd(content_tsv, to_tsquery('english', :terms)) AS keyword_rank
                FROM eligible_chunks
                WHERE content_tsv @@ to_tsquery('english', :terms)
                ORDER BY keyword_rank DESC, similarity_score DESC, chunk_id
                LIMIT :candidateCount
                """;

        return jdbcTemplate.query(keywordSql, queryParameters, CHUNK_ROW_MAPPER);
    }

    /**
     * Builds the parameter value for {@code to_tsquery('english', :terms)} from free text: the distinct
     * case-folded tokens of length 2 or more (letters and digits, with commas and periods kept between
     * digits), in order of first appearance, minus PostgreSQL's english stopwords, each wrapped in single
     * quotes (embedded quotes doubled) and joined with {@code " | "}. Returns an empty string when nothing
     * remains, which callers treat as "no keyword search". Public so the retrieval service can decide whether
     * the keyword path has anything to search for before calling {@link #findKeywordChunks}.
     */
    public static String keywordTerms(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = KEYWORD_TOKEN.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 2 && !ENGLISH_STOPWORDS.contains(token)) {
                tokens.add(token);
            }
        }
        StringJoiner termJoiner = new StringJoiner(" | ");
        for (String token : tokens) {
            termJoiner.add(quoteTerm(token));
        }
        return termJoiner.toString();
    }

    /**
     * Builds the parameter value for the figure leg's {@code to_tsquery('english', :terms)}: the numeric tokens
     * among those {@link #keywordTerms} keeps (digits with commas and periods kept between digits, length 2 or
     * more, distinct, in order of first appearance; {@code 65%} yields {@code 65} and {@code $40.4} yields
     * {@code 40.4}), quoted the same way and joined with {@code " & "}, so a chunk must contain every figure to
     * match. A four-digit token from 1900 to 2100 is a year: years ride along when another figure is present but
     * never make a figure query on their own, since a year matches most chunks. Returns an empty string when no
     * figure remains, which callers treat as "no figure search".
     */
    public static String figureTerms(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        Set<String> tokens = new LinkedHashSet<>();
        boolean hasFigureBesidesYears = false;
        Matcher matcher = KEYWORD_TOKEN.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 2 && NUMERIC_TOKEN.matcher(token).matches()) {
                tokens.add(token);
                hasFigureBesidesYears |= !isYear(token);
            }
        }
        if (!hasFigureBesidesYears) {
            return "";
        }
        StringJoiner termJoiner = new StringJoiner(" & ");
        for (String token : tokens) {
            termJoiner.add(quoteTerm(token));
        }
        return termJoiner.toString();
    }

    private static boolean isYear(String token) {
        if (token.length() != 4 || !token.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int value = Integer.parseInt(token);
        return value >= FIRST_YEAR && value <= LAST_YEAR;
    }

    private static String quoteTerm(String token) {
        return "'" + token.replace("'", "''") + "'";
    }

    /**
     * The shared eligibility pool: filings of the ticker with EMBEDDED status, the optional type and
     * inclusive date filters, the latest-filing-per-type policy, the optional section filter, and only chunks
     * with a usable embedding. Both retrieval paths draw from this CTE so their candidate sets agree. The
     * generated {@code content_tsv} column is projected only for the keyword path ({@code materialized}
     * false): the vector path never reads it and materialising it would copy about 2 KB per eligible chunk.
     */
    private String eligibleChunksCte(FilingRetrievalFilter retrievalFilter,
                                     MapSqlParameterSource queryParameters, boolean materialized) {
        queryParameters.addValue("ticker", retrievalFilter.ticker())
                .addValue("latestFilingsOnly", retrievalFilter.latestFilingsOnly());

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

        return """
                WITH eligible_filings AS (
                    SELECT *, row_number() OVER (
                        PARTITION BY filing_type ORDER BY filing_date DESC, accession_no DESC, id DESC
                    ) AS filing_rank
                    FROM public.sec_filings
                    %s
                ), eligible_chunks AS %s (
                    SELECT filing_chunk.id AS chunk_id, filing.id AS filing_id,
                           filing.ticker, filing.cik, filing.accession_no, filing.filing_type,
                           filing.filing_date, filing.report_date, filing.source_url,
                           filing_chunk.section_key, filing_chunk.section_title,
                           filing_chunk.chunk_index, filing_chunk.content, filing_chunk.embedding%s
                    FROM eligible_filings filing
                    JOIN public.sec_filing_chunks filing_chunk ON filing_chunk.filing_id = filing.id
                    WHERE (:latestFilingsOnly = FALSE OR filing.filing_rank = 1)
                      AND filing_chunk.embedding IS NOT NULL
                      AND vector_norm(filing_chunk.embedding) > 0
                      %s
                )
                """.formatted(filingConditions, materialized ? "MATERIALIZED" : "NOT MATERIALIZED",
                        materialized ? "" : ", filing_chunk.content_tsv", sectionCondition);
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

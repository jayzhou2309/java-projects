package project.stockrecommendationengine.rag.ingestion;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * How parsed filing sections are cut into stored chunks ({@link FilingChunker}), and the processing version that names that cut.
 * Changing a value here changes only what a later ingestion or rebuild stores; filings already stored keep their chunks and their
 * recorded {@code processing_version} (RAG.md, FilingIngestionProperties). Plan {@code 2026-09-17-chunk-size.md}, Milestone 2.
 */
@Component
@ConfigurationProperties(prefix = "rag.ingestion")
@Validated
@Getter
@Setter
public class FilingIngestionProperties {
    /** The part of the processing version that names the section parser and embedding context rule; the chunk size and overlap follow it. */
    public static final String PROCESSING_VERSION_PREFIX = "sections-v2-context-v2";

    /** Longest chunk in characters; a section longer than this is cut at a paragraph, sentence, or word boundary in the second half of the window. */
    @Min(100) @Max(20000)
    private int chunkMaxChars = 4000;

    /** Characters the next chunk of a section starts before the end of the previous one; 0 to half of {@code chunk-max-chars}. */
    @Min(0) @Max(10000)
    private int chunkOverlapChars = 500;

    @AssertTrue(message = "rag.ingestion.chunk-overlap-chars must be at most half of rag.ingestion.chunk-max-chars")
    public boolean isChunkOverlapAtMostHalfOfChunkMaxChars() {
        return chunkOverlapChars <= chunkMaxChars / 2;
    }

    /**
     * The processing version a filing stored under these settings records, for example {@code sections-v2-context-v2-chunk4000-500}: the
     * prefix, then {@code -chunk<chunk-max-chars>-<chunk-overlap-chars>}, so {@code sec_filings.processing_version} and
     * {@code filing_rebuild_runs.processing_version} distinguish stores cut at different sizes.
     */
    public String processingVersion() {
        return PROCESSING_VERSION_PREFIX + "-chunk" + chunkMaxChars + "-" + chunkOverlapChars;
    }
}

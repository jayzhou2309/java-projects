package project.stockrecommendationengine.rag.retrieval;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "rag.retrieval")
@Validated
@Getter
@Setter
public class FilingRetrievalProperties {
    @Min(1) @Max(20)
    private int defaultTopK = 5;

    @Min(20) @Max(200)
    private int candidateCount = 40;

    private boolean latestFilingsOnly = true;
    private boolean rerankingEnabled = false;

    /**
     * Fuse full-text keyword candidates with the vector candidates (reciprocal rank fusion); a request's hybrid field
     * overrides per call. On by default since the 2026-09-12 measurement (RAG.md, Hybrid Retrieval: snapshots 35 and 34,
     * hit@5 0.600000 to 0.633333 with no ticker's hit@5 lower).
     */
    private boolean hybridEnabled = true;

    /** Keyword candidates fetched per query when the hybrid path runs. */
    @Min(20) @Max(200)
    private int keywordCandidateCount = 40;

    /** The k in reciprocal rank fusion's 1 / (k + rank). */
    @Min(1) @Max(1000)
    private int rrfK = 60;
}

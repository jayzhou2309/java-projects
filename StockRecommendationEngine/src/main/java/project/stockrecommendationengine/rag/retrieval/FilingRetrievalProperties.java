package project.stockrecommendationengine.rag.retrieval;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
     * How many of the fused, diversified candidates the reranker receives when reranking runs: the first
     * max(rerankCandidates, topK), so a topK above this value is never cut short; the rest are dropped. With reranking off the
     * topK cut is taken from the full fused list as before.
     */
    @Min(5) @Max(40)
    private int rerankCandidates = 20;

    /** Longest wait for the reranker, in milliseconds; past it retrieval keeps the fused order and logs a WARN. */
    @Min(100) @Max(60000)
    private long rerankTimeoutMs = 2000;

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

    /** Weight of the vector ranking in reciprocal rank fusion: a chunk at rank r in it scores weight / (k + r). */
    @DecimalMin("0.0") @DecimalMax("10.0")
    private double rrfVectorWeight = 1.0;

    /**
     * Weight of the keyword ranking in reciprocal rank fusion. 0.5 since the 2026-09-12 fusion tuning (RAG.md, Fusion
     * tuning: snapshot 51 against 34 and 35 keeps hit@5 0.633333 with no ticker lower, lifts MRR 0.436667 to 0.463373, and
     * returns every FIGURE question that either reference had in the top 5, msft-04 included, to the top 5).
     */
    @DecimalMin("0.0") @DecimalMax("10.0")
    private double rrfKeywordWeight = 0.5;

    /**
     * Weight of the figure ranking (chunks containing every numeric token of the query; {@code figureTerms}); 0 leaves
     * that leg off. 1.0 since the 2026-09-12 fusion tuning (snapshot 51, where set v1 carried no figure and the leg never
     * ran); kept after measurement on set v2 (2026-09-13): snapshot 69 at 1.0 has hit@5 0.785714 and MRR 0.655187, against
     * 0.738095 and 0.533362 with the leg off (snapshot 71), and no other figure weight qualified with a higher MRR.
     */
    @DecimalMin("0.0") @DecimalMax("10.0")
    private double rrfFigureWeight = 1.0;
}

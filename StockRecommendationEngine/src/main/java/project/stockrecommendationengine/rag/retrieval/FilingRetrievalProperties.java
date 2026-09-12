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
     * that leg off. 1.0 since the 2026-09-12 fusion tuning (snapshot 51): the leg runs only for queries that carry a
     * figure, which none of the evaluation set's questions do, so it is active for numeric questions at no measured cost.
     */
    @DecimalMin("0.0") @DecimalMax("10.0")
    private double rrfFigureWeight = 1.0;
}

package project.stockrecommendationengine.rag.retrieval;

/**
 * The second reranker model's {@link FilingReranker}: exactly {@link CrossEncoderReranker}'s ordering, validation, and scoring log
 * line over an {@link OnnxCrossEncoderScorer} loaded from this model's files, as its own class only so that evaluation snapshots
 * record {@code properties.reranker} {@code GteReranker} (the current model records {@code CrossEncoderReranker}), beside
 * {@code properties.rerankerVersion}, this model's SHA-256 prefix ({@code c6d3226502ad}; the current model's is {@code 5d3e70fd0c9f}).
 */
public class GteReranker extends CrossEncoderReranker {
    public GteReranker(PairScorer scorer, String version) {
        super(scorer, version);
    }
}

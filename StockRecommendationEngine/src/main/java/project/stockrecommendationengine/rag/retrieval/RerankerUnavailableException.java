package project.stockrecommendationengine.rag.retrieval;

/** A call asked for reranking ({@code rerank: true}) but no {@link FilingReranker} bean is configured; a client error. */
public class RerankerUnavailableException extends RuntimeException {
    public RerankerUnavailableException() {
        super("rerank was requested but no FilingReranker is configured; omit rerank or set it to false");
    }
}

package project.stockrecommendationengine.rag.ingestion;

public class UnknownTickerException extends IllegalArgumentException {
    public UnknownTickerException(String ticker) {
        super("Unknown ticker: " + ticker);
    }
}

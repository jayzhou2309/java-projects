package project.stockrecommendationengine.rag.retrieval;

import java.io.IOException;
import java.nio.file.Path;

/**
 * {@link PassageTokenizer} for the second reranker model: {@link CrossEncoderPassageTokenizer} over this model's verified
 * {@code tokenizer.json} (the same single-sequence tokenizer the scorer encodes with), plus this model's configured
 * {@code max-length}, so the evidence report computes W with the loaded model's window rather than
 * {@code rag.retrieval.cross-encoder.max-length}. Created only by {@link GteRerankerConfiguration}; retrieval and scoring never use it.
 */
final class GteRerankerPassageTokenizer implements PassageTokenizer {
    static final String MAX_LENGTH_SOURCE = "current configuration " + GteRerankerProperties.PREFIX + ".max-length (snapshots do not record it)";

    private final CrossEncoderPassageTokenizer delegate;
    private final int maxLength;

    GteRerankerPassageTokenizer(Path tokenizerPath, String modelVersion, int maxLength) throws IOException {
        this.delegate = new CrossEncoderPassageTokenizer(tokenizerPath, modelVersion);
        this.maxLength = maxLength;
    }

    @Override
    public Tokens tokenize(String text) {
        return delegate.tokenize(text);
    }

    @Override
    public String modelVersion() {
        return delegate.modelVersion();
    }

    @Override
    public MaxLength maxLength() {
        return new MaxLength(maxLength, MAX_LENGTH_SOURCE);
    }

    @Override
    public void close() {
        delegate.close();
    }
}

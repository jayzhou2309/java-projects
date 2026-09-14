package project.stockrecommendationengine.rag.retrieval;

/**
 * The cross-encoder's tokenizer as a source of token positions for the evaluation evidence report (RAG.md, Retrieval Evaluation,
 * Evidence report): one text at a time, exactly as {@link OnnxCrossEncoderScorer} tokenizes a query or a passage (no special tokens,
 * no truncation, no padding), with every token's character span. Created only with the cross-encoder
 * ({@link CrossEncoderConfiguration}); retrieval and scoring never use it. Callers bound the text first
 * ({@link CrossEncoderTokenPositions#bound}), as the scorer does.
 */
public interface PassageTokenizer extends AutoCloseable {
    /** Tokens of {@code text} in order; token {@code i} covers UTF-16 units {@code [starts[i], ends[i])} of {@code text}. */
    Tokens tokenize(String text);

    /**
     * The loaded model's version, the value an evaluation snapshot records as {@code properties.rerankerVersion} (the first 12 hex
     * characters of the model file's SHA-256).
     */
    String modelVersion();

    /** Token character spans in token order, as UTF-16 offsets into the tokenized text, end exclusive. */
    record Tokens(int[] starts, int[] ends) {
        public Tokens {
            if (starts.length != ends.length) throw new IllegalArgumentException("starts and ends differ in length");
        }

        public int count() {
            return starts.length;
        }
    }

    /** Releases native resources; the default does nothing. */
    @Override
    default void close() {
    }
}

package project.stockrecommendationengine.rag.retrieval;

/**
 * How the cross-encoder scores a chunk longer than one model window ({@code rag.retrieval.cross-encoder.passage-scoring}).
 * The window is the token budget left for the chunk beside the query ({@code max-length} - 3 - the query tokens kept); a chunk
 * that fits it is scored the same under both modes.
 */
public enum PassageScoring {
    /** The chunk's first window only, the behaviour before windowed scoring: an answer past the window is never seen. */
    HEAD("head"),
    /** The maximum logit over sliding windows that together cover the chunk ({@link CrossEncoderPairAssembler#windowStarts}). */
    MAX_WINDOW("max-window");

    private final String label;

    PassageScoring(String label) {
        this.label = label;
    }

    /** The property value: {@code head} or {@code max-window}. */
    public String label() {
        return label;
    }
}

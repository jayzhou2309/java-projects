package project.stockrecommendationengine.rag.retrieval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The second reranker model's files after verification, as {@link CrossEncoderModelFiles} does for the current model but naming
 * the {@code rag.retrieval.gte-reranker} properties: both files exist as regular files, the model's SHA-256 equals
 * {@code model-sha256} (required), and the tokenizer's equals {@code tokenizer-sha256} when that is set. Uses no ONNX Runtime or
 * tokenizer class, so a bad file fails startup before any native library loads.
 */
public record GteRerankerModelFiles(Path modelPath, Path tokenizerPath, String modelSha256) {
    /** The first 12 hex characters of the model file's SHA-256, recorded as {@code properties.rerankerVersion}. */
    public String version() {
        return modelSha256.substring(0, CrossEncoderModelFiles.VERSION_LENGTH);
    }

    /** Verifies the configured files; an {@link IllegalStateException} naming the path and property on any failure. */
    public static GteRerankerModelFiles verify(GteRerankerProperties properties) {
        Path model = requireFile(properties.getModelPath(), "model-path");
        Path tokenizer = requireFile(properties.getTokenizerPath(), "tokenizer-path");
        if (properties.getModelSha256() == null || properties.getModelSha256().isBlank()) {
            throw new IllegalStateException(GteRerankerProperties.PREFIX + ".model-sha256 is required when the gte reranker is enabled (model file "
                    + model + ")");
        }
        String modelSha = requireChecksum(model, properties.getModelSha256(), "model-sha256");
        if (properties.getTokenizerSha256() != null && !properties.getTokenizerSha256().isBlank()) {
            requireChecksum(tokenizer, properties.getTokenizerSha256(), "tokenizer-sha256");
        }
        return new GteRerankerModelFiles(model, tokenizer, modelSha);
    }

    private static Path requireFile(String configured, String property) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(GteRerankerProperties.PREFIX + "." + property + " is required when the gte reranker is enabled");
        }
        Path path = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("GTE reranker file not found: " + path + " (" + GteRerankerProperties.PREFIX + "." + property + ")");
        }
        return path;
    }

    private static String requireChecksum(Path path, String expected, String property) {
        String actual = CrossEncoderModelFiles.sha256(path);
        if (!actual.equals(expected.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("GTE reranker file checksum mismatch: " + path + " has SHA-256 " + actual + " but "
                    + GteRerankerProperties.PREFIX + "." + property + " is " + expected.trim());
        }
        return actual;
    }
}

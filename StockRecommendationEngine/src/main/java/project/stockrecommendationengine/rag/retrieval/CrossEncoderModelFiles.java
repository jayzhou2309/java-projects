package project.stockrecommendationengine.rag.retrieval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The cross-encoder's model and tokenizer files after verification: both exist as regular files and the model's SHA-256
 * equals {@code model-sha256} (the tokenizer's equals {@code tokenizer-sha256} when that is set). Uses no ONNX Runtime or
 * tokenizer class, so a bad file fails startup before any native library loads.
 */
public record CrossEncoderModelFiles(Path modelPath, Path tokenizerPath, String modelSha256) {
    /** Hex characters of the model's SHA-256 recorded as the reranker version. */
    static final int VERSION_LENGTH = 12;

    /** The first 12 hex characters of the model file's SHA-256. */
    public String version() {
        return modelSha256.substring(0, VERSION_LENGTH);
    }

    /** Verifies the configured files; an {@link IllegalStateException} naming the path on a missing file or a checksum mismatch. */
    public static CrossEncoderModelFiles verify(CrossEncoderProperties properties) {
        Path model = requireFile(properties.getModelPath(), "model-path");
        Path tokenizer = requireFile(properties.getTokenizerPath(), "tokenizer-path");
        if (properties.getModelSha256() == null || properties.getModelSha256().isBlank()) {
            throw new IllegalStateException("rag.retrieval.cross-encoder.model-sha256 is required when the cross-encoder is enabled (model file "
                    + model + ")");
        }
        String modelSha = requireChecksum(model, properties.getModelSha256(), "model-sha256");
        if (properties.getTokenizerSha256() != null && !properties.getTokenizerSha256().isBlank()) {
            requireChecksum(tokenizer, properties.getTokenizerSha256(), "tokenizer-sha256");
        }
        return new CrossEncoderModelFiles(model, tokenizer, modelSha);
    }

    private static Path requireFile(String configured, String property) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("rag.retrieval.cross-encoder." + property + " is required when the cross-encoder is enabled");
        }
        Path path = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Cross-encoder file not found: " + path + " (rag.retrieval.cross-encoder." + property + ")");
        }
        return path;
    }

    private static String requireChecksum(Path path, String expected, String property) {
        String actual = sha256(path);
        if (!actual.equals(expected.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("Cross-encoder file checksum mismatch: " + path + " has SHA-256 " + actual
                    + " but rag.retrieval.cross-encoder." + property + " is " + expected.trim());
        }
        return actual;
    }

    /** Lower-case hex SHA-256 of the file. */
    static String sha256(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read cross-encoder file: " + path, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}

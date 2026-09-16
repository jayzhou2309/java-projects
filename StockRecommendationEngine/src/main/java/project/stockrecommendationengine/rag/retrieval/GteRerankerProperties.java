package project.stockrecommendationengine.rag.retrieval;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * The second reranker model ({@code Alibaba-NLP/gte-reranker-modernbert-base} on ONNX Runtime; plan
 * {@code plans/2026-09-15-reranker-ettin.md}, amendment 1), kept apart from {@link CrossEncoderProperties} so the current model's
 * keys and classes stay unchanged. {@code enabled} only creates this reranker's beans and checks its files at startup; whether
 * retrieval reranks by default stays {@code rag.retrieval.reranking-enabled}, and a request's {@code rerank} field overrides per
 * call. It cannot be enabled together with {@code rag.retrieval.cross-encoder.enabled} ({@link GteRerankerConfiguration}). The
 * window settings mean exactly what they mean for the current model (same assembler arithmetic); profile
 * {@code reranker-gte} sets the frozen comparison values.
 */
@Component
@ConfigurationProperties(prefix = "rag.retrieval.gte-reranker")
@Validated
@Getter
@Setter
public class GteRerankerProperties {
    static final String PREFIX = "rag.retrieval.gte-reranker";

    /** Create this reranker's beans, verify the model files, and load them at startup. Off by default. */
    private boolean enabled = false;

    /** The ONNX model file; a relative path resolves against the working directory. */
    private String modelPath = "models/gte-reranker-modernbert-base/model.onnx";

    /** The Hugging Face {@code tokenizer.json}; a relative path resolves against the working directory. */
    private String tokenizerPath = "models/gte-reranker-modernbert-base/tokenizer.json";

    /** Expected SHA-256 of the model file, hex; required when enabled, startup fails naming the path on a mismatch. */
    private String modelSha256;

    /** Expected SHA-256 of the tokenizer file, hex; checked when set. */
    private String tokenizerSha256;

    /** Longest (query, chunk) row in tokens, special tokens included. The comparison freezes 512, the current model's window. */
    @Min(16) @Max(512)
    private int maxLength = 512;

    /** Chunks tokenized and scored together; rows still run under the per-call attention cap. */
    @Min(1) @Max(64)
    private int batchSize = 20;

    /** {@code head} or {@code max-window}, as for the current model. */
    @NotNull
    private PassageScoring passageScoring = PassageScoring.MAX_WINDOW;

    /** Tokens shared by consecutive windows under {@code max-window}. */
    @Min(0) @Max(256)
    private int windowOverlapTokens = 64;

    /** Most windows scored per chunk under {@code max-window}, taken from its head. */
    @Min(1) @Max(16)
    private int maxWindows = 4;
}

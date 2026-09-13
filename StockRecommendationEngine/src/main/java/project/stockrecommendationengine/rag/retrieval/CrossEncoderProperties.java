package project.stockrecommendationengine.rag.retrieval;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * The local cross-encoder reranker ({@code cross-encoder/ms-marco-MiniLM-L-6-v2} on ONNX Runtime). {@code enabled} only
 * creates the reranker bean and checks its files at startup; whether retrieval uses it by default stays
 * {@code rag.retrieval.reranking-enabled}, and a request's {@code rerank} field overrides per call.
 */
@Component
@ConfigurationProperties(prefix = "rag.retrieval.cross-encoder")
@Validated
@Getter
@Setter
public class CrossEncoderProperties {
    /** Create the cross-encoder reranker bean, verify the model files, and load them at startup. Off by default. */
    private boolean enabled = false;

    /** The ONNX model file; a relative path resolves against the working directory. */
    private String modelPath = "models/cross-encoder-ms-marco-MiniLM-L-6-v2/model.onnx";

    /** The Hugging Face {@code tokenizer.json}; a relative path resolves against the working directory. */
    private String tokenizerPath = "models/cross-encoder-ms-marco-MiniLM-L-6-v2/tokenizer.json";

    /** Expected SHA-256 of the model file, hex; required when enabled, startup fails naming the path on a mismatch. */
    private String modelSha256;

    /** Expected SHA-256 of the tokenizer file, hex; checked when set. */
    private String tokenizerSha256;

    /** Longest (query, chunk) pair in tokens, special tokens included; the longer of the two is cut first. The model's window is 512. */
    @Min(16) @Max(512)
    private int maxLength = 512;

    /** Chunks tokenized and scored together; long pairs run in several ONNX Runtime calls (OnnxCrossEncoderScorer, Work per call). */
    @Min(1) @Max(64)
    private int batchSize = 20;
}

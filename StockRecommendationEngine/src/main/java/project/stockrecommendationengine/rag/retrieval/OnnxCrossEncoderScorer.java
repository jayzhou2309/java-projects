package project.stockrecommendationengine.rag.retrieval;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.util.PairList;
import ai.djl.util.Platform;
import ai.djl.util.Utils;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * A cross-encoder run on the CPU with ONNX Runtime and the model's Hugging Face tokenizer, both from local files. Each pair is
 * encoded query first and passage second with the model's special tokens; only the passage is truncated so the pair fits
 * {@code maxLength} tokens. Pairs run in batches of {@code batchSize}, padded to the longest pair in the batch, as int64
 * {@code input_ids}, {@code attention_mask}, and (when the model declares it) {@code token_type_ids}; the score is the single
 * logit per pair. Both native libraries come from the dependency jars: the tokenizer's library for the current platform must be
 * bundled on the classpath, otherwise construction fails rather than letting DJL download one.
 * <p>
 * Scoring may run on several threads at once. {@link #close()} waits for calls in flight (a call the retrieval service has
 * already abandoned on timeout keeps running natively) before releasing the native session, so a shutdown never frees a
 * session under a running inference; a call after close throws {@link IllegalStateException}.
 */
@Slf4j
public final class OnnxCrossEncoderScorer implements PairScorer {
    private static final String INPUT_IDS = "input_ids";
    private static final String ATTENTION_MASK = "attention_mask";
    private static final String TOKEN_TYPE_IDS = "token_type_ids";

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final int batchSize;
    private final String outputName;
    private final boolean usesTokenTypeIds;
    /** Read-held by every scoring call, write-held by close. */
    private final ReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private boolean closed;
    /** Longest wait in close for scoring calls in flight. */
    private static final long CLOSE_WAIT_SECONDS = 30;

    public OnnxCrossEncoderScorer(Path modelPath, Path tokenizerPath, int maxLength, int batchSize) {
        this.batchSize = batchSize;
        requireBundledTokenizerLibrary();
        this.environment = OrtEnvironment.getEnvironment();
        OrtSession createdSession = null;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            createdSession = environment.createSession(modelPath.toString(), options);
            if (!createdSession.getInputNames().containsAll(List.of(INPUT_IDS, ATTENTION_MASK)) || createdSession.getNumOutputs() < 1) {
                throw new IllegalStateException("Cross-encoder model " + modelPath + " does not declare input_ids and attention_mask inputs and an output: "
                        + describe(createdSession));
            }
            this.session = createdSession;
            this.usesTokenTypeIds = createdSession.getInputNames().contains(TOKEN_TYPE_IDS);
            this.outputName = createdSession.getOutputNames().iterator().next();
            this.tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerPath)
                    .optAddSpecialTokens(true)
                    .optTruncateSecondOnly()
                    .optMaxLength(maxLength)
                    .optPadding(false)
                    .build();
        } catch (OrtException | IOException | RuntimeException failure) {
            closeQuietly(createdSession);
            throw new IllegalStateException("Cannot load the cross-encoder from " + modelPath + " and " + tokenizerPath + ": "
                    + failure.getClass().getSimpleName(), failure);
        }
        log.info("Cross-encoder loaded: model={}, maxLength={}, batchSize={}, {}", modelPath, maxLength, batchSize, describe(session));
    }

    /** The session's input and output names with their declared types and shapes, as reported by ONNX Runtime. */
    public String describe() {
        return describe(session);
    }

    private static String describe(OrtSession session) {
        try {
            return "inputs=" + nodes(session.getInputInfo()) + ", outputs=" + nodes(session.getOutputInfo());
        } catch (OrtException e) {
            return "metadata unavailable (" + e.getClass().getSimpleName() + ")";
        }
    }

    private static String nodes(Map<String, NodeInfo> nodes) {
        return nodes.values().stream().map(node -> node.getName() + " " + node.getInfo()).collect(Collectors.joining("; ", "[", "]"));
    }

    @Override
    public float[] score(String query, List<String> passages) {
        lifecycle.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("Cross-encoder is closed");
            float[] scores = new float[passages.size()];
            for (int start = 0; start < passages.size(); start += batchSize) {
                List<String> batch = passages.subList(start, Math.min(passages.size(), start + batchSize));
                float[] batchScores = scoreBatch(query, batch);
                System.arraycopy(batchScores, 0, scores, start, batchScores.length);
            }
            return scores;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private float[] scoreBatch(String query, List<String> passages) {
        PairList<String, String> pairs = new PairList<>(passages.size());
        for (String passage : passages) pairs.add(query, passage);
        Encoding[] encodings = tokenizer.batchEncode(pairs);
        int length = 0;
        for (Encoding encoding : encodings) length = Math.max(length, encoding.getIds().length);
        long[][] ids = new long[encodings.length][length];
        long[][] mask = new long[encodings.length][length];
        long[][] types = new long[encodings.length][length];
        for (int row = 0; row < encodings.length; row++) {
            long[] rowIds = encodings[row].getIds();
            System.arraycopy(rowIds, 0, ids[row], 0, rowIds.length);
            System.arraycopy(encodings[row].getAttentionMask(), 0, mask[row], 0, rowIds.length);
            System.arraycopy(encodings[row].getTypeIds(), 0, types[row], 0, rowIds.length);
        }
        List<OnnxTensor> tensors = new ArrayList<>();
        try {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put(INPUT_IDS, track(tensors, OnnxTensor.createTensor(environment, ids)));
            inputs.put(ATTENTION_MASK, track(tensors, OnnxTensor.createTensor(environment, mask)));
            if (usesTokenTypeIds) inputs.put(TOKEN_TYPE_IDS, track(tensors, OnnxTensor.createTensor(environment, types)));
            try (OrtSession.Result result = session.run(inputs)) {
                OnnxValue output = result.get(outputName).orElseThrow(() -> new IllegalStateException("Cross-encoder output missing"));
                if (!(output.getValue() instanceof float[][] logits) || logits.length != passages.size()) {
                    throw new IllegalStateException("Cross-encoder output is not float logits of shape [batch, 1]");
                }
                float[] scores = new float[logits.length];
                for (int row = 0; row < logits.length; row++) {
                    if (logits[row].length != 1) throw new IllegalStateException("Cross-encoder output is not float logits of shape [batch, 1]");
                    scores[row] = logits[row][0];
                }
                return scores;
            }
        } catch (OrtException e) {
            throw new IllegalStateException("Cross-encoder inference failed: " + e.getClass().getSimpleName(), e);
        } finally {
            tensors.forEach(OnnxTensor::close);
        }
    }

    private static OnnxTensor track(List<OnnxTensor> tensors, OnnxTensor tensor) {
        tensors.add(tensor);
        return tensor;
    }

    /**
     * Fails unless the tokenizer's native library for this platform is bundled in the tokenizers jar, so DJL extracts it from
     * the classpath and never downloads one. DJL picks a CUDA build (downloaded from its CDN) when it detects CUDA; the flavor is
     * pinned to {@code cpu} unless {@code RUST_FLAVOR} is already set, and an explicit {@code RUST_LIBRARY_PATH} (a local
     * library) skips the check.
     */
    static void requireBundledTokenizerLibrary() {
        if (Utils.getEnvOrSystemProperty("RUST_LIBRARY_PATH") != null) return;
        String flavor = Utils.getEnvOrSystemProperty("RUST_FLAVOR");
        if (flavor == null) {
            System.setProperty("RUST_FLAVOR", "cpu");
            flavor = "cpu";
        }
        Platform platform = Platform.detectPlatform("tokenizers");
        String resource = "native/lib/" + platform.getOsPrefix() + "-" + platform.getOsArch() + "/" + flavor + "/"
                + System.mapLibraryName("tokenizers");
        if (OnnxCrossEncoderScorer.class.getClassLoader().getResource(resource) == null) {
            throw new IllegalStateException("The tokenizers jar bundles no native library at " + resource
                    + "; refusing to let DJL download one at runtime");
        }
    }

    @Override
    public void close() {
        boolean locked;
        try {
            locked = lifecycle.writeLock().tryLock(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (!locked) {
            log.warn("Cross-encoder scoring still in flight after {} s; leaving the native session open", CLOSE_WAIT_SECONDS);
            return;
        }
        try {
            if (closed) return;
            closed = true;
            try {
                tokenizer.close();
            } finally {
                closeQuietly(session);
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    private static void closeQuietly(OrtSession session) {
        if (session == null) return;
        try {
            session.close();
        } catch (OrtException e) {
            log.warn("Closing the cross-encoder session failed: error={}", e.getClass().getSimpleName());
        }
    }
}

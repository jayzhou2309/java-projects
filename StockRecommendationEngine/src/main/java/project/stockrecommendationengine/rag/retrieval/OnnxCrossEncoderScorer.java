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
 * encoded query first and passage second with the model's special tokens and truncated to {@code maxLength} tokens with the
 * tokenizer's {@code longest_first} strategy, the one the reference sentence-transformers {@code CrossEncoder} uses. Pairs run
 * in batches of {@code batchSize}, padded to the longest pair in the batch, as int64
 * {@code input_ids}, {@code attention_mask}, and (when the model declares it) {@code token_type_ids}; the score is the single
 * logit per pair. Both native libraries come from the dependency jars: the tokenizer's library for the current platform must be
 * bundled on the classpath, otherwise construction fails rather than letting DJL download one.
 * <p>
 * Input bounds. The native tokenizer turns an error into a Rust panic that aborts the whole JVM (no Java exception), so every
 * call is kept to encodings the tokenizer can always produce. Query and passages are first bounded in Java by
 * {@link CrossEncoderInputBounds} (null to empty, at most 20,000 UTF-16 units each, unpaired surrogates replaced). Truncation is
 * {@code longest_first}, not {@code only_second}: with {@code only_second}, a query that alone fills {@code maxLength - 3}
 * tokens (509 one-token words at 512, with 3 special tokens for a BERT pair) leaves no passage tokens to remove, the tokenizer
 * returns {@code TruncationError::SequenceTooShort}, and DJL's JNI layer unwraps it and aborts the process. {@code longest_first} on a pair only computes two target lengths
 * (the shorter sequence keeps up to half the budget, the longer takes the rest) and cuts each sequence to its target, so it has
 * no error branch; the query is cut only when both sequences are long (more than about 254 query tokens at 512), and a query
 * cut is logged at INFO with lengths only. The option {@code optTruncation(true)} maps to {@code LONGEST_FIRST}
 * ({@code HuggingFaceTokenizer$TruncationStrategy.fromValue}); the opt-in {@code CrossEncoderForkedLiveTests} exercise the
 * boundary lengths in a child JVM so an abort fails the test with its exit code.
 * <p>
 * Network. The static initializer applies {@link DjlRuntimeDefaults} (system properties {@code OPT_OUT_TRACKING=true} and
 * {@code ai.djl.offline=true} when absent) before any DJL class is initialised, so {@code Ec2Utils.callHome} in
 * {@code HuggingFaceTokenizer.Builder.build()} returns without connecting.
 * <p>
 * Scoring may run on several threads at once. {@link #close()} waits up to 30 s for calls in flight (a call the retrieval
 * service has already abandoned on timeout keeps running natively) before releasing the native session, so a shutdown never
 * frees a session under a running inference; a call after close throws {@link IllegalStateException}. If a call is still
 * running after 30 s, close logs {@code Cross-encoder scoring still in flight after 30 s; leaving the native session open} at
 * WARN and returns without freeing anything: the JVM then exits with the session and tokenizer still allocated, and an
 * inference still running natively while the process tears down can crash at exit (a native crash report instead of a clean
 * exit code). With the input bounds above one call is at most {@code ceil(candidates / batchSize)} batches of 512-token pairs
 * (one batch at the defaults of 20 candidates and batch size 20); the worst bounded batch, a 20,000-character query with 20
 * passages of 2,000 characters, took 542 to 882 ms on the development Mac (two runs, 2026-09-13), so reaching the 30 s wait needs a
 * stalled or heavily oversubscribed machine.
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
    private final int maxLength;

    static {
        // Before any DJL class is initialised: HuggingFaceTokenizer.Builder.build() otherwise calls Ec2Utils.callHome.
        DjlRuntimeDefaults.apply();
    }

    public OnnxCrossEncoderScorer(Path modelPath, Path tokenizerPath, int maxLength, int batchSize) {
        this.batchSize = batchSize;
        this.maxLength = maxLength;
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
                    // longest_first: never returns a truncation error for a pair (class Javadoc); only_second aborts the JVM
                    // when the query alone fills the window.
                    .optTruncation(true)
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
            String boundedQuery = CrossEncoderInputBounds.bound(query);
            List<String> boundedPassages = passages.stream().map(CrossEncoderInputBounds::bound).toList();
            float[] scores = new float[passages.size()];
            int[] queryTokensKept = {Integer.MAX_VALUE};
            for (int start = 0; start < boundedPassages.size(); start += batchSize) {
                List<String> batch = boundedPassages.subList(start, Math.min(boundedPassages.size(), start + batchSize));
                float[] batchScores = scoreBatch(boundedQuery, batch, queryTokensKept);
                System.arraycopy(batchScores, 0, scores, start, batchScores.length);
            }
            logQueryTruncation(query, boundedQuery, queryTokensKept[0]);
            return scores;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Logs at INFO, lengths only, when the query was cut by the character bound or by token truncation in any pair. */
    private void logQueryTruncation(String query, String boundedQuery, int queryTokensKept) {
        if (queryTokensKept == Integer.MAX_VALUE) return;
        boolean charsCut = query != null && boundedQuery.length() < query.length();
        // longest_first keeps at least half the content budget of a query it cuts, so a shorter kept query was not cut and the
        // extra encode below is skipped for ordinary questions.
        if (!charsCut && queryTokensKept < (maxLength - 3) / 2) return;
        // The query alone, truncated at maxLength by the same tokenizer: a count of maxLength means "at least maxLength".
        int queryTokens = tokenizer.encode(boundedQuery, false, false).getIds().length;
        if (charsCut || queryTokensKept < queryTokens) {
            log.info("Cross-encoder query truncated: queryChars={}, queryCharsKept={}, queryTokens={}{}, queryTokensKept={}",
                    query == null ? 0 : query.length(), boundedQuery.length(), queryTokens, queryTokens >= maxLength ? "+" : "", queryTokensKept);
        }
    }

    private float[] scoreBatch(String query, List<String> passages, int[] queryTokensKept) {
        PairList<String, String> pairs = new PairList<>(passages.size());
        for (String passage : passages) pairs.add(query, passage);
        Encoding[] encodings = tokenizer.batchEncode(pairs);
        int length = 0;
        for (Encoding encoding : encodings) {
            length = Math.max(length, encoding.getIds().length);
            int kept = 0;
            for (long sequence : encoding.getSequenceIds()) if (sequence == 0) kept++;
            queryTokensKept[0] = Math.min(queryTokensKept[0], kept);
        }
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

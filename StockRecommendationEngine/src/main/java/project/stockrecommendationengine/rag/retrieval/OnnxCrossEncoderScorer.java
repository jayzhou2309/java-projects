package project.stockrecommendationengine.rag.retrieval;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
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
 * A cross-encoder run on the CPU with ONNX Runtime and the model's Hugging Face tokenizer, both from local files. Pairs are
 * query first and passage second, {@code [CLS] query [SEP] passage [SEP]}, cut to {@code maxLength} tokens longest first, and
 * fed as int64 {@code input_ids}, {@code attention_mask}, and (when the model declares it) {@code token_type_ids}; the score is
 * the single logit per pair. Both native libraries come from the dependency jars: the tokenizer's library for the current
 * platform must be bundled on the classpath, otherwise construction fails rather than letting DJL download one.
 * <p>
 * Tokenization. The native tokenizer never sees a pair. The query and each passage are tokenized alone by
 * {@link #singleSequenceTokenizer} (no special tokens, truncation and padding off, so no overflow encodings exist), and
 * {@link CrossEncoderPairAssembler} cuts the two id sequences longest first, adds the special tokens read from
 * {@code tokenizer.json}, and pads each ONNX Runtime call to its longest pair. Two native failure modes are thereby out of
 * reach: {@code only_second} truncation's {@code SequenceTooShort}, which DJL's JNI layer turns into a Rust panic that aborts
 * the JVM (Milestone 2), and native pair truncation's overflow pieces, which it builds for every combination of query and passage
 * pieces even when none is read, so memory grew with the product of the two lengths and an OS memory kill (exit 137) bypassed
 * the fallback (41 GB at max-length 16 for a 4,000-character CJK query with 20 passages; remediation 1). Per pair the work is now
 * linear in the input length. The assembled tensors equal the replaced native pair encoding exactly: 320 generated pairs that
 * need no truncation (English, digits, CJK, accents, punctuation, emoji) and 160 truncating pairs at 512 matched id for id
 * ({@code CrossEncoderSeparateTokenizationLiveTests}, RAG.md Input bounds).
 * <p>
 * Input bounds. Query and passages are first bounded in Java by {@link CrossEncoderInputBounds} (null to empty, at most 20,000
 * UTF-16 units each, unpaired surrogates replaced). A query cut by characters or tokens in any pair is logged at INFO with
 * lengths only.
 * <p>
 * Work per call. Passages are tokenized {@code batchSize} at a time, and each batch runs as one or more ONNX Runtime calls whose
 * rows times longest-pair width squared stays within {@link CrossEncoderPairAssembler#MAX_ATTENTION_CELLS_PER_RUN} (eight
 * 512-token pairs), because attention memory, about 88 MB per 512-token row, would otherwise reach 3.9 GB at batch size 64.
 * Measured in child JVMs under {@code /usr/bin/time -l} on the development Mac (2026-09-13, RAG.md Latency): a query and 40
 * passages of 20,000 characters take at most about 2.1 s per call with a peak process footprint under 1.0 GB at max-length 512
 * (any batch size, CJK or {@code "a "}), and at most 1.1 s and 0.38 GB at max-length 16; 20 passages of 2,000 characters at the
 * defaults take about 0.3 s. Memory adds up per concurrent call (two simultaneous heaviest calls: 1.43 GB).
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
 * exit code). With the worst measured call at about 2.1 s, reaching the 30 s wait needs a stalled or heavily oversubscribed
 * machine, or many concurrent calls queued on the CPU.
 */
@Slf4j
public final class OnnxCrossEncoderScorer implements PairScorer {
    private static final String INPUT_IDS = "input_ids";
    private static final String ATTENTION_MASK = "attention_mask";
    private static final String TOKEN_TYPE_IDS = "token_type_ids";

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final CrossEncoderPairAssembler assembler;
    private final int batchSize;
    private final String outputName;
    private final boolean usesTokenTypeIds;
    /** Read-held by every scoring call, write-held by close. */
    private final ReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private boolean closed;
    /** Longest wait in close for scoring calls in flight. */
    private static final long CLOSE_WAIT_SECONDS = 30;

    static {
        // Before any DJL class is initialised: HuggingFaceTokenizer.Builder.build() otherwise calls Ec2Utils.callHome.
        DjlRuntimeDefaults.apply();
    }

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
            this.assembler = CrossEncoderPairAssembler.fromTokenizerJson(tokenizerPath, maxLength);
            this.tokenizer = singleSequenceTokenizer(tokenizerPath);
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
            long[] queryIds = tokenIds(boundedQuery);
            float[] scores = new float[passages.size()];
            int queryTokensKept = Integer.MAX_VALUE;
            for (int start = 0; start < passages.size(); start += batchSize) {
                List<String> batch = passages.subList(start, Math.min(passages.size(), start + batchSize));
                List<long[]> passageIds = new ArrayList<>(batch.size());
                for (String passage : batch) passageIds.add(tokenIds(CrossEncoderInputBounds.bound(passage)));
                for (int from = 0; from < passageIds.size(); ) {
                    int end = assembler.runEnd(queryIds.length, passageIds, from);
                    CrossEncoderPairAssembler.Batch tensors = assembler.assemble(queryIds, passageIds.subList(from, end));
                    queryTokensKept = Math.min(queryTokensKept, tensors.queryTokensKept());
                    float[] runScores = run(tensors);
                    System.arraycopy(runScores, 0, scores, start + from, runScores.length);
                    from = end;
                }
            }
            logQueryTruncation(query, boundedQuery, queryIds.length, queryTokensKept);
            return scores;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * The tokenizer the scorer encodes with: one text at a time, no special tokens (added in Java), truncation and padding off,
     * no overflow encodings. Fails, closing it, if DJL reports any other truncation or padding strategy.
     */
    static HuggingFaceTokenizer singleSequenceTokenizer(Path tokenizerPath) throws IOException {
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerPath)
                .optAddSpecialTokens(false)
                .optWithOverflowingTokens(false)
                .optTruncation(false)
                .optPadding(false)
                .build();
        if (!"DO_NOT_TRUNCATE".equals(tokenizer.getTruncation()) || !"DO_NOT_PAD".equals(tokenizer.getPadding())) {
            String settings = tokenizer.getTruncation() + "/" + tokenizer.getPadding();
            tokenizer.close();
            throw new IllegalStateException("tokenizer truncation/padding " + settings + " is not DO_NOT_TRUNCATE/DO_NOT_PAD");
        }
        return tokenizer;
    }

    /** One text alone: no special tokens, no truncation, no padding, so no overflow encodings are ever built. */
    private long[] tokenIds(String text) {
        return tokenizer.encode(text, false, false).getIds();
    }

    /** Logs at INFO, lengths only, when the query was cut by the character bound or by token truncation in any pair. */
    private static void logQueryTruncation(String query, String boundedQuery, int queryTokens, int queryTokensKept) {
        if (queryTokensKept == Integer.MAX_VALUE) return;
        boolean charsCut = query != null && boundedQuery.length() < query.length();
        if (charsCut || queryTokensKept < queryTokens) {
            log.info("Cross-encoder query truncated: queryChars={}, queryCharsKept={}, queryTokens={}, queryTokensKept={}",
                    query == null ? 0 : query.length(), boundedQuery.length(), queryTokens, queryTokensKept);
        }
    }

    private float[] run(CrossEncoderPairAssembler.Batch batch) {
        int rows = batch.inputIds().length;
        List<OnnxTensor> tensors = new ArrayList<>();
        try {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put(INPUT_IDS, track(tensors, OnnxTensor.createTensor(environment, batch.inputIds())));
            inputs.put(ATTENTION_MASK, track(tensors, OnnxTensor.createTensor(environment, batch.attentionMask())));
            if (usesTokenTypeIds) inputs.put(TOKEN_TYPE_IDS, track(tensors, OnnxTensor.createTensor(environment, batch.tokenTypeIds())));
            try (OrtSession.Result result = session.run(inputs)) {
                OnnxValue output = result.get(outputName).orElseThrow(() -> new IllegalStateException("Cross-encoder output missing"));
                if (!(output.getValue() instanceof float[][] logits) || logits.length != rows) {
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

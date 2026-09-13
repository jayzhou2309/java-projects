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
 * Windows. A stored chunk is often longer than the passage budget beside the query (about 480 tokens at {@code maxLength} 512:
 * 65% of the 569 stored chunks were longer on 2026-09-13, and an answer past the budget was invisible to the head-only cut).
 * Under {@link PassageScoring#MAX_WINDOW} (the default) {@link CrossEncoderPairAssembler#windows} splits each passage's ids
 * into sliding windows of that budget (overlap {@code windowOverlapTokens}, at most {@code maxWindows} from the head), each
 * window is one model row, and the passage's score is the maximum logit over its rows; under {@link PassageScoring#HEAD} the
 * first window alone is scored, exactly as before windowing. The query keeps what the longest-first cut gives it against the
 * whole passage in both modes. {@link #scoreWithWindows} also reports the rows scored, which the reranker logs as
 * {@code windows=}, and every row's logit per passage, which a traced evaluation records (RAG.md, Retrieval Evaluation, Traces).
 * <p>
 * Work per call. Passages are tokenized {@code batchSize} at a time, and each batch's windows run as one or more ONNX Runtime
 * calls whose rows times longest-row width squared stays within {@link CrossEncoderPairAssembler#MAX_ATTENTION_CELLS_PER_RUN}
 * (eight 512-token pairs), because attention memory, about 88 MB per 512-token row, would otherwise reach 3.9 GB at batch size
 * 64. Windowing adds rows, never wider ones, so peak memory per call is unchanged and the work per call is at most
 * {@code maxWindows} times the head figure.
 * Measured latency and memory (per call, per batch size, and for the two calls the retrieval pool runs at once), with
 * the evidence behind each figure, are kept in one place: RAG.md, Cross-encoder reranker, Latency and Shutdown
 * (live-runs/2026-09-13-reranker/). In short, about 1 GB per call and about 1.7 GB for two concurrent calls, as measured.
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
    private final PassageScoring passageScoring;
    private final int windowOverlapTokens;
    private final int maxWindows;
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

    /**
     * {@code passageScoring}, {@code windowOverlapTokens} (0 or more) and {@code maxWindows} (1 or more) are the
     * {@code rag.retrieval.cross-encoder} window settings (Windows above); {@code windowOverlapTokens} and {@code maxWindows}
     * are read only under {@link PassageScoring#MAX_WINDOW}.
     */
    public OnnxCrossEncoderScorer(Path modelPath, Path tokenizerPath, int maxLength, int batchSize, PassageScoring passageScoring,
            int windowOverlapTokens, int maxWindows) {
        if (passageScoring == null) throw new IllegalArgumentException("passageScoring is required");
        if (windowOverlapTokens < 0) throw new IllegalArgumentException("windowOverlapTokens must not be negative: " + windowOverlapTokens);
        if (maxWindows < 1) throw new IllegalArgumentException("maxWindows must be at least 1: " + maxWindows);
        this.batchSize = batchSize;
        this.passageScoring = passageScoring;
        this.windowOverlapTokens = windowOverlapTokens;
        this.maxWindows = maxWindows;
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
        log.info("Cross-encoder loaded: model={}, maxLength={}, batchSize={}, scoring={}, {}", modelPath, maxLength, batchSize, scoring(),
                describe(session));
    }

    /** {@code head}, or {@code max-window/overlap=<windowOverlapTokens>/maxWindows=<maxWindows>}. */
    @Override
    public String scoring() {
        if (passageScoring == PassageScoring.HEAD) return PassageScoring.HEAD.label();
        return PassageScoring.MAX_WINDOW.label() + "/overlap=" + windowOverlapTokens + "/maxWindows=" + maxWindows;
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
        return scoreWithWindows(query, passages).scores();
    }

    /**
     * One score per passage: the maximum logit over the passage's windows ({@link CrossEncoderPairAssembler#windows}; one
     * window, so its own logit, under {@link PassageScoring#HEAD}), the total windows scored, and each passage's row logits in
     * window order ({@link PairScorer.Scored#windowScores}), from which the score is reduced by {@link CrossEncoderPairAssembler#maxOverWindows}. Every
     * window of a group is a row under the per-call attention cap, so a call's memory is unchanged by windowing.
     */
    @Override
    public Scored scoreWithWindows(String query, List<String> passages) {
        lifecycle.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("Cross-encoder is closed");
            String boundedQuery = CrossEncoderInputBounds.bound(query);
            long[] queryIds = tokenIds(boundedQuery);
            float[] scores = new float[passages.size()];
            float[][] windowScores = new float[passages.size()][];
            int queryTokensKept = Integer.MAX_VALUE;
            int windowsScored = 0;
            for (int start = 0; start < passages.size(); start += batchSize) {
                List<String> batch = passages.subList(start, Math.min(passages.size(), start + batchSize));
                List<long[]> passageIds = new ArrayList<>(batch.size());
                for (String passage : batch) passageIds.add(tokenIds(CrossEncoderInputBounds.bound(passage)));
                List<CrossEncoderPairAssembler.Window> windows =
                        assembler.windows(queryIds.length, passageIds, passageScoring, windowOverlapTokens, maxWindows);
                int[] rowsPerPassage = new int[batch.size()];
                for (CrossEncoderPairAssembler.Window window : windows) rowsPerPassage[window.passage()]++;
                float[][] rowScores = new float[batch.size()][];
                for (int passage = 0; passage < batch.size(); passage++) rowScores[passage] = new float[rowsPerPassage[passage]];
                int[] filled = new int[batch.size()];
                for (int from = 0; from < windows.size(); ) {
                    int end = assembler.runEnd(windows, from);
                    CrossEncoderPairAssembler.Batch tensors = assembler.assemble(queryIds, passageIds, windows.subList(from, end));
                    queryTokensKept = Math.min(queryTokensKept, tensors.queryTokensKept());
                    float[] runScores = run(tensors);
                    for (int row = 0; row < runScores.length; row++) {
                        int passage = windows.get(from + row).passage();
                        rowScores[passage][filled[passage]++] = runScores[row];
                    }
                    from = end;
                }
                windowsScored += windows.size();
                for (int passage = 0; passage < batch.size(); passage++) {
                    scores[start + passage] = CrossEncoderPairAssembler.maxOverWindows(rowScores[passage]);
                    windowScores[start + passage] = rowScores[passage];
                }
            }
            logQueryTruncation(query, boundedQuery, queryIds.length, queryTokensKept);
            return new Scored(scores, windowsScored, windowScores);
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

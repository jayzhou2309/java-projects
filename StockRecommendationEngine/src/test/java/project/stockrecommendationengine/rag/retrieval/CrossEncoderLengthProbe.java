package project.stockrecommendationengine.rag.retrieval;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Child-JVM main for {@link CrossEncoderForkedLiveTests}: loads the real model through {@link OnnxCrossEncoderScorer} and scores
 * queries of boundary lengths against a 2,000-character passage, a query-by-passage length sweep, and a 20-pair batch of the
 * longest bounded inputs, printing one {@code PROBE} line per result. A native abort ends this process with a non-zero exit code
 * (134 for a Rust panic), which the parent test reports. Arguments: the model directory and a local port for the lsof control
 * connection (0 for none).
 */
final class CrossEncoderLengthProbe {
    private static final String UNICODE_UNIT = "Revenue \u6536\u5165 caf\u00E9 na\u00EFve \u0645\u0628\u064A\u0639\u0627\u062A "
            + "\uD83D\uDCC8\uD83D\uDE80 \uD83D\uDC68\u200D\uD83D\uDCBB \u0E23\u0E32\u0E22\u0E44\u0E14\u0E49 ";

    public static void main(String[] args) throws Exception {
        try {
            run(Path.of(args[0]), Integer.parseInt(args[1]));
            System.out.println("PROBE done");
            System.exit(0);
        } catch (Throwable failure) {
            System.out.println("PROBE failed " + failure);
            failure.printStackTrace(System.out);
            System.exit(3);
        }
    }

    private static void run(Path modelDir, int controlPort) throws Exception {
        System.out.println("PROBE properties before OPT_OUT_TRACKING=" + System.getProperty("OPT_OUT_TRACKING")
                + " ai.djl.offline=" + System.getProperty("ai.djl.offline") + " RUST_FLAVOR=" + System.getProperty("RUST_FLAVOR"));
        System.out.println("PROBE phase=scorer-build-start");
        long started = System.nanoTime();
        // Head scoring, so the boundary and sweep cases keep the single-row shape their recorded figures were measured on.
        OnnxCrossEncoderScorer scorer = new OnnxCrossEncoderScorer(modelDir.resolve("model.onnx"), modelDir.resolve("tokenizer.json"), 512, 20,
                PassageScoring.HEAD, 0, 1);
        System.out.println("PROBE phase=scorer-built elapsedMs=" + ms(started));
        System.out.println("PROBE properties after OPT_OUT_TRACKING=" + System.getProperty("OPT_OUT_TRACKING")
                + " ai.djl.offline=" + System.getProperty("ai.djl.offline") + " RUST_FLAVOR=" + System.getProperty("RUST_FLAVOR"));
        // An untruncated tokenizer for reporting token counts only (a single sequence without truncation cannot fail).
        try (HuggingFaceTokenizer counter = HuggingFaceTokenizer.builder().optTokenizerPath(modelDir.resolve("tokenizer.json"))
                .optAddSpecialTokens(false).optTruncation(false).optPadding(false).build()) {
            String passage = passage(1);
            System.out.println("PROBE passage chars=" + passage.length() + " tokens=" + counter.encode(passage).getIds().length);

            Map<String, String> cases = new LinkedHashMap<>();
            cases.put("words508", words(508));
            cases.put("words509", words(509));
            cases.put("words600", words(600));
            cases.put("chars4000", cycled("data center revenue growth ", 4_000));
            cases.put("chars20000", cycled("data center revenue growth ", 20_000));
            cases.put("chars25000", cycled("data center revenue growth ", 25_000));
            cases.put("singleWord5000", "x".repeat(5_000));
            cases.put("unicodeEmoji", cycled(UNICODE_UNIT, 3_000) + "\uD800 lone high surrogate, nul " + (char) 0 + " end");
            for (Map.Entry<String, String> c : cases.entrySet()) {
                System.out.println("PROBE start case=" + c.getKey());
                long caseStarted = System.nanoTime();
                float[] scores = scorer.score(c.getValue(), List.of(passage));
                long elapsed = ms(caseStarted);
                requireScores(c.getKey(), scores, 1);
                System.out.println("PROBE result case=" + c.getKey() + " queryChars=" + c.getValue().length()
                        + " queryTokens=" + counter.encode(CrossEncoderInputBounds.bound(c.getValue())).getIds().length
                        + " passageChars=" + passage.length() + " scores=" + Arrays.toString(scores) + " elapsedMs=" + elapsed);
            }

            // Query length by passage length around every boundary of a 512-token pair (509 content tokens, half 254).
            List<String> passages = List.of("", "data", words(254), words(255), words(509), words(510), passage);
            for (int queryWords : new int[] {0, 1, 253, 254, 255, 256, 508, 509, 510, 1_000}) {
                float[] scores = scorer.score(words(queryWords), passages);
                requireScores("sweep" + queryWords, scores, passages.size());
                System.out.println("PROBE sweep queryWords=" + queryWords + " passageWords=[0, 1, 254, 255, 509, 510, 2000 chars] scores="
                        + Arrays.toString(scores));
            }

            // Worst bounded batch: a 20,000-character query with 20 passages of 2,000 characters, every pair at 512 tokens.
            List<String> twenty = IntStream.rangeClosed(1, 20).mapToObj(CrossEncoderLengthProbe::passage).toList();
            String longest = cycled("data center revenue growth ", 20_000);
            scorer.score(longest, twenty); // warm-up
            List<Long> elapsed = new ArrayList<>();
            for (int run = 0; run < 3; run++) {
                long batchStarted = System.nanoTime();
                requireScores("batch20", scorer.score(longest, twenty), 20);
                elapsed.add(ms(batchStarted));
            }
            System.out.println("PROBE batch pairs=20 queryChars=20000 passageChars=2000 tokensPerPair=512 elapsedMs=" + elapsed);
        } finally {
            scorer.close();
        }

        if (controlPort > 0) {
            // Positive control for the parent's lsof sampling: one known local connection held open briefly.
            try (Socket control = new Socket(InetAddress.getLoopbackAddress(), controlPort)) {
                System.out.println("PROBE control connected port=" + controlPort);
                Thread.sleep(1_500);
            }
        }
    }

    private static void requireScores(String name, float[] scores, int expected) {
        if (scores == null || scores.length != expected) {
            throw new IllegalStateException(name + ": expected " + expected + " scores, got " + (scores == null ? "null" : scores.length));
        }
        for (float score : scores) {
            if (!Float.isFinite(score)) throw new IllegalStateException(name + ": non-finite score " + score);
        }
    }

    private static String words(int count) {
        return count == 0 ? "" : String.join(" ", java.util.Collections.nCopies(count, "data"));
    }

    private static String cycled(String unit, int chars) {
        StringBuilder text = new StringBuilder(chars + unit.length());
        while (text.length() < chars) text.append(unit);
        String cut = text.substring(0, chars);
        // Never end on half of a surrogate pair, so the case length is exact and well formed.
        return Character.isHighSurrogate(cut.charAt(cut.length() - 1)) ? cut.substring(0, cut.length() - 1) + "." : cut;
    }

    private static String passage(int id) {
        String[] topics = {
                "Data Center revenue increased due to strong demand for accelerated computing platforms used for large language models.",
                "Gaming revenue reflected higher sales of GeForce RTX GPUs to partners ahead of the holiday season.",
                "Professional Visualization revenue grew on adoption of RTX workstation GPUs for design and simulation.",
                "Automotive revenue rose on sales of self-driving platforms and AI cockpit solutions to automakers.",
                "Operating expenses increased because of compensation and benefits related to employee growth."};
        StringBuilder text = new StringBuilder("Passage " + id + ". ");
        while (text.length() < 2000) text.append(topics[(id + text.length() / 120) % topics.length]).append(' ');
        return text.substring(0, 2000);
    }

    private static long ms(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}

package project.stockrecommendationengine.rag.retrieval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Child-JVM main for {@link CrossEncoderResourceLiveTests}: loads the real model through {@link OnnxCrossEncoderScorer} with the
 * given {@code maxLength} and {@code batchSize} and scores one query against {@code passages} passages, where query and every
 * passage are {@code chars} characters of {@code "a "} repeated ({@code unit=a}) or of U+4E2D ({@code unit=cjk}), or, for
 * {@code unit=filing}, a short question against passages of {@code chars} characters of filing-like prose. Scores the call
 * twice and prints one {@code PROBE} line per call with its elapsed time; with {@code threads} above 1, each round starts that
 * many identical calls at once (the retrieval service's rerank pool has two threads). Run it under {@code /usr/bin/time -l} to
 * read the peak memory footprint. Arguments: model directory, maxLength, batchSize, unit, chars, passages, optional threads.
 */
final class CrossEncoderResourceProbe {
    public static void main(String[] args) {
        try {
            Path modelDir = Path.of(args[0]);
            int maxLength = Integer.parseInt(args[1]);
            int batchSize = Integer.parseInt(args[2]);
            String unit = args[3];
            int chars = Integer.parseInt(args[4]);
            int count = Integer.parseInt(args[5]);
            int threads = args.length > 6 ? Integer.parseInt(args[6]) : 1;
            String text = text(unit, chars);
            String query = unit.equals("filing") ? "What drove the increase in data center revenue?" : text;
            List<String> passages = new ArrayList<>(Collections.nCopies(count, text));
            long loadStarted = System.nanoTime();
            // Head scoring: the probe measures the pre-window row shape; windowed scoring adds rows under the same per-call cap.
            try (OnnxCrossEncoderScorer scorer = new OnnxCrossEncoderScorer(modelDir.resolve("model.onnx"), modelDir.resolve("tokenizer.json"),
                    maxLength, batchSize, PassageScoring.HEAD, 0, 1)) {
                System.out.println("PROBE loaded elapsedMs=" + ms(loadStarted));
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                try {
                    for (int call = 1; call <= 2; call++) {
                        long started = System.nanoTime();
                        List<Future<float[]>> calls = new ArrayList<>();
                        for (int t = 0; t < threads; t++) calls.add(pool.submit(() -> scorer.score(query, passages)));
                        float[] scores = null;
                        for (Future<float[]> pending : calls) scores = check(pending.get(), count);
                        long elapsed = ms(started);
                        System.out.println("PROBE call=" + call + " maxLength=" + maxLength + " batchSize=" + batchSize + " unit=" + unit
                                + " chars=" + text.length() + " passages=" + count + " threads=" + threads + " firstScore=" + scores[0]
                                + " elapsedMs=" + elapsed + " heapUsedMb=" + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576);
                    }
                } finally {
                    pool.shutdownNow();
                }
            }
            System.out.println("PROBE done");
            System.exit(0);
        } catch (Throwable failure) {
            System.out.println("PROBE failed " + failure);
            failure.printStackTrace(System.out);
            System.exit(3);
        }
    }

    private static float[] check(float[] scores, int count) {
        if (scores.length != count) throw new IllegalStateException("expected " + count + " scores, got " + scores.length);
        for (float score : scores) if (!Float.isFinite(score)) throw new IllegalStateException("non-finite score " + score);
        return scores;
    }

    static String text(String unit, int chars) {
        return switch (unit) {
            case "a" -> "a ".repeat(chars / 2);
            case "cjk" -> "\u4E2D".repeat(chars);
            case "filing" -> {
                String sentence = "Data Center revenue increased due to strong demand for accelerated computing platforms used for large language models. ";
                yield sentence.repeat(chars / sentence.length() + 1).substring(0, chars);
            }
            default -> throw new IllegalArgumentException("unit must be a, cjk or filing: " + unit);
        };
    }

    private static long ms(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}

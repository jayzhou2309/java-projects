package project.stockrecommendationengine.rag.retrieval;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/**
 * Opt-in (-Drag.rerank.live=true, model files under models/): the scorer's separate tokenization of query and passage.
 * <ul>
 * <li>The tokenizer the scorer uses returns no special tokens and no overflow encodings, even for 20,000-character texts.</li>
 * <li>{@link CrossEncoderParityProbe} in a child JVM (-Xmx1g): Java-assembled tensors equal the replaced native pair encoding on
 * every generated pair that needs no truncation; truncating pairs are counted and printed.</li>
 * <li>{@link CrossEncoderResourceProbe} in a child JVM under {@code /usr/bin/time -l} for the two heaviest measured cases
 * (max-length 512, batch size 64, a query and 40 passages of 20,000 characters, CJK and {@code "a "} repeated): exit 0 and a
 * peak memory footprint under 2 GB. Where {@code /usr/bin/time -l} reports no footprint (not macOS), that assertion is skipped
 * with a message and the exit code is still checked.</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "rag.rerank.live", matches = "true")
class CrossEncoderSeparateTokenizationLiveTests {
    private static final long FOOTPRINT_LIMIT_BYTES = 2_000_000_000L;
    private static final Path TIME = Path.of("/usr/bin/time");
    private static final Pattern FOOTPRINT = Pattern.compile("^\\s*(\\d+)\\s+peak memory footprint\\s*$");

    private static Path modelDir() {
        return Path.of(System.getProperty("rag.rerank.model-dir", "models/cross-encoder-ms-marco-MiniLM-L-6-v2")).toAbsolutePath();
    }

    @Test
    void theScorersTokenizerAddsNoSpecialTokensAndBuildsNoOverflowEncodings() throws Exception {
        Path tokenizerJson = modelDir().resolve("tokenizer.json");
        long[] specialIds = CrossEncoderPairAssembler.fromTokenizerJson(tokenizerJson, 512).specialIds();
        Map<String, String> texts = Map.of(
                "question", "What was total revenue for fiscal year 2025?",
                "mixed", "Revenue \u6536\u5165 caf\u00E9 na\u00EFve 12,345.67% \uD83D\uDCC8 \uD83D\uDC68\u200D\uD83D\uDCBB (Q4) \u2014 end.",
                "a20000", "a ".repeat(10_000),
                "cjk20000", "\u4E2D".repeat(20_000));
        try (HuggingFaceTokenizer tokenizer = OnnxCrossEncoderScorer.singleSequenceTokenizer(tokenizerJson)) {
            assertThat(tokenizer.getTruncation()).isEqualTo("DO_NOT_TRUNCATE");
            assertThat(tokenizer.getPadding()).isEqualTo("DO_NOT_PAD");
            for (Map.Entry<String, String> text : texts.entrySet()) {
                // Overflow requested explicitly: with truncation off the native encoding has none to return.
                Encoding withOverflow = tokenizer.encode(text.getValue(), false, true);
                Encoding asScored = tokenizer.encode(text.getValue(), false, false);
                Encoding builderDefaults = tokenizer.encode(text.getValue());
                System.out.println("CROSS_ENCODER tokenizer text=" + text.getKey() + " chars=" + text.getValue().length() + " tokens="
                        + asScored.getIds().length + " overflowing=" + withOverflow.getOverflowing().length + " exceedMaxLength="
                        + withOverflow.exceedMaxLength());
                assertThat(withOverflow.getOverflowing()).as(text.getKey()).isEmpty();
                assertThat(withOverflow.exceedMaxLength()).as(text.getKey()).isFalse();
                assertThat(asScored.getIds()).as(text.getKey()).containsExactly(withOverflow.getIds()).containsExactly(builderDefaults.getIds());
                assertThat(Arrays.stream(asScored.getIds()).boxed().toList()).as(text.getKey())
                        .doesNotContainAnyElementsOf(Arrays.stream(specialIds).boxed().toList());
                assertThat(asScored.getSpecialTokenMask()).as(text.getKey()).containsOnly(0L);
                assertThat(asScored.getTypeIds()).as(text.getKey()).containsOnly(0L);
            }
            assertThat(tokenizer.encode(texts.get("a20000"), false, false).getIds()).hasSize(10_000);
            assertThat(tokenizer.encode(texts.get("cjk20000"), false, false).getIds()).hasSize(20_000);
        }
    }

    @Test
    void javaAssembledPairsEqualTheNativePairEncodingWhereNoTruncationIsNeeded() throws Exception {
        ForkedJvm.Result result = ForkedJvm.run(CrossEncoderParityProbe.class, List.of("-Xmx1g"), List.of(modelDir().toString()), List.of(), 600, null);
        assertThat(result.exitCode()).as(result.output()).isZero();
        String a = line(result, "PARITY a ");
        System.out.println("CROSS_ENCODER " + a);
        System.out.println("CROSS_ENCODER " + line(result, "PARITY b "));
        System.out.println("CROSS_ENCODER " + line(result, "PARITY c "));
        assertThat(number(a, "pairs")).isGreaterThanOrEqualTo(300);
        assertThat(number(a, "mismatches")).isZero();
        assertThat(number(a, "paddedBatchRowMismatches")).isZero();
        assertThat(result.lines()).contains("PARITY done");
    }

    @Test
    void theTwoHeaviestCasesExitCleanlyUnderTwoGigabytesInAChildJvm() throws Exception {
        for (String unit : List.of("cjk", "a")) {
            List<String> prefix = Files.isExecutable(TIME) ? List.of(TIME.toString(), "-l") : List.of();
            ForkedJvm.Result result = ForkedJvm.run(prefix, CrossEncoderResourceProbe.class, List.of("-Xmx1g"),
                    List.of(modelDir().toString(), "512", "64", unit, "20000", "40"), List.of(), 300, null);
            OptionalLong footprint = result.lines().stream().map(FOOTPRINT::matcher).filter(Matcher::matches)
                    .mapToLong(m -> Long.parseLong(m.group(1))).findFirst();
            result.lines().stream().filter(l -> l.startsWith("PROBE call=")).forEach(l -> System.out.println("CROSS_ENCODER resource " + l));
            System.out.println("CROSS_ENCODER resource unit=" + unit + " exitCode=" + result.exitCode() + " timedOut=" + result.timedOut()
                    + " peakMemoryFootprintBytes=" + (footprint.isPresent() ? footprint.getAsLong() : "unavailable"));
            assertThat(result.timedOut()).as(result.output()).isFalse();
            assertThat(result.exitCode()).as("child exit code (137 is an OS memory kill)\n" + result.output()).isZero();
            assertThat(result.lines()).contains("PROBE done");
            if (footprint.isPresent()) {
                assertThat(footprint.getAsLong()).as("peak memory footprint of unit=" + unit).isLessThan(FOOTPRINT_LIMIT_BYTES);
            } else {
                System.out.println("CROSS_ENCODER resource footprint assertion skipped: /usr/bin/time -l reported no peak memory footprint on this platform");
            }
        }
    }

    private static String line(ForkedJvm.Result result, String prefix) {
        return result.lines().stream().filter(l -> l.startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError("no line starting " + prefix + "\n" + result.output()));
    }

    private static long number(String line, String key) {
        Matcher matcher = Pattern.compile("\\b" + key + "=(\\d+)").matcher(line);
        if (!matcher.find()) throw new AssertionError("no " + key + " in " + line);
        return Long.parseLong(matcher.group(1));
    }
}

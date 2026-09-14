package project.stockrecommendationengine.rag.retrieval;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Child-JVM main for {@link CrossEncoderSeparateTokenizationLiveTests}: compares the scorer's Java-assembled pair tensors with
 * the native pair encoding they replaced ({@code encode(query, passage)} with special tokens, {@code longest_first} truncation at
 * 512 and no padding, the Milestone 2 remediation 1 tokenizer), on generated text mixing English, digits, CJK, accents,
 * punctuation and emoji. The native reference is used here only, on sizes where its pair encoding is cheap.
 * <ul>
 * <li>Section a: pairs whose combined length needs no truncation (at most 509 content tokens), including empty sides and an
 * exactly full pair; every {@code input_ids}, {@code token_type_ids} and {@code attention_mask} is compared, one pair alone and
 * again as rows of padded batches of 20 (the row's prefix must equal the native encoding, the rest pad id, type 0, mask 0).</li>
 * <li>Section b: truncating pairs with each side 300 to 1,000 tokens (some with equal lengths); counts exact id matches and, for
 * the first 50, the absolute logit difference between the two encodings run through the same ONNX Runtime session.</li>
 * <li>Section c: truncating pairs where one side has at most half the budget (the other truncation branch); counts exact matches.</li>
 * </ul>
 * Prints {@code PARITY} lines. Arguments: model directory.
 */
final class CrossEncoderParityProbe {
    private static final int MAX_LENGTH = 512;
    private static final String[] FRAGMENTS = {
            "revenue", "Data", "Center", "the", "increased", "GPU", "fiscal", "NVIDIA", "Microsoft's", "gross", "margin", "quarter",
            "2025", "$130.5", "114%", "3,300,000", "0.75", "Q4", "10-K", "FY24",
            "\u6536\u5165", "\u6570\u636E\u4E2D\u5FC3", "\u4E2D", "\u5E74\u5EA6\u62A5\u544A",
            "caf\u00E9", "na\u00EFve", "Z\u00FCrich", "r\u00E9sum\u00E9", "e\u0301t\u00E9", "\u00C5ngstr\u00F6m",
            "\u2014", "...", "(", ")", "?!", "\"quoted\"", ";", "&", "#", "\u00AB", "\u00BB",
            "\uD83D\uDCC8", "\uD83D\uDE80", "\uD83D\uDC68\u200D\uD83D\uDCBB", "\uD83C\uDDFA\uD83C\uDDF8", "\u2764\uFE0F"};

    private static HuggingFaceTokenizer single;
    private static HuggingFaceTokenizer reference;
    private static CrossEncoderPairAssembler assembler;
    private static OrtSession session;
    private static OrtEnvironment environment;

    public static void main(String[] args) {
        Path modelDir = Path.of(args[0]);
        try {
            DjlRuntimeDefaults.apply();
            environment = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                session = environment.createSession(modelDir.resolve("model.onnx").toString(), options);
            }
            Path tokenizerJson = modelDir.resolve("tokenizer.json");
            // The scorer's tokenizer configuration.
            single = OnnxCrossEncoderScorer.singleSequenceTokenizer(tokenizerJson);
            // The replaced native pair encoding (remediation 1): special tokens, longest_first at 512, no padding.
            reference = HuggingFaceTokenizer.builder().optTokenizerPath(tokenizerJson).optAddSpecialTokens(true).optTruncation(true)
                    .optMaxLength(MAX_LENGTH).optPadding(false).build();
            assembler = CrossEncoderPairAssembler.fromTokenizerJson(tokenizerJson, MAX_LENGTH);
            System.out.println("PARITY tokenizer single truncation=" + single.getTruncation() + " padding=" + single.getPadding()
                    + " reference truncation=" + reference.getTruncation() + " maxLength=" + reference.getMaxLength()
                    + " specialIds(first, middle, last, pad)=" + Arrays.toString(assembler.specialIds()));
            Random random = new Random(20260913L);
            sectionA(random);
            sectionB(random);
            sectionC(random);
            System.out.println("PARITY done");
            System.exit(0);
        } catch (Throwable failure) {
            System.out.println("PARITY failed " + failure);
            failure.printStackTrace(System.out);
            System.exit(3);
        }
    }

    private static void sectionA(Random random) {
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[] {"", ""});
        pairs.add(new String[] {"", "Data Center revenue was $115.2 billion \uD83D\uDCC8"});
        pairs.add(new String[] {"What was revenue?", ""});
        pairs.add(new String[] {"a ".repeat(100).trim(), "a ".repeat(409).trim()}); // exactly 509 content tokens
        pairs.add(new String[] {"\u4E2D".repeat(254), "\u4E2D".repeat(255)}); // exactly 509, CJK
        while (pairs.size() < 320) {
            String query = text(random, random.nextInt(61));
            String passage = text(random, random.nextInt(random.nextBoolean() ? 120 : 460));
            if (ids(query).length + ids(passage).length <= MAX_LENGTH - CrossEncoderPairAssembler.SPECIAL_TOKENS) pairs.add(new String[] {query, passage});
        }
        int mismatches = 0;
        int batchMismatches = 0;
        int maxTotal = 0;
        int cjkPairs = 0;
        int emojiPairs = 0;
        int asciiOnlyPairs = 0;
        for (int start = 0; start < pairs.size(); start += 20) {
            List<String[]> group = pairs.subList(start, Math.min(pairs.size(), start + 20));
            List<long[]> passages = new ArrayList<>();
            long[] firstQuery = ids(group.get(0)[0]);
            for (String[] pair : group) passages.add(ids(pair[1]));
            // Rows of one padded batch share the query, so batch rows are checked for the group's first query.
            CrossEncoderPairAssembler.Batch batch = assembler.assemble(firstQuery, passages);
            for (int row = 0; row < group.size(); row++) {
                String[] pair = group.get(row);
                long[] queryIds = ids(pair[0]);
                Encoding expected = reference.encode(pair[0], pair[1], true, false);
                if (expected.exceedMaxLength()) throw new IllegalStateException("section a pair needs truncation: " + row);
                CrossEncoderPairAssembler.Batch alone = assembler.assemble(queryIds, List.of(passages.get(row)));
                boolean same = Arrays.equals(expected.getIds(), alone.inputIds()[0]) && Arrays.equals(expected.getTypeIds(), alone.tokenTypeIds()[0])
                        && Arrays.equals(expected.getAttentionMask(), alone.attentionMask()[0]);
                if (!same) {
                    mismatches++;
                    System.out.println("PARITY a mismatch index=" + (start + row) + " native=" + Arrays.toString(expected.getIds())
                            + " java=" + Arrays.toString(alone.inputIds()[0]));
                }
                Encoding expectedFirstQuery = reference.encode(group.get(0)[0], pair[1], true, false);
                if (!rowMatches(expectedFirstQuery, batch, row)) batchMismatches++;
                maxTotal = Math.max(maxTotal, expected.getIds().length);
                if ((pair[0] + pair[1]).matches("(?s).*[\u4E00-\u9FFF].*")) cjkPairs++;
                if ((pair[0] + pair[1]).codePoints().anyMatch(cp -> cp >= 0x1F000)) emojiPairs++;
                if ((pair[0] + pair[1]).codePoints().allMatch(cp -> cp < 0x80)) asciiOnlyPairs++;
            }
        }
        System.out.println("PARITY a pairs=" + pairs.size() + " mismatches=" + mismatches + " paddedBatchRowMismatches=" + batchMismatches
                + " longestPairTokens=" + maxTotal + " pairsWithCjk=" + cjkPairs + " pairsWithEmoji=" + emojiPairs + " asciiOnlyPairs=" + asciiOnlyPairs);
    }

    private static boolean rowMatches(Encoding expected, CrossEncoderPairAssembler.Batch batch, int row) {
        long[] ids = batch.inputIds()[row];
        long[] types = batch.tokenTypeIds()[row];
        long[] mask = batch.attentionMask()[row];
        int n = expected.getIds().length;
        if (!Arrays.equals(expected.getIds(), Arrays.copyOf(ids, n)) || !Arrays.equals(expected.getTypeIds(), Arrays.copyOf(types, n))
                || !Arrays.equals(expected.getAttentionMask(), Arrays.copyOf(mask, n))) return false;
        long pad = assembler.specialIds()[3];
        for (int i = n; i < ids.length; i++) if (ids[i] != pad || types[i] != 0 || mask[i] != 0) return false;
        return true;
    }

    private static void sectionB(Random random) throws Exception {
        int total = 0;
        int exact = 0;
        int queryLonger = 0;
        int passageLonger = 0;
        int equal = 0;
        List<Double> diffs = new ArrayList<>();
        while (total < 60) {
            String query = textOfTokens(random, 300 + random.nextInt(701));
            String passage = total % 12 == 5 ? query : textOfTokens(random, 300 + random.nextInt(701));
            int q = ids(query).length;
            int p = ids(passage).length;
            if (q < 300 || q > 1000 || p < 300 || p > 1000) continue;
            total++;
            if (q > p) queryLonger++; else if (q < p) passageLonger++; else equal++;
            Encoding expected = reference.encode(query, passage, true, false);
            CrossEncoderPairAssembler.Batch java = assembler.assemble(ids(query), List.of(ids(passage)));
            boolean same = Arrays.equals(expected.getIds(), java.inputIds()[0]) && Arrays.equals(expected.getTypeIds(), java.tokenTypeIds()[0])
                    && Arrays.equals(expected.getAttentionMask(), java.attentionMask()[0]);
            if (same) exact++;
            else System.out.println("PARITY b differs q=" + q + " p=" + p + " nativeLength=" + expected.getIds().length + " javaLength=" + java.inputIds()[0].length);
            if (diffs.size() < 50) {
                float nativeLogit = logit(new long[][] {expected.getIds()}, new long[][] {expected.getTypeIds()}, new long[][] {expected.getAttentionMask()});
                float javaLogit = logit(java.inputIds(), java.tokenTypeIds(), java.attentionMask());
                diffs.add((double) Math.abs(nativeLogit - javaLogit));
            }
        }
        List<Double> sorted = diffs.stream().sorted().toList();
        double median = sorted.size() % 2 == 1 ? sorted.get(sorted.size() / 2) : (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2;
        System.out.println("PARITY b pairs=" + total + " exactIdTypeMaskMatches=" + exact + " queryLonger=" + queryLonger + " passageLonger="
                + passageLonger + " equalLength=" + equal + " logitSample=" + diffs.size() + " maxAbsLogitDiff=" + sorted.get(sorted.size() - 1)
                + " medianAbsLogitDiff=" + median);
    }

    private static void sectionC(Random random) {
        int total = 0;
        int exact = 0;
        while (total < 100) {
            boolean shortQuery = random.nextBoolean();
            String small = textOfTokens(random, 1 + random.nextInt(254));
            String large = textOfTokens(random, 300 + random.nextInt(701));
            String query = shortQuery ? small : large;
            String passage = shortQuery ? large : small;
            int q = ids(query).length;
            int p = ids(passage).length;
            if (q + p <= MAX_LENGTH - CrossEncoderPairAssembler.SPECIAL_TOKENS || Math.min(q, p) > (MAX_LENGTH - 3) / 2 || Math.max(q, p) > 1000) continue;
            total++;
            Encoding expected = reference.encode(query, passage, true, false);
            CrossEncoderPairAssembler.Batch java = assembler.assemble(ids(query), List.of(ids(passage)));
            if (Arrays.equals(expected.getIds(), java.inputIds()[0]) && Arrays.equals(expected.getTypeIds(), java.tokenTypeIds()[0])
                    && Arrays.equals(expected.getAttentionMask(), java.attentionMask()[0])) exact++;
            else System.out.println("PARITY c differs q=" + q + " p=" + p);
        }
        System.out.println("PARITY c pairs=" + total + " exactIdTypeMaskMatches=" + exact);
    }

    private static float logit(long[][] ids, long[][] types, long[][] mask) throws Exception {
        try (OnnxTensor idTensor = OnnxTensor.createTensor(environment, ids);
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, mask);
             OnnxTensor typeTensor = OnnxTensor.createTensor(environment, types);
             OrtSession.Result result = session.run(Map.of("input_ids", idTensor, "attention_mask", maskTensor, "token_type_ids", typeTensor))) {
            return ((float[][]) result.get(0).getValue())[0][0];
        }
    }

    private static long[] ids(String text) {
        return single.encode(text, false, false).getIds();
    }

    /**
     * {@code fragments} fragments from one pool chosen at random: English and digits only (indexes 0 to 19), CJK only (20 to 23),
     * accents, punctuation and emoji only (24 to 45), or all of them; sometimes joined without a space.
     */
    private static String text(Random random, int fragments) {
        int[][] pools = {{0, 20}, {20, 24}, {24, FRAGMENTS.length}, {0, FRAGMENTS.length}};
        int[] pool = pools[random.nextInt(pools.length)];
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fragments; i++) {
            if (i > 0) out.append(random.nextInt(8) == 0 ? "" : " ");
            out.append(FRAGMENTS[pool[0] + random.nextInt(pool[1] - pool[0])]);
        }
        return out.toString();
    }

    /** Space-separated fragments until the text has at least {@code tokens} tokens (counted on the whole text at the end). */
    private static String textOfTokens(Random random, int tokens) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        while (count < tokens) {
            String fragment = FRAGMENTS[random.nextInt(FRAGMENTS.length)];
            if (out.length() > 0) out.append(' ');
            out.append(fragment);
            count += ids(fragment).length;
        }
        return out.toString();
    }
}

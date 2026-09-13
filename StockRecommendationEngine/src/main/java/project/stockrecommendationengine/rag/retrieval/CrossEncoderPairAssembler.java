package project.stockrecommendationengine.rag.retrieval;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the cross-encoder's batch tensors in Java from separately tokenized query and passage ids, so the native tokenizer
 * never encodes or truncates a pair (its pair truncation builds every overflow-piece combination, a cost that grows with the
 * product of the two lengths; see {@link OnnxCrossEncoderScorer}). Imports nothing from DJL or ONNX Runtime.
 * <p>
 * Layout, read from the {@code post_processor} pair template of {@code tokenizer.json} (never hard-coded): for this model
 * {@code [CLS] query [SEP] passage [SEP]} with ids 101, 102, 102. The template must have exactly the shape special, sequence A,
 * special, sequence B, special, each special with one id, the first two specials typed like A and the last typed like B;
 * anything else fails construction. {@code token_type_ids} are A's type (0) through the first {@code [SEP]} inclusive and B's
 * type (1) after; {@code attention_mask} is 1 for every real token; rows are padded to the longest pair in the batch with the pad
 * id ({@code padding.pad_id} when {@code tokenizer.json} declares padding, otherwise the id of the added token {@code [PAD]},
 * the Hugging Face default pad token; 0 for this model) and mask 0.
 * <p>
 * Truncation, longest first ({@link #keptLengths}): with {@code budget = maxLength - 3}, a pair whose lengths sum to at most the
 * budget is kept whole; otherwise one token at a time is removed from the end of the longer sequence until the pair fits. On a
 * tie the query loses the token, unless the query was the longer sequence before any removal, in which case the passage does.
 * The result in closed form: if the shorter sequence has at most half the budget it is kept whole and the longer keeps
 * {@code budget - shorter}; otherwise the two keep {@code floor(budget / 2)} and {@code ceil(budget / 2)}, the ceiling going to
 * the query only when the query started longer. This is the target-length arithmetic of Hugging Face tokenizers'
 * {@code longest_first} for a pair, measured equal on the parity sample recorded in RAG.md.
 */
final class CrossEncoderPairAssembler {
    /** Special tokens a pair adds: first, middle, last. */
    static final int SPECIAL_TOKENS = 3;
    /**
     * Upper bound on {@code rows x width x width} of one ONNX Runtime call, the size of the attention matrices that dominate its
     * memory: eight pairs of 512 tokens (about 88 MB of peak footprint per 512-token row on the development Mac, 2026-09-13), or
     * all 64 rows of a batch whose longest pair has at most 181 tokens.
     */
    static final long MAX_ATTENTION_CELLS_PER_RUN = 8L * 512 * 512;

    private final long firstId;
    private final long middleId;
    private final long lastId;
    private final long padId;
    private final long queryTypeId;
    private final long passageTypeId;
    private final int maxLength;

    /** The model's input tensors for one batch, plus the fewest query tokens kept in any pair of it. */
    record Batch(long[][] inputIds, long[][] attentionMask, long[][] tokenTypeIds, int queryTokensKept) {
    }

    CrossEncoderPairAssembler(long firstId, long middleId, long lastId, long padId, long queryTypeId, long passageTypeId, int maxLength) {
        if (maxLength <= SPECIAL_TOKENS) throw new IllegalArgumentException("maxLength must exceed " + SPECIAL_TOKENS + ": " + maxLength);
        this.firstId = firstId;
        this.middleId = middleId;
        this.lastId = lastId;
        this.padId = padId;
        this.queryTypeId = queryTypeId;
        this.passageTypeId = passageTypeId;
        this.maxLength = maxLength;
    }

    /** Reads the pair template and pad id from a Hugging Face {@code tokenizer.json}; fails naming the path on any other shape. */
    static CrossEncoderPairAssembler fromTokenizerJson(Path tokenizerJson, int maxLength) throws IOException {
        JsonNode root = JsonMapper.builder().build().readTree(Files.readString(tokenizerJson));
        JsonNode processor = root.path("post_processor");
        JsonNode pair = processor.path("pair");
        if (!"TemplateProcessing".equals(processor.path("type").asString("")) || !pair.isArray() || pair.size() != 5) {
            throw invalid(tokenizerJson, "post_processor is not a five-piece TemplateProcessing pair template");
        }
        JsonNode first = pair.get(0).path("SpecialToken");
        JsonNode a = pair.get(1).path("Sequence");
        JsonNode middle = pair.get(2).path("SpecialToken");
        JsonNode b = pair.get(3).path("Sequence");
        JsonNode last = pair.get(4).path("SpecialToken");
        if (first.isMissingNode() || middle.isMissingNode() || last.isMissingNode() || !"A".equals(a.path("id").asString(""))
                || !"B".equals(b.path("id").asString(""))) {
            throw invalid(tokenizerJson, "pair template is not special, A, special, B, special");
        }
        long typeA = typeId(a, tokenizerJson);
        long typeB = typeId(b, tokenizerJson);
        if (typeId(first, tokenizerJson) != typeA || typeId(middle, tokenizerJson) != typeA || typeId(last, tokenizerJson) != typeB) {
            throw invalid(tokenizerJson, "pair template special tokens are not typed A, A, B");
        }
        JsonNode specials = processor.path("special_tokens");
        return new CrossEncoderPairAssembler(specialId(specials, first, tokenizerJson), specialId(specials, middle, tokenizerJson),
                specialId(specials, last, tokenizerJson), padId(root, tokenizerJson), typeA, typeB, maxLength);
    }

    private static long typeId(JsonNode piece, Path path) {
        JsonNode type = piece.path("type_id");
        if (!type.isIntegralNumber()) throw invalid(path, "pair template piece without an integer type_id");
        return type.longValue();
    }

    private static long specialId(JsonNode specials, JsonNode piece, Path path) {
        String name = piece.path("id").asString("");
        JsonNode ids = specials.path(name).path("ids");
        if (!ids.isArray() || ids.size() != 1 || !ids.get(0).isIntegralNumber()) {
            throw invalid(path, "special token " + name + " does not map to exactly one id");
        }
        return ids.get(0).longValue();
    }

    private static long padId(JsonNode root, Path path) {
        JsonNode padding = root.path("padding");
        if (padding.path("pad_id").isIntegralNumber()) return padding.path("pad_id").longValue();
        for (JsonNode added : root.path("added_tokens")) {
            if ("[PAD]".equals(added.path("content").asString("")) && added.path("id").isIntegralNumber()) return added.path("id").longValue();
        }
        throw invalid(path, "no padding.pad_id and no [PAD] added token");
    }

    private static IllegalStateException invalid(Path path, String reason) {
        return new IllegalStateException("Unsupported cross-encoder tokenizer " + path + ": " + reason);
    }

    int maxLength() {
        return maxLength;
    }

    long[] specialIds() {
        return new long[] {firstId, middleId, lastId, padId};
    }

    /**
     * Tokens kept of each sequence, {@code {query, passage}}, under the longest-first rule in the class Javadoc. Never negative,
     * never more than given, and the sum is at most {@code maxLength - 3}.
     */
    static int[] keptLengths(int queryTokens, int passageTokens, int maxLength) {
        int budget = maxLength - SPECIAL_TOKENS;
        if (queryTokens + passageTokens <= budget) return new int[] {queryTokens, passageTokens};
        int shorter = Math.min(queryTokens, passageTokens);
        if (2 * shorter <= budget) {
            return queryTokens <= passageTokens ? new int[] {queryTokens, budget - queryTokens} : new int[] {budget - passageTokens, passageTokens};
        }
        int floor = budget / 2;
        int ceil = budget - floor;
        return queryTokens > passageTokens ? new int[] {ceil, floor} : new int[] {floor, ceil};
    }

    /** Tokens in the assembled row of this pair, special tokens included, before padding. */
    int pairWidth(int queryTokens, int passageTokens) {
        int[] kept = keptLengths(queryTokens, passageTokens, maxLength);
        return kept[0] + kept[1] + SPECIAL_TOKENS;
    }

    /**
     * End index (exclusive) of the ONNX Runtime call that starts at {@code from}: rows are added in order while the call's
     * {@code rows x longest width x longest width} stays within {@link #MAX_ATTENTION_CELLS_PER_RUN}; a call always takes at
     * least one row.
     */
    int runEnd(int queryTokens, List<long[]> passages, int from) {
        long width = pairWidth(queryTokens, passages.get(from).length);
        int end = from + 1;
        while (end < passages.size()) {
            long candidate = Math.max(width, pairWidth(queryTokens, passages.get(end).length));
            if ((long) (end - from + 1) * candidate * candidate > MAX_ATTENTION_CELLS_PER_RUN) break;
            width = candidate;
            end++;
        }
        return end;
    }

    /** One row per passage, each {@code query} paired with that passage, cut and assembled as in the class Javadoc. */
    Batch assemble(long[] query, List<long[]> passages) {
        int rows = passages.size();
        int[][] kept = new int[rows][];
        int width = 0;
        int queryKept = Integer.MAX_VALUE;
        for (int row = 0; row < rows; row++) {
            kept[row] = keptLengths(query.length, passages.get(row).length, maxLength);
            width = Math.max(width, kept[row][0] + kept[row][1] + SPECIAL_TOKENS);
            queryKept = Math.min(queryKept, kept[row][0]);
        }
        long[][] ids = new long[rows][width];
        long[][] mask = new long[rows][width];
        long[][] types = new long[rows][width];
        for (int row = 0; row < rows; row++) {
            int q = kept[row][0];
            int p = kept[row][1];
            long[] rowIds = ids[row];
            int at = 0;
            rowIds[at++] = firstId;
            System.arraycopy(query, 0, rowIds, at, q);
            at += q;
            rowIds[at++] = middleId;
            int passageStart = at;
            System.arraycopy(passages.get(row), 0, rowIds, at, p);
            at += p;
            rowIds[at++] = lastId;
            Arrays.fill(rowIds, at, width, padId);
            Arrays.fill(mask[row], 0, at, 1L);
            Arrays.fill(types[row], 0, passageStart, queryTypeId);
            Arrays.fill(types[row], passageStart, at, passageTypeId);
        }
        return new Batch(ids, mask, types, rows == 0 ? Integer.MAX_VALUE : queryKept);
    }
}

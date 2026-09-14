package project.stockrecommendationengine.rag.retrieval;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * <p>
 * Windows ({@link #windows}): the query keeps exactly what the longest-first rule above gives it against the whole passage, so
 * it is never cut more than before; the passage budget left, {@code W = budget - query kept}, is the window length. Under
 * {@link PassageScoring#HEAD} a passage yields one window, its first {@code W} tokens: the same row the longest-first cut
 * produced before windowing, so {@link #assemble(long[], List)} still builds exactly those tensors. Under
 * {@link PassageScoring#MAX_WINDOW} a passage longer than {@code W} yields windows that start at 0 and advance by
 * {@code W - overlap}, the last one moved back to end at the passage's last token as a full {@code W} tokens, at most
 * {@code maxWindows} of them taken from the head ({@link #windowStarts}). Each window is one model row; the scorer takes the
 * maximum logit over a passage's rows. A passage of at most {@code W} tokens is one window under both modes.
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

    /**
     * One model row: the first {@code queryKept} query tokens paired with tokens {@code [start, start + length)} of the passage
     * at index {@code passage} of its group. {@code length} is at most the window length, so a row never exceeds {@code maxLength}.
     */
    record Window(int passage, int queryKept, int start, int length) {
        /** Tokens in the assembled row, special tokens included, before padding. */
        int width() {
            return queryKept + length + SPECIAL_TOKENS;
        }
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

    /**
     * Passage tokens one window holds beside a query of {@code queryTokens} tokens: {@code maxLength - 3} less the query tokens
     * the longest-first rule keeps against the whole passage. Equals the passage tokens kept before windowing whenever the passage
     * is at least that long; 0 only for an empty passage beside a query that fills the budget.
     */
    static int windowLength(int queryTokens, int passageTokens, int maxLength) {
        return maxLength - SPECIAL_TOKENS - keptLengths(queryTokens, passageTokens, maxLength)[0];
    }

    /**
     * Start offsets of the windows that score a passage of {@code passageTokens} tokens with windows of {@code window} tokens.
     * A passage of at most {@code window} tokens (an empty one included) is one window at 0. A longer passage starts a window at
     * 0 and every {@code max(1, window - overlapTokens)} tokens after it while the window does not reach the last token; the final
     * window starts at {@code passageTokens - window}, so it ends exactly at the last token and is a full {@code window} tokens
     * (with {@code overlapTokens} 0 the windows are disjoint except for that final one, which shares tokens with the previous
     * window unless the length divides the passage). Never more than {@code maxWindows} windows, the first ones: a passage longer
     * than they cover is scored on its head only. Starts strictly increase.
     */
    static int[] windowStarts(int passageTokens, int window, int overlapTokens, int maxWindows) {
        if (overlapTokens < 0) throw new IllegalArgumentException("overlapTokens must not be negative: " + overlapTokens);
        if (maxWindows < 1) throw new IllegalArgumentException("maxWindows must be at least 1: " + maxWindows);
        if (passageTokens <= window) return new int[] {0};
        int stride = Math.max(1, window - overlapTokens);
        int[] starts = new int[maxWindows];
        int count = 0;
        for (int start = 0; start + window < passageTokens && count < maxWindows; start += stride) starts[count++] = start;
        if (count < maxWindows) starts[count++] = passageTokens - window;
        return Arrays.copyOf(starts, count);
    }

    /**
     * The rows that score {@code passages} (indexed by position in the list) beside a query of {@code queryTokens} tokens, in
     * passage order and window order within a passage: one head window per passage under {@link PassageScoring#HEAD}, the
     * {@link #windowStarts} windows under {@link PassageScoring#MAX_WINDOW}. Every passage yields at least one row.
     */
    List<Window> windows(int queryTokens, List<long[]> passages, PassageScoring scoring, int overlapTokens, int maxWindows) {
        List<Window> out = new ArrayList<>(passages.size());
        for (int index = 0; index < passages.size(); index++) {
            int passageTokens = passages.get(index).length;
            int queryKept = keptLengths(queryTokens, passageTokens, maxLength)[0];
            int window = maxLength - SPECIAL_TOKENS - queryKept;
            int length = Math.min(passageTokens, window);
            if (scoring == PassageScoring.HEAD) {
                out.add(new Window(index, queryKept, 0, length));
                continue;
            }
            for (int start : windowStarts(passageTokens, window, overlapTokens, maxWindows)) {
                out.add(new Window(index, queryKept, start, length));
            }
        }
        return out;
    }

    /** {@link #windows} under {@link PassageScoring#HEAD}: the one row per passage that the longest-first cut alone produces. */
    List<Window> headWindows(int queryTokens, List<long[]> passages) {
        return windows(queryTokens, passages, PassageScoring.HEAD, 0, 1);
    }

    /**
     * End index (exclusive) of the ONNX Runtime call that starts at row {@code from} of {@code windows}: rows are added in order
     * while the call's {@code rows x longest width x longest width} stays within {@link #MAX_ATTENTION_CELLS_PER_RUN}; a call
     * always takes at least one row.
     */
    int runEnd(List<Window> windows, int from) {
        long width = windows.get(from).width();
        int end = from + 1;
        while (end < windows.size()) {
            long candidate = Math.max(width, windows.get(end).width());
            if ((long) (end - from + 1) * candidate * candidate > MAX_ATTENTION_CELLS_PER_RUN) break;
            width = candidate;
            end++;
        }
        return end;
    }

    /** One head row per passage, each {@code query} paired with that passage, cut and assembled as in the class Javadoc. */
    Batch assemble(long[] query, List<long[]> passages) {
        return assemble(query, passages, headWindows(query.length, passages));
    }

    /**
     * One row per window of {@code windows} (each naming a passage of {@code passages} by index), laid out as in the class
     * Javadoc: the first {@code queryKept} query tokens, then the window's passage tokens, padded to the widest row.
     */
    Batch assemble(long[] query, List<long[]> passages, List<Window> windows) {
        int rows = windows.size();
        int width = 0;
        int queryKept = Integer.MAX_VALUE;
        for (Window window : windows) {
            width = Math.max(width, window.width());
            queryKept = Math.min(queryKept, window.queryKept());
        }
        long[][] ids = new long[rows][width];
        long[][] mask = new long[rows][width];
        long[][] types = new long[rows][width];
        for (int row = 0; row < rows; row++) {
            Window window = windows.get(row);
            int q = window.queryKept();
            int p = window.length();
            long[] rowIds = ids[row];
            int at = 0;
            rowIds[at++] = firstId;
            System.arraycopy(query, 0, rowIds, at, q);
            at += q;
            rowIds[at++] = middleId;
            int passageStart = at;
            System.arraycopy(passages.get(window.passage()), window.start(), rowIds, at, p);
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

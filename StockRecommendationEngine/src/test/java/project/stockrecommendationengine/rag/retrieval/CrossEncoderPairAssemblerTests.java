package project.stockrecommendationengine.rag.retrieval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** The Java-side pair assembly and longest-first truncation that replace native pair encoding; no model or native library. */
class CrossEncoderPairAssemblerTests {
    private static final String BERT_TEMPLATE = """
            {"added_tokens": [{"id": 0, "content": "[PAD]"}, {"id": 100, "content": "[UNK]"}, {"id": 101, "content": "[CLS]"}],
             "padding": %s,
             "post_processor": {"type": "TemplateProcessing",
               "pair": [{"SpecialToken": {"id": "[CLS]", "type_id": 0}}, {"Sequence": {"id": "A", "type_id": 0}},
                        {"SpecialToken": {"id": "[SEP]", "type_id": 0}}, {"Sequence": {"id": "B", "type_id": 1}},
                        {"SpecialToken": {"id": "[SEP]", "type_id": %s}}],
               "special_tokens": {"[CLS]": {"id": "[CLS]", "ids": [101], "tokens": ["[CLS]"]},
                                  "[SEP]": {"id": "[SEP]", "ids": [102], "tokens": ["[SEP]"]}}}}
            """;

    @TempDir
    Path dir;

    /** The rule as specified: remove one token from the end of the longer side until the pair fits; ties per the Javadoc. */
    private static int[] iterative(int query, int passage, int maxLength) {
        boolean queryStartedLonger = query > passage;
        int budget = maxLength - 3;
        while (query + passage > budget) {
            if (query > passage) query--;
            else if (passage > query) passage--;
            else if (queryStartedLonger) passage--;
            else query--;
        }
        return new int[] {query, passage};
    }

    @Test
    void keptLengthsEqualTheIterativeLongestFirstRuleForEveryLengthPair() {
        for (int maxLength : new int[] {16, 17, 20, 33, 64}) {
            for (int query = 0; query <= 3 * maxLength; query++) {
                for (int passage = 0; passage <= 3 * maxLength; passage++) {
                    int[] kept = CrossEncoderPairAssembler.keptLengths(query, passage, maxLength);
                    assertThat(kept).as("q=%d p=%d max=%d", query, passage, maxLength).containsExactly(iterative(query, passage, maxLength));
                    assertThat(kept[0]).isBetween(0, query);
                    assertThat(kept[1]).isBetween(0, passage);
                    assertThat(kept[0] + kept[1]).isLessThanOrEqualTo(maxLength - 3);
                }
            }
        }
    }

    @Test
    void keptLengthsAtTheModelWindowCoverEachBranchAndTie() {
        // Fits: nothing removed, including the exactly full pair.
        assertThat(CrossEncoderPairAssembler.keptLengths(12, 400, 512)).containsExactly(12, 400);
        assertThat(CrossEncoderPairAssembler.keptLengths(100, 409, 512)).containsExactly(100, 409);
        // The shorter side has at most half of the 509-token budget: kept whole, the longer side takes the rest.
        assertThat(CrossEncoderPairAssembler.keptLengths(12, 2_000, 512)).containsExactly(12, 497);
        assertThat(CrossEncoderPairAssembler.keptLengths(2_000, 254, 512)).containsExactly(255, 254);
        assertThat(CrossEncoderPairAssembler.keptLengths(600, 0, 512)).containsExactly(509, 0);
        // Both long: 254 and 255, the extra token to the query only when the query started longer.
        assertThat(CrossEncoderPairAssembler.keptLengths(600, 400, 512)).containsExactly(255, 254);
        assertThat(CrossEncoderPairAssembler.keptLengths(400, 600, 512)).containsExactly(254, 255);
        assertThat(CrossEncoderPairAssembler.keptLengths(700, 700, 512)).containsExactly(254, 255);
        assertThat(CrossEncoderPairAssembler.keptLengths(256, 255, 512)).containsExactly(255, 254);
        assertThat(CrossEncoderPairAssembler.keptLengths(20_000, 20_000, 16)).containsExactly(6, 7);
    }

    @Test
    void assembleLaysOutClsQuerySepPassageSepWithTypesMaskAndPadding() {
        CrossEncoderPairAssembler assembler = new CrossEncoderPairAssembler(101, 102, 102, 0, 0, 1, 16);
        long[] query = {7, 8};
        List<long[]> passages = List.of(new long[] {20, 21, 22}, new long[] {}, new long[] {30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43});
        CrossEncoderPairAssembler.Batch batch = assembler.assemble(query, passages);

        // Row 3 is cut to 11 passage tokens (budget 13), so the batch width is 16.
        assertThat(batch.inputIds()[0]).containsExactly(101, 7, 8, 102, 20, 21, 22, 102, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(batch.tokenTypeIds()[0]).containsExactly(0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(batch.attentionMask()[0]).containsExactly(1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(batch.inputIds()[1]).containsExactly(101, 7, 8, 102, 102, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(batch.tokenTypeIds()[1]).containsExactly(0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(batch.attentionMask()[1]).containsExactly(1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(batch.inputIds()[2]).containsExactly(101, 7, 8, 102, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 102);
        assertThat(batch.tokenTypeIds()[2]).containsExactly(0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        assertThat(batch.attentionMask()[2]).containsOnly(1);
        assertThat(batch.queryTokensKept()).isEqualTo(2);
    }

    @Test
    void aLongQueryIsCutAndTheFewestKeptQueryTokensAreReported() {
        CrossEncoderPairAssembler assembler = new CrossEncoderPairAssembler(1, 2, 3, 9, 0, 1, 16);
        long[] query = new long[40];
        CrossEncoderPairAssembler.Batch batch = assembler.assemble(query, List.of(new long[40], new long[2]));
        assertThat(batch.inputIds()[0]).hasSize(16);
        assertThat(batch.inputIds()[0][0]).isEqualTo(1);
        assertThat(batch.inputIds()[0][7]).isEqualTo(2); // 6 query tokens (floor of 13 / 2, the query did not start longer)
        assertThat(batch.inputIds()[0][15]).isEqualTo(3);
        assertThat(batch.inputIds()[1][12]).isEqualTo(2); // 11 query tokens beside a 2-token passage
        assertThat(batch.queryTokensKept()).isEqualTo(6);
    }

    @Test
    void runEndKeepsRowsTimesWidthSquaredWithinTheBudgetAndAlwaysTakesOneRow() {
        CrossEncoderPairAssembler assembler = new CrossEncoderPairAssembler(101, 102, 102, 0, 0, 1, 512);
        List<long[]> full = new ArrayList<>();
        for (int i = 0; i < 40; i++) full.add(new long[1_000]);
        assertThat(assembler.runEnd(assembler.headWindows(1_000, full), 0)).isEqualTo(8);
        assertThat(assembler.runEnd(assembler.headWindows(1_000, full), 32)).isEqualTo(40);
        List<long[]> shortPairs = new ArrayList<>();
        for (int i = 0; i < 64; i++) shortPairs.add(new long[170]); // 8 + 170 + 3 = 181 tokens per pair
        assertThat(assembler.runEnd(assembler.headWindows(8, shortPairs), 0)).isEqualTo(64);
        List<long[]> mixed = new ArrayList<>(List.of(new long[10], new long[10], new long[2_000], new long[10]));
        assertThat(assembler.runEnd(assembler.headWindows(8, mixed), 0)).isEqualTo(4); // 4 x 512 x 512 fits
        CrossEncoderPairAssembler tiny = new CrossEncoderPairAssembler(101, 102, 102, 0, 0, 1, 512);
        assertThat(tiny.runEnd(tiny.headWindows(0, List.of(new long[5_000])), 0)).isEqualTo(1);
        assertThat(CrossEncoderPairAssembler.MAX_ATTENTION_CELLS_PER_RUN).isEqualTo(8L * 512 * 512);
    }

    // Windows (plan 2026-09-13-reranker-windows, Milestone 1, C1 and C5).

    @Test
    void windowLengthIsTheBudgetLessTheQueryTokensTheLongestFirstRuleKeeps() {
        assertThat(CrossEncoderPairAssembler.windowLength(25, 1_000, 512)).isEqualTo(484); // short query kept whole: 509 - 25
        assertThat(CrossEncoderPairAssembler.windowLength(25, 100, 512)).isEqualTo(484); // the same window whether or not the chunk fills it
        assertThat(CrossEncoderPairAssembler.windowLength(600, 1_000, 512)).isEqualTo(255); // both long: the query keeps 254
        assertThat(CrossEncoderPairAssembler.windowLength(1_000, 600, 512)).isEqualTo(254); // the query started longer and keeps 255
        assertThat(CrossEncoderPairAssembler.windowLength(2_000, 100, 512)).isEqualTo(100); // short chunk kept whole, the query takes the rest
        assertThat(CrossEncoderPairAssembler.windowLength(600, 0, 512)).isEqualTo(0); // only an empty chunk beside a query filling the budget
        assertThat(CrossEncoderPairAssembler.windowLength(6, 40, 16)).isEqualTo(7);
    }

    @Test
    void windowStartsCoverTheChunkAdvanceByWindowLessOverlapAndEndAtTheLastToken() {
        // Fits (or is empty): one window at 0.
        assertThat(CrossEncoderPairAssembler.windowStarts(0, 484, 64, 4)).containsExactly(0);
        assertThat(CrossEncoderPairAssembler.windowStarts(484, 484, 64, 4)).containsExactly(0);
        assertThat(CrossEncoderPairAssembler.windowStarts(300, 484, 64, 4)).containsExactly(0);
        // One token over: the second window is moved back to end at the last token as a full window.
        assertThat(CrossEncoderPairAssembler.windowStarts(485, 484, 64, 4)).containsExactly(0, 1);
        // The defaults on the longest stored chunk (1,214 tokens beside a 25-token question): three windows, the last moved back.
        assertThat(CrossEncoderPairAssembler.windowStarts(1_214, 484, 64, 4)).containsExactly(0, 420, 730);
        // Regular advance W - overlap while the window does not reach the end, then the last window at length - W.
        assertThat(CrossEncoderPairAssembler.windowStarts(10, 7, 2, 4)).containsExactly(0, 3);
        assertThat(CrossEncoderPairAssembler.windowStarts(20, 7, 2, 8)).containsExactly(0, 5, 10, 13);
        // Overlap 0: disjoint windows; the last is moved back only when the length does not divide the chunk.
        assertThat(CrossEncoderPairAssembler.windowStarts(21, 7, 0, 8)).containsExactly(0, 7, 14);
        assertThat(CrossEncoderPairAssembler.windowStarts(20, 7, 0, 8)).containsExactly(0, 7, 13);
        // An overlap of the window length or more still advances one token at a time.
        assertThat(CrossEncoderPairAssembler.windowStarts(10, 7, 64, 16)).containsExactly(0, 1, 2, 3);
        // Cap: the first max-windows windows only, so a chunk longer than they cover is scored on its head.
        assertThat(CrossEncoderPairAssembler.windowStarts(5_000, 484, 64, 4)).containsExactly(0, 420, 840, 1_260);
        assertThat(CrossEncoderPairAssembler.windowStarts(5_000, 484, 64, 1)).containsExactly(0);
        assertThat(CrossEncoderPairAssembler.windowStarts(20_000, 7, 0, 16)).hasSize(16).startsWith(0, 7, 14);
        assertThatIllegalArgumentException().isThrownBy(() -> CrossEncoderPairAssembler.windowStarts(10, 7, -1, 4));
        assertThatIllegalArgumentException().isThrownBy(() -> CrossEncoderPairAssembler.windowStarts(10, 7, 0, 0));
    }

    @Test
    void windowStartsSatisfyTheContractForEveryLengthWindowAndOverlap() {
        for (int window = 1; window <= 40; window++) {
            for (int overlap = 0; overlap <= 12; overlap++) {
                for (int length = 0; length <= 130; length++) {
                    for (int maxWindows : new int[] {1, 2, 4, 16}) {
                        int[] starts = CrossEncoderPairAssembler.windowStarts(length, window, overlap, maxWindows);
                        String as = "length=%d window=%d overlap=%d maxWindows=%d".formatted(length, window, overlap, maxWindows);
                        assertThat(starts.length).as(as).isBetween(1, maxWindows);
                        assertThat(starts[0]).as(as).isZero();
                        int stride = Math.max(1, window - overlap);
                        for (int i = 1; i < starts.length; i++) {
                            assertThat(starts[i]).as(as).isGreaterThan(starts[i - 1]).isLessThanOrEqualTo(length - window);
                            if (i < starts.length - 1) assertThat(starts[i]).as(as).isEqualTo(starts[i - 1] + stride);
                            else assertThat(starts[i]).as(as).isIn(starts[i - 1] + stride, length - window);
                        }
                        int[] uncapped = CrossEncoderPairAssembler.windowStarts(length, window, overlap, 1_000); // more than any length here needs
                        if (length > window && starts.length == uncapped.length) {
                            // Not cut by the cap: the last window ends exactly at the last token and every token is covered.
                            assertThat(starts[starts.length - 1] + window).as(as).isEqualTo(length);
                            boolean[] covered = new boolean[length];
                            for (int start : starts) for (int t = start; t < start + window; t++) covered[t] = true;
                            for (int t = 0; t < length; t++) assertThat(covered[t]).as(as + " token " + t).isTrue();
                        } else if (length > window) {
                            assertThat(starts).as(as).containsExactly(Arrays.copyOf(uncapped, maxWindows));
                        }
                    }
                }
            }
        }
    }

    @Test
    void aChunkThatFitsTheWindowYieldsOneWindowWithExactlyTheHeadTensorsUnderBothModes() {
        CrossEncoderPairAssembler assembler = new CrossEncoderPairAssembler(101, 102, 102, 0, 0, 1, 16);
        long[] query = {7, 8};
        List<long[]> passages = List.of(new long[] {20, 21, 22}, new long[] {}, ids(30, 11), new long[40]); // 11 = W exactly
        List<CrossEncoderPairAssembler.Window> windows = assembler.windows(query.length, passages, PassageScoring.MAX_WINDOW, 3, 4);
        // The 40-token chunk: stride 8, windows at 0, 8, 16, 24, the cap of 4 reached before the end.
        assertThat(windows).hasSize(7).extracting(CrossEncoderPairAssembler.Window::passage).containsExactly(0, 1, 2, 3, 3, 3, 3);
        assertThat(windows.subList(3, 7)).extracting(CrossEncoderPairAssembler.Window::start).containsExactly(0, 8, 16, 24);
        assertThat(windows.subList(0, 3)).containsExactly(new CrossEncoderPairAssembler.Window(0, 2, 0, 3),
                new CrossEncoderPairAssembler.Window(1, 2, 0, 0), new CrossEncoderPairAssembler.Window(2, 2, 0, 11));
        CrossEncoderPairAssembler.Batch head = assembler.assemble(query, passages.subList(0, 3));
        CrossEncoderPairAssembler.Batch windowed = assembler.assemble(query, passages, windows.subList(0, 3));
        assertThat(windowed.inputIds()).isDeepEqualTo(head.inputIds());
        assertThat(windowed.attentionMask()).isDeepEqualTo(head.attentionMask());
        assertThat(windowed.tokenTypeIds()).isDeepEqualTo(head.tokenTypeIds());
        assertThat(windowed.queryTokensKept()).isEqualTo(head.queryTokensKept()).isEqualTo(2);
        assertThat(windowed.inputIds()[0]).hasSize(16); // width from the longest row, the full 11-token window
        assertThat(windowed.inputIds()[1]).containsExactly(101, 7, 8, 102, 102, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0); // the empty chunk
        // Head mode: one window per passage, the first W tokens, the same rows assemble(query, passages) builds.
        List<CrossEncoderPairAssembler.Window> headWindows = assembler.windows(query.length, passages, PassageScoring.HEAD, 3, 4);
        assertThat(headWindows).containsExactlyElementsOf(assembler.headWindows(query.length, passages));
        assertThat(headWindows.get(3)).isEqualTo(new CrossEncoderPairAssembler.Window(3, 2, 0, 11));
        CrossEncoderPairAssembler.Batch headAll = assembler.assemble(query, passages);
        assertThat(assembler.assemble(query, passages, headWindows).inputIds()).isDeepEqualTo(headAll.inputIds());
    }

    @Test
    void windowsOfALongChunkHoldItsTokensInOrderAndTheLastEndsAtItsLastToken() {
        CrossEncoderPairAssembler assembler = new CrossEncoderPairAssembler(101, 102, 102, 0, 0, 1, 16);
        long[] query = {7, 8}; // budget 13, W = 11
        long[] passage = ids(100, 30); // tokens 100 to 129
        List<CrossEncoderPairAssembler.Window> windows = assembler.windows(2, List.of(passage), PassageScoring.MAX_WINDOW, 3, 4);
        // Stride 8: starts 0, 8, then 16 + 11 = 27 < 30 gives 16, then 24 + 11 reaches the end, so the last window starts at 19.
        assertThat(windows).extracting(CrossEncoderPairAssembler.Window::start).containsExactly(0, 8, 16, 19);
        assertThat(windows).allSatisfy(w -> assertThat(w.length()).isEqualTo(11));
        CrossEncoderPairAssembler.Batch batch = assembler.assemble(query, List.of(passage), windows);
        assertThat(batch.inputIds()).hasDimensions(4, 16);
        assertThat(batch.inputIds()[0]).containsExactly(101, 7, 8, 102, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 102);
        assertThat(batch.inputIds()[1]).containsExactly(101, 7, 8, 102, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 102);
        assertThat(batch.inputIds()[3]).containsExactly(101, 7, 8, 102, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 102);
        assertThat(batch.tokenTypeIds()[3]).containsExactly(0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        assertThat(batch.attentionMask()[3]).containsOnly(1);
        assertThat(batch.queryTokensKept()).isEqualTo(2);
        // Overlap 0 with a length the window divides: disjoint windows.
        assertThat(assembler.windows(2, List.of(ids(100, 33)), PassageScoring.MAX_WINDOW, 0, 4))
                .extracting(CrossEncoderPairAssembler.Window::start).containsExactly(0, 11, 22);
    }

    @Test
    void aCutQueryKeepsWhatTheLongestFirstRuleGivesItAgainstTheWholeChunkInEveryWindow() {
        CrossEncoderPairAssembler assembler = new CrossEncoderPairAssembler(101, 102, 102, 0, 0, 1, 512);
        long[] query = new long[600];
        List<long[]> passages = List.of(new long[1_000], new long[100], new long[254]);
        List<CrossEncoderPairAssembler.Window> windows = assembler.windows(600, passages, PassageScoring.MAX_WINDOW, 64, 4);
        // 1,000 tokens beside a 600-token query: both long, the query keeps 254, W = 255, stride 191: 0, 191, 382, 573 (cap 4).
        assertThat(windows.subList(0, 4)).extracting(CrossEncoderPairAssembler.Window::queryKept).containsOnly(254);
        assertThat(windows.subList(0, 4)).extracting(CrossEncoderPairAssembler.Window::start).containsExactly(0, 191, 382, 573);
        assertThat(windows.subList(0, 4)).extracting(CrossEncoderPairAssembler.Window::length).containsOnly(255);
        // 100 tokens: kept whole, the query takes 409; 254 tokens: the shorter side, kept whole, the query takes 255.
        assertThat(windows.get(4)).isEqualTo(new CrossEncoderPairAssembler.Window(1, 409, 0, 100));
        assertThat(windows.get(5)).isEqualTo(new CrossEncoderPairAssembler.Window(2, 255, 0, 254));
        CrossEncoderPairAssembler.Batch batch = assembler.assemble(query, passages, windows);
        assertThat(batch.inputIds()[0]).hasSize(512);
        assertThat(batch.queryTokensKept()).isEqualTo(254);
        assertThat(windows).allSatisfy(w -> assertThat(w.width()).isLessThanOrEqualTo(512));
    }

    @Test
    void windowsAreRowsUnderTheSamePerCallAttentionCapAndALongChunkYieldsAtMostMaxWindows() {
        CrossEncoderPairAssembler assembler = new CrossEncoderPairAssembler(101, 102, 102, 0, 0, 1, 512);
        List<long[]> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) twenty.add(new long[1_000]);
        // A 12-token question: W = 497, stride 433: 0, 433, then 866 + 497 reaches the end, so the last starts at 503: 3 windows each.
        List<CrossEncoderPairAssembler.Window> windows = assembler.windows(12, twenty, PassageScoring.MAX_WINDOW, 64, 4);
        assertThat(windows).hasSize(60);
        assertThat(windows.subList(0, 3)).extracting(CrossEncoderPairAssembler.Window::start).containsExactly(0, 433, 503);
        assertThat(assembler.runEnd(windows, 0)).isEqualTo(8).isEqualTo(assembler.runEnd(assembler.headWindows(12, twenty), 0)); // eight 512-token rows, as before
        int calls = 0;
        for (int from = 0; from < windows.size(); calls++) {
            int end = assembler.runEnd(windows, from);
            long width = windows.subList(from, end).stream().mapToLong(CrossEncoderPairAssembler.Window::width).max().orElseThrow();
            assertThat((end - from) * width * width).isLessThanOrEqualTo(CrossEncoderPairAssembler.MAX_ATTENTION_CELLS_PER_RUN);
            assertThat(end - from).isLessThanOrEqualTo(8);
            from = end;
        }
        assertThat(calls).isEqualTo(8); // 60 rows in calls of 8 (7 full calls and one of 4)
        // A 20,000-token chunk (the character bound admits at most 20,000 tokens) yields max-windows windows, no more.
        for (int maxWindows : new int[] {1, 4, 16}) {
            assertThat(assembler.windows(12, List.of(new long[20_000]), PassageScoring.MAX_WINDOW, 64, maxWindows)).hasSize(maxWindows);
        }
        assertThat(assembler.windows(12, List.of(new long[20_000]), PassageScoring.HEAD, 64, 16)).hasSize(1);
    }

    private static long[] ids(int first, int count) {
        long[] out = new long[count];
        for (int i = 0; i < count; i++) out[i] = first + i;
        return out;
    }

    @Test
    void specialAndPadIdsAreReadFromTokenizerJson() throws Exception {
        Path noPadding = write("a.json", BERT_TEMPLATE.formatted("null", "1"));
        assertThat(CrossEncoderPairAssembler.fromTokenizerJson(noPadding, 512).specialIds()).containsExactly(101, 102, 102, 0);
        Path padding = write("b.json", BERT_TEMPLATE.formatted("{\"pad_id\": 7, \"pad_token\": \"<pad>\"}", "1"));
        CrossEncoderPairAssembler assembler = CrossEncoderPairAssembler.fromTokenizerJson(padding, 16);
        assertThat(assembler.specialIds()).containsExactly(101, 102, 102, 7);
        assertThat(assembler.assemble(new long[] {5}, List.of(new long[] {6}, new long[] {6, 6})).inputIds()[0]).containsExactly(101, 5, 102, 6, 102, 7);
    }

    @Test
    void anUnsupportedTemplateFailsNamingThePath() throws Exception {
        Path wrongType = write("c.json", BERT_TEMPLATE.formatted("null", "0"));
        assertThatIllegalStateException().isThrownBy(() -> CrossEncoderPairAssembler.fromTokenizerJson(wrongType, 512))
                .withMessageContaining(wrongType.toString()).withMessageContaining("typed A, A, B");
        Path noTemplate = write("d.json", "{\"post_processor\": {\"type\": \"BertProcessing\"}}");
        assertThatIllegalStateException().isThrownBy(() -> CrossEncoderPairAssembler.fromTokenizerJson(noTemplate, 512))
                .withMessageContaining(noTemplate.toString());
        Path noPad = write("e.json", BERT_TEMPLATE.formatted("null", "1").replace("\"[PAD]\"", "\"<nothing>\""));
        assertThatIllegalStateException().isThrownBy(() -> CrossEncoderPairAssembler.fromTokenizerJson(noPad, 512))
                .withMessageContaining("[PAD]");
    }

    private Path write(String name, String content) throws Exception {
        return Files.writeString(dir.resolve(name), content);
    }
}

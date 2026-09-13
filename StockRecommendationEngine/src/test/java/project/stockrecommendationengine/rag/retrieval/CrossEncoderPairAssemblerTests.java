package project.stockrecommendationengine.rag.retrieval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        assertThat(assembler.runEnd(1_000, full, 0)).isEqualTo(8);
        assertThat(assembler.runEnd(1_000, full, 32)).isEqualTo(40);
        List<long[]> shortPairs = new ArrayList<>();
        for (int i = 0; i < 64; i++) shortPairs.add(new long[170]); // 8 + 170 + 3 = 181 tokens per pair
        assertThat(assembler.runEnd(8, shortPairs, 0)).isEqualTo(64);
        List<long[]> mixed = new ArrayList<>(List.of(new long[10], new long[10], new long[2_000], new long[10]));
        assertThat(assembler.runEnd(8, mixed, 0)).isEqualTo(4); // 4 x 512 x 512 fits
        CrossEncoderPairAssembler tiny = new CrossEncoderPairAssembler(101, 102, 102, 0, 0, 1, 512);
        assertThat(tiny.runEnd(0, List.of(new long[5_000]), 0)).isEqualTo(1);
        assertThat(CrossEncoderPairAssembler.MAX_ATTENTION_CELLS_PER_RUN).isEqualTo(8L * 512 * 512);
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

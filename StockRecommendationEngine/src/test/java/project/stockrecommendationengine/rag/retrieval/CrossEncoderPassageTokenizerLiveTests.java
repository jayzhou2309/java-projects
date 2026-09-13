package project.stockrecommendationengine.rag.retrieval;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderTokenPositions.Scoring;
import static org.assertj.core.api.Assertions.*;

/**
 * Opt-in (-Drag.rerank.live=true, model files under models/, no database, no Spring context): the evidence report's token positions on
 * the real tokenizer and scorer (evaluation evidence Milestone 2). {@link CrossEncoderPassageTokenizer} must report the scorer's token
 * count for a text, with character spans that select each token's own characters in UTF-16 offsets (the native spans are code point
 * offsets, so an emoji shifts every later token by one unit); and {@link CrossEncoderTokenPositions}' window arithmetic must give, for every
 * passage, the number of rows the real scorer ran for it ({@link PairScorer.Scored#windowScores}), under head, max-window with overlap 64
 * and 224, at max-length 512 and 64. Each scorer's recorded scoring label parses back to its settings.
 */
@EnabledIfSystemProperty(named = "rag.rerank.live", matches = "true")
class CrossEncoderPassageTokenizerLiveTests {
    private static final String EXPECTED_MODEL_SHA256 = "5d3e70fd0c9ff14b9b5169a51e957b7a9c74897afd0a35ce4bd318150c1d4d4a";
    private static final String EMOJI = Character.toString(0x1F600);
    private static final String NBSP = Character.toString(0x00A0);
    private static final String LEFT_QUOTE = Character.toString(0x201C);
    private static final String RIGHT_QUOTE = Character.toString(0x201D);

    private static CrossEncoderModelFiles files;
    private static CrossEncoderPassageTokenizer tokenizer;
    private static HuggingFaceTokenizer counter;

    @BeforeAll
    static void load() throws Exception {
        Path dir = Path.of(System.getProperty("rag.rerank.model-dir", "models/cross-encoder-ms-marco-MiniLM-L-6-v2"));
        CrossEncoderProperties properties = new CrossEncoderProperties();
        properties.setEnabled(true);
        properties.setModelPath(dir.resolve("model.onnx").toString());
        properties.setTokenizerPath(dir.resolve("tokenizer.json").toString());
        properties.setModelSha256(System.getProperty("rag.rerank.model-sha256", EXPECTED_MODEL_SHA256));
        files = CrossEncoderModelFiles.verify(properties);
        tokenizer = new CrossEncoderPassageTokenizer(files.tokenizerPath(), files.version());
        counter = OnnxCrossEncoderScorer.singleSequenceTokenizer(files.tokenizerPath());
        assertThat(tokenizer.modelVersion()).isEqualTo("5d3e70fd0c9f");
    }

    @AfterAll
    static void close() {
        if (tokenizer != null) tokenizer.close();
        if (counter != null) counter.close();
    }

    @Test
    void spansAreUtf16OffsetsThatSelectEachTokensOwnCharacters() {
        List<String> texts = List.of(
                "Revenue $ 215,938 $ 130,497 Up 65%",
                "a " + EMOJI + " revenue " + EMOJI + EMOJI + " growth after emoji",
                LEFT_QUOTE + "Caf" + Character.toString(0x00E9) + RIGHT_QUOTE + NBSP + "revenue grew" + NBSP + "12%",
                Character.toString(0x4E2D) + Character.toString(0x6587) + " revenue",
                "  we employed approximately 223,000 people on a full-time basis,\n121,000 in the U.S. and 102,000 internationally");
        int checked = 0;
        for (String text : texts) {
            PassageTokenizer.Tokens tokens = tokenizer.tokenize(text);
            Encoding encoding = counter.encode(text, false, false);
            assertThat(tokens.count()).as(text).isEqualTo(encoding.getIds().length);
            String[] strings = encoding.getTokens();
            List<String> line = new ArrayList<>();
            for (int token = 0; token < tokens.count(); token++) {
                int start = tokens.starts()[token];
                int end = tokens.ends()[token];
                assertThat(start).as(text + " token " + token).isBetween(0, end);
                assertThat(end).as(text + " token " + token).isLessThanOrEqualTo(text.length());
                if (token > 0) assertThat(start).isGreaterThanOrEqualTo(tokens.starts()[token - 1]);
                String selected = text.substring(start, end);
                line.add(strings[token] + "=" + start + "-" + end);
                if (strings[token].equals("[UNK]")) {
                    assertThat(selected.codePoints().allMatch(cp -> cp == 0x1F600)).as(text + " " + selected).isTrue();
                } else {
                    assertThat(fold(selected)).as(text + " token " + token + " " + strings[token]).isEqualTo(strings[token].replaceFirst("^##", ""));
                    checked++;
                }
            }
            System.out.println("EVIDENCE_TOKENIZER text=" + text.replace("\n", "\\n") + " tokens=" + line);
        }
        assertThat(checked).isGreaterThan(40);
        PassageTokenizer.Tokens emoji = tokenizer.tokenize(texts.get(1));
        assertThat(texts.get(1).substring(emoji.starts()[2], emoji.ends()[2])).isEqualTo("revenue");
        assertThat(emoji.starts()[2]).isEqualTo(5);
    }

    @Test
    void theWindowArithmeticGivesTheRowsTheScorerRanForEveryPassage() throws Exception {
        String query = "What are Microsoft's three reportable segments in its fiscal 2026 annual report?";
        List<String> passages = new ArrayList<>(List.of("", "Segments are reported.", prose(470), prose(476), prose(900), prose(2600)));
        List<Scoring> settings = List.of(Scoring.head(), Scoring.maxWindow(64, 4), Scoring.maxWindow(224, 4), Scoring.maxWindow(0, 16));
        for (int maxLength : new int[] {512, 64}) {
            for (Scoring scoring : settings) {
                try (OnnxCrossEncoderScorer scorer = new OnnxCrossEncoderScorer(files.modelPath(), files.tokenizerPath(), maxLength, 20, scoring.mode(),
                        scoring.windowOverlapTokens() == null ? 64 : scoring.windowOverlapTokens(), scoring.maxWindows() == null ? 4 : scoring.maxWindows())) {
                    assertThat(Scoring.parse(scorer.scoring())).isEqualTo(scoring);
                    PairScorer.Scored scored = scorer.scoreWithWindows(query, passages);
                    int queryTokens = tokenizer.tokenize(CrossEncoderTokenPositions.bound(query)).count();
                    int total = 0;
                    List<String> rows = new ArrayList<>();
                    for (int index = 0; index < passages.size(); index++) {
                        int passageTokens = tokenizer.tokenize(CrossEncoderTokenPositions.bound(passages.get(index))).count();
                        int w = CrossEncoderTokenPositions.windowLength(queryTokens, passageTokens, maxLength);
                        int expected = CrossEncoderTokenPositions.windowStarts(passageTokens, w, scoring).length;
                        assertThat(scored.windowScores()[index]).as("maxLength %d %s passage %d (%d tokens, W %d)", maxLength, scoring.label(), index,
                                passageTokens, w).hasSize(expected);
                        total += expected;
                        rows.add(passageTokens + "t/W" + w + "/" + expected + "rows");
                    }
                    assertThat(scored.windows()).isEqualTo(total);
                    System.out.println("EVIDENCE_WINDOWS maxLength=" + maxLength + " scoring=" + scorer.scoring() + " queryTokens=" + queryTokens
                            + " rows=" + scored.windows() + " passages=" + rows);
                }
            }
        }
    }

    /** Lower-cased, accents removed, as this uncased WordPiece vocabulary normalises text. */
    private static String fold(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** Filing-style prose of at least {@code tokens} WordPiece tokens. */
    private static String prose(int tokens) {
        String sentence = "Our segments reflect how management allocates resources and evaluates performance across the business. ";
        StringBuilder out = new StringBuilder();
        while (counter.encode(out.toString(), false, false).getIds().length < tokens) out.append(sentence);
        return out.toString().trim();
    }
}

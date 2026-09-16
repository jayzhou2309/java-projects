package project.stockrecommendationengine.rag.retrieval;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderTokenPositions.HeadMembership;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderTokenPositions.Scoring;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderTokenPositions.TokenSpan;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Opt-in (-Drag.rerank.gte.live=true; files under models/gte-reranker-modernbert-base/; the project .env exported for the database;
 * no Spring context, no embedding or chat model call): the second reranker model on real text (plan 2026-09-15-reranker-ettin,
 * Milestone 1, E4 and E5 as amended). Real text is evaluation set v2's questions and the stored chunks of {@code sec_filing_chunks}
 * that hold their accepted phrases, read over JDBC.
 * <ul>
 * <li>Signature: the downloaded model passes {@link GteRerankerModelInspector} (inputs input_ids and attention_mask, one output of
 * shape [batch, 1]).</li>
 * <li>Rows against the tokenizer's own pair encoding, with the profile's settings (max-length 512, max-window, overlap 64, 4 windows),
 * for every (question, holding chunk) pair and again with a query of at least 450 tokens built from the question: a pair that fits
 * is one row equal to the native pair encoding (special tokens, no truncation) id for id with type ids and mask; the head row of a
 * longer pair equals the native encoding with longest_first truncation at 512; every row is {@code [CLS]} + the query tokens kept +
 * {@code [SEP]} + its window of the chunk + {@code [SEP]}, where the query and chunk tokens equal the segments of the native
 * untruncated pair encoding; window starts equal {@link CrossEncoderTokenPositions#windowStarts}; no row exceeds 512 tokens; rows
 * assembled as padded batches keep the same prefix and pad with 50283 and mask 0.</li>
 * <li>A phrase across the head window's boundary (characters of chunk tokens W-3 to W+3 of the longest holding chunk): through
 * {@link GteRerankerPassageTokenizer}'s spans it is partly in the head, wholly in row 2 only, and row 2 carries its token ids.</li>
 * <li>Scoring smoke check through the unchanged {@link OnnxCrossEncoderScorer} on this model's files: a relevant passage scores above
 * an irrelevant one; a passage's score alone equals its score in a padded batch within 1e-3; the scoring time of 20 and 40 of the
 * longest stored chunks is printed ({@code GTE_RERANKER timing}).</li>
 * </ul>
 * Prints {@code GTE_RERANKER} lines.
 */
@EnabledIfSystemProperty(named = "rag.rerank.gte.live", matches = "true")
class GteRerankerLiveTests {
    private static final String MODEL_SHA256 = "c6d3226502addbcd4d2cf273802957ebf8a2a6bf94037dcb9b1d95bfc01e5d93";
    private static final String TOKENIZER_SHA256 = "2aea6ff4701d063e7e029b6be695a1659f2caaa2ae4fb0e8b18285818271becd";
    private static final int MAX_LENGTH = 512;
    private static final int OVERLAP = 64;
    private static final int MAX_WINDOWS = 4;
    private static final long CLS = 50281;
    private static final long SEP = 50282;
    private static final long PAD = 50283;

    private static GteRerankerModelFiles files;
    private static HuggingFaceTokenizer single;
    private static HuggingFaceTokenizer nativeTruncating;
    private static HuggingFaceTokenizer nativeWhole;
    private static CrossEncoderPairAssembler assembler;
    private static GteRerankerPassageTokenizer passageTokenizer;
    private static final List<String[]> pairs = new ArrayList<>();
    private static final List<String> chunkTexts = new ArrayList<>();

    @BeforeAll
    static void load() throws Exception {
        Path dir = Path.of(System.getProperty("rag.rerank.gte.model-dir", "models/gte-reranker-modernbert-base"));
        GteRerankerProperties properties = new GteRerankerProperties();
        properties.setEnabled(true);
        properties.setModelPath(dir.resolve("model.onnx").toString());
        properties.setTokenizerPath(dir.resolve("tokenizer.json").toString());
        properties.setModelSha256(MODEL_SHA256);
        properties.setTokenizerSha256(TOKENIZER_SHA256);
        files = GteRerankerModelFiles.verify(properties);
        DjlRuntimeDefaults.apply();
        OnnxCrossEncoderScorer.requireBundledTokenizerLibrary();
        single = OnnxCrossEncoderScorer.singleSequenceTokenizer(files.tokenizerPath());
        nativeTruncating = HuggingFaceTokenizer.builder().optTokenizerPath(files.tokenizerPath()).optAddSpecialTokens(true).optTruncation(true)
                .optMaxLength(MAX_LENGTH).optPadding(false).build();
        nativeWhole = HuggingFaceTokenizer.builder().optTokenizerPath(files.tokenizerPath()).optAddSpecialTokens(true).optTruncation(false)
                .optPadding(false).build();
        assembler = CrossEncoderPairAssembler.fromTokenizerJson(files.tokenizerPath(), MAX_LENGTH);
        passageTokenizer = new GteRerankerPassageTokenizer(files.tokenizerPath(), files.version(), MAX_LENGTH);
        System.out.println("GTE_RERANKER tokenizers single=" + single.getTruncation() + "/" + single.getPadding() + " nativeTruncating="
                + nativeTruncating.getTruncation() + "/" + nativeTruncating.getPadding() + " maxLength=" + nativeTruncating.getMaxLength()
                + " nativeWhole=" + nativeWhole.getTruncation() + "/" + nativeWhole.getPadding() + " specialIds=" + Arrays.toString(assembler.specialIds()));
        readRealText();
    }

    @AfterAll
    static void close() {
        for (AutoCloseable closeable : new AutoCloseable[] {single, nativeTruncating, nativeWhole, passageTokenizer}) {
            try {
                if (closeable != null) closeable.close();
            } catch (Exception ignored) {
                // test teardown
            }
        }
    }

    /** Set v2's questions paired with every stored chunk (same accession and section) whose normalised text holds an accepted phrase. */
    private static void readRealText() throws Exception {
        JsonNode set = JsonMapper.builder().build().readTree(Files.readString(Path.of("src/main/resources/evaluation/retrieval-set-v2.json")));
        String url = "jdbc:postgresql://localhost:" + env("POSTGRES_PORT", "5432") + "/" + env("POSTGRES_DB", "rag_db");
        Map<String, List<String>> bySection = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(url, env("POSTGRES_USER", "root"), env("POSTGRES_PASSWORD", "root123"));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select f.accession_no, c.section_key, c.content from sec_filing_chunks c "
                     + "join sec_filings f on f.id = c.filing_id order by c.id")) {
            while (rows.next()) {
                bySection.computeIfAbsent(rows.getString(1) + "|" + rows.getString(2), key -> new ArrayList<>()).add(rows.getString(3));
                chunkTexts.add(rows.getString(3));
            }
        }
        for (JsonNode question : set.path("questions")) {
            for (JsonNode expected : question.path("expected")) {
                String phrase = normalise(expected.path("phrase").asString());
                for (String content : bySection.getOrDefault(expected.path("accessionNo").asString() + "|" + expected.path("sectionKey").asString(), List.of())) {
                    if (normalise(content).contains(phrase)) pairs.add(new String[] {question.path("id").asString(), question.path("question").asString(), content});
                }
            }
        }
        System.out.println("GTE_RERANKER realText storedChunks=" + chunkTexts.size() + " questionChunkPairs=" + pairs.size());
        assertThat(pairs).as("set v2 pairs with a stored holding chunk").hasSizeGreaterThanOrEqualTo(40);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String normalise(String text) {
        return text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static long[] ids(String text) {
        return single.encode(CrossEncoderInputBounds.bound(text), false, false).getIds();
    }

    /** The question repeated until it has at least 450 tokens, so the query fills most of the 509-token budget. */
    private static String longQuery(String question) {
        StringBuilder out = new StringBuilder(question);
        while (ids(out.toString()).length < 450) out.append(' ').append(question);
        return out.toString();
    }

    @Test
    void theDownloadedModelPassesTheStartupSignatureCheck() {
        assertThatCode(() -> GteRerankerModelInspector.requireSignature(files.modelPath())).doesNotThrowAnyException();
        assertThat(files.version()).isEqualTo("c6d3226502ad");
        assertThat(passageTokenizer.maxLength()).isEqualTo(new PassageTokenizer.MaxLength(512, GteRerankerPassageTokenizer.MAX_LENGTH_SOURCE));
    }

    @Test
    void everyRowEqualsTheTokenizersOwnPairEncodingOnRealQuestionsAndChunks() {
        int checkedPairs = 0;
        int wholePairs = 0;
        int truncatedHeads = 0;
        int rows = 0;
        int longQueryPairs = 0;
        int maxRowWidth = 0;
        int minLongQueryWindow = Integer.MAX_VALUE;
        Scoring scoring = Scoring.maxWindow(OVERLAP, MAX_WINDOWS);
        for (String[] pair : pairs) {
            for (String query : List.of(pair[1], longQuery(pair[1]))) {
                boolean isLong = !query.equals(pair[1]);
                long[] queryIds = ids(query);
                long[] passageIds = ids(pair[2]);
                if (isLong) {
                    assertThat(queryIds.length).isGreaterThanOrEqualTo(450);
                    longQueryPairs++;
                }
                // Separate tokenization equals the native pair encoding's segments.
                Encoding whole = nativeWhole.encode(query, pair[2], true, false);
                long[] wholeIds = whole.getIds();
                assertThat(wholeIds.length).as(pair[0]).isEqualTo(queryIds.length + passageIds.length + 3);
                assertThat(wholeIds[0]).isEqualTo(CLS);
                assertThat(wholeIds[queryIds.length + 1]).isEqualTo(SEP);
                assertThat(wholeIds[wholeIds.length - 1]).isEqualTo(SEP);
                assertThat(Arrays.copyOfRange(wholeIds, 1, 1 + queryIds.length)).as(pair[0] + " query segment").containsExactly(queryIds);
                assertThat(Arrays.copyOfRange(wholeIds, queryIds.length + 2, wholeIds.length - 1)).as(pair[0] + " chunk segment").containsExactly(passageIds);
                assertThat(whole.getTypeIds()).containsOnly(0L);

                List<CrossEncoderPairAssembler.Window> windows = assembler.windows(queryIds.length, List.of(passageIds), PassageScoring.MAX_WINDOW, OVERLAP, MAX_WINDOWS);
                int w = CrossEncoderTokenPositions.windowLength(queryIds.length, passageIds.length, MAX_LENGTH);
                if (isLong) minLongQueryWindow = Math.min(minLongQueryWindow, w);
                assertThat(windows).extracting(CrossEncoderPairAssembler.Window::start).as(pair[0])
                        .containsExactly(Arrays.stream(CrossEncoderTokenPositions.windowStarts(passageIds.length, w, scoring)).boxed().toArray(Integer[]::new));
                CrossEncoderPairAssembler.Batch batch = assembler.assemble(queryIds, List.of(passageIds), windows);
                Encoding head = nativeTruncating.encode(query, pair[2], true, false);
                for (int row = 0; row < windows.size(); row++) {
                    CrossEncoderPairAssembler.Window window = windows.get(row);
                    long[] expected = new long[window.width()];
                    int at = 0;
                    expected[at++] = CLS;
                    System.arraycopy(queryIds, 0, expected, at, window.queryKept());
                    at += window.queryKept();
                    expected[at++] = SEP;
                    System.arraycopy(passageIds, window.start(), expected, at, window.length());
                    at += window.length();
                    expected[at] = SEP;
                    long[] actual = Arrays.copyOf(batch.inputIds()[row], window.width());
                    assertThat(actual).as(pair[0] + " row " + row).containsExactly(expected);
                    assertThat(window.width()).isLessThanOrEqualTo(MAX_LENGTH);
                    for (int i = window.width(); i < batch.inputIds()[row].length; i++) {
                        assertThat(batch.inputIds()[row][i]).isEqualTo(PAD);
                        assertThat(batch.attentionMask()[row][i]).isZero();
                    }
                    assertThat(Arrays.copyOf(batch.attentionMask()[row], window.width())).containsOnly(1L);
                    assertThat(batch.tokenTypeIds()[row]).containsOnly(0L);
                    maxRowWidth = Math.max(maxRowWidth, window.width());
                    rows++;
                }
                if (queryIds.length + passageIds.length + 3 <= MAX_LENGTH) {
                    assertThat(windows).hasSize(1);
                    assertThat(Arrays.copyOf(batch.inputIds()[0], windows.get(0).width())).as(pair[0] + " whole pair").containsExactly(wholeIds);
                    wholePairs++;
                } else {
                    // exceedMaxLength reports that native truncation cut the pair; the kept encoding fits the window.
                    assertThat(head.getIds().length).isLessThanOrEqualTo(MAX_LENGTH);
                    assertThat(Arrays.copyOf(batch.inputIds()[0], windows.get(0).width())).as(pair[0] + " head row against longest_first at 512")
                            .containsExactly(head.getIds());
                    assertThat(Arrays.copyOf(batch.attentionMask()[0], windows.get(0).width())).containsExactly(head.getAttentionMask());
                    assertThat(head.getTypeIds()).containsOnly(0L);
                    truncatedHeads++;
                }
                checkedPairs++;
            }
        }
        // Padded batches: groups of 20 chunks beside one question, rows under the scorer's per-call grouping keep their prefixes.
        int batchRows = 0;
        for (int start = 0; start < pairs.size(); start += 20) {
            List<String[]> group = pairs.subList(start, Math.min(pairs.size(), start + 20));
            long[] queryIds = ids(group.get(0)[1]);
            List<long[]> passages = group.stream().map(p -> ids(p[2])).toList();
            List<CrossEncoderPairAssembler.Window> windows = assembler.windows(queryIds.length, passages, PassageScoring.MAX_WINDOW, OVERLAP, MAX_WINDOWS);
            for (int from = 0; from < windows.size(); ) {
                int end = assembler.runEnd(windows, from);
                CrossEncoderPairAssembler.Batch together = assembler.assemble(queryIds, passages, windows.subList(from, end));
                for (int row = 0; row < end - from; row++) {
                    CrossEncoderPairAssembler.Window window = windows.get(from + row);
                    CrossEncoderPairAssembler.Batch alone = assembler.assemble(queryIds, passages, List.of(window));
                    assertThat(Arrays.copyOf(together.inputIds()[row], window.width())).containsExactly(alone.inputIds()[0]);
                    for (int i = window.width(); i < together.inputIds()[row].length; i++) {
                        assertThat(together.inputIds()[row][i]).isEqualTo(PAD);
                        assertThat(together.attentionMask()[row][i]).isZero();
                    }
                    batchRows++;
                }
                from = end;
            }
        }
        System.out.println("GTE_RERANKER rows checkedPairs=" + checkedPairs + " longQueryPairs=" + longQueryPairs + " wholePairs=" + wholePairs
                + " truncatedHeads=" + truncatedHeads + " rows=" + rows + " maxRowWidth=" + maxRowWidth + " minWindowBesideLongQuery="
                + minLongQueryWindow + " paddedBatchRows=" + batchRows + " mismatches=0");
        assertThat(truncatedHeads).as("pairs that need truncation or windows").isPositive();
        assertThat(wholePairs).as("pairs that fit one row").isPositive();
        assertThat(maxRowWidth).isLessThanOrEqualTo(MAX_LENGTH);
    }

    @Test
    void aPhraseAcrossTheHeadWindowBoundaryIsPartlyInTheHeadAndWhollyInRowTwo() {
        String[] longest = pairs.stream().max(Comparator.comparingInt(p -> ids(p[2]).length)).orElseThrow();
        String query = longest[1];
        String chunk = CrossEncoderTokenPositions.bound(longest[2]);
        long[] queryIds = ids(query);
        PassageTokenizer.Tokens tokens = passageTokenizer.tokenize(chunk);
        long[] passageIds = ids(chunk);
        assertThat(tokens.count()).as("the evidence tokenizer counts what the scorer scores").isEqualTo(passageIds.length);
        int w = CrossEncoderTokenPositions.windowLength(queryIds.length, passageIds.length, passageTokenizer.maxLength().value());
        assertThat(passageIds.length).as("the longest holding chunk needs more than one window").isGreaterThan(w + 3);
        int charStart = tokens.starts()[w - 3];
        int charEnd = tokens.ends()[w + 2];
        String phrase = chunk.substring(charStart, charEnd);
        TokenSpan span = CrossEncoderTokenPositions.tokenSpan(tokens, charStart, charEnd);
        int[] starts = CrossEncoderTokenPositions.windowStarts(passageIds.length, w, Scoring.maxWindow(OVERLAP, MAX_WINDOWS));
        List<Integer> holding = CrossEncoderTokenPositions.windowsHoldingWholly(passageIds.length, w, starts, span);
        System.out.println("GTE_RERANKER boundary question=" + longest[0] + " queryTokens=" + queryIds.length + " chunkTokens=" + passageIds.length
                + " W=" + w + " starts=" + Arrays.toString(starts) + " phraseChars=[" + charStart + ", " + charEnd + ") tokenSpan=" + span
                + " holdingWholly=" + holding + " phrase=" + phrase.replaceAll("\\s+", " "));
        assertThat(span.start()).isLessThan(w);
        assertThat(span.end()).isGreaterThan(w);
        assertThat(CrossEncoderTokenPositions.head(span, w)).isEqualTo(HeadMembership.PARTLY);
        assertThat(holding).containsExactly(2);
        List<CrossEncoderPairAssembler.Window> windows = assembler.windows(queryIds.length, List.of(passageIds), PassageScoring.MAX_WINDOW, OVERLAP, MAX_WINDOWS);
        CrossEncoderPairAssembler.Batch batch = assembler.assemble(queryIds, List.of(passageIds), windows);
        long[] row2 = batch.inputIds()[1];
        int offset = 2 + windows.get(1).queryKept() + (span.start() - windows.get(1).start());
        assertThat(Arrays.copyOfRange(row2, offset, offset + span.end() - span.start()))
                .containsExactly(Arrays.copyOfRange(passageIds, span.start(), span.end()));
    }

    @Test
    void scoringSmokeCheckRelevantAboveIrrelevantPaddingInvariantAndTimed() throws Exception {
        GteRerankerModelInspector.requireSignature(files.modelPath());
        try (OnnxCrossEncoderScorer scorer = new OnnxCrossEncoderScorer(files.modelPath(), files.tokenizerPath(), MAX_LENGTH, 20,
                PassageScoring.MAX_WINDOW, OVERLAP, MAX_WINDOWS)) {
            assertThat(scorer.scoring()).isEqualTo("max-window/overlap=64/maxWindows=4");
            String question = "What was the company's total revenue in fiscal 2024?";
            float[] handwritten = scorer.score(question, List.of("Total revenue for fiscal 2024 was $60.9 billion, up 126% from a year ago.",
                    "The weather in Paris is mild in spring and many tourists visit the museums."));
            System.out.println("GTE_RERANKER smoke handwritten relevant=" + handwritten[0] + " irrelevant=" + handwritten[1]);
            assertThat(handwritten[0]).isGreaterThan(handwritten[1]);

            String[] real = pairs.get(0);
            String unrelated = chunkTexts.stream().filter(text -> !normalise(text).contains("net sales") && ids(text).length < 200).findFirst().orElseThrow();
            float[] realScores = scorer.score(real[1], List.of(real[2], unrelated));
            System.out.println("GTE_RERANKER smoke real question=" + real[0] + " holdingChunk=" + realScores[0] + " unrelatedChunk=" + realScores[1]);

            // Padding invariance: a short chunk scored alone and beside a chunk of more than 1,000 tokens (so its row is padded).
            String longChunk = pairs.stream().map(p -> p[2]).max(Comparator.comparingInt(text -> ids(text).length)).orElseThrow();
            double maxDiff = 0;
            for (String[] pair : pairs.subList(0, Math.min(10, pairs.size()))) {
                float alone = scorer.score(pair[1], List.of(pair[2]))[0];
                float padded = scorer.score(pair[1], List.of(longChunk, pair[2]))[1];
                maxDiff = Math.max(maxDiff, Math.abs(alone - padded));
            }
            System.out.println("GTE_RERANKER smoke paddingInvariance pairs=10 maxAbsDiff=" + maxDiff);
            assertThat(maxDiff).isLessThan(1e-3);

            List<String> longest = chunkTexts.stream().sorted(Comparator.comparingInt(String::length).reversed()).limit(40).toList();
            for (int count : new int[] {20, 40}) {
                long started = System.nanoTime();
                PairScorer.Scored scored = scorer.scoreWithWindows(real[1], longest.subList(0, count));
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                System.out.println("GTE_RERANKER timing chunks=" + count + " windows=" + scored.windows() + " elapsedMs=" + elapsed
                        + " (single thread, longest stored chunks by characters, question " + real[0] + ")");
                assertThat(scored.scores()).hasSize(count).doesNotContain(Float.NaN);
            }
        }
    }
}

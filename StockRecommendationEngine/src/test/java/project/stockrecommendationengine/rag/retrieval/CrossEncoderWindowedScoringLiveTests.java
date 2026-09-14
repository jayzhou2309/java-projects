package project.stockrecommendationengine.rag.retrieval;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Opt-in (-Drag.rerank.live=true, model files under models/, no database, no Spring context): windowed passage scoring on the
 * real model (plan 2026-09-13-reranker-windows, Milestone 1, C3 and C4). Two scorers share the JVM, one per
 * {@link PassageScoring} mode with the application defaults (overlap 64, at most 4 windows). A handwritten chunk of about 900
 * tokens whose answering sentence starts after token 600 must score higher under max-window than under head and rank first of
 * three candidates; a chunk with the answer in its first 200 tokens must score the same under both modes; two calls on the same
 * input must agree; the windows the reranker logs for 20 chunks of about 1,000 tokens must be at least 40; and the latency of
 * both modes for 20 and 40 such chunks is printed (recorded in RAG.md, Cross-encoder reranker, Latency).
 */
@EnabledIfSystemProperty(named = "rag.rerank.live", matches = "true")
@ExtendWith(OutputCaptureExtension.class)
class CrossEncoderWindowedScoringLiveTests {
    private static final String EXPECTED_MODEL_SHA256 = "5d3e70fd0c9ff14b9b5169a51e957b7a9c74897afd0a35ce4bd318150c1d4d4a";
    private static final String QUESTION = "What was Data Center revenue for fiscal year 2025?";
    private static final String ANSWER = "Data Center revenue for fiscal year 2025 was $115.2 billion, up 142% from a year ago, driven by demand "
            + "for the Hopper and Blackwell computing platforms used to train and serve large language models.";
    private static final String UNRELATED = "The Board of Directors held eleven meetings during the year, and every director attended at "
            + "least seventy-five percent of the meetings of the Board and of the committees on which the director served.";
    /** Filing-style prose that names no revenue figure, so a window without the answer scores low. */
    private static final String[] FILLER = {
            "Our business depends on the continued availability of skilled engineers, and competition for such personnel in the semiconductor industry is intense.",
            "We rely on a small number of third-party foundries and assembly and test subcontractors, most of which are located outside the United States.",
            "Changes in export control regulations may require us to obtain licenses before shipping certain products to customers in some countries.",
            "Our products are complex and may contain defects or errors that are detected only after they have been installed in customer systems.",
            "We hold a large number of patents, but competitors may design around them or challenge their validity in litigation.",
            "The Company leases its headquarters buildings and expects that its facilities are adequate for its needs for the next twelve months.",
            "Natural disasters, public health crises, and geopolitical conflict could disrupt our operations or the operations of our suppliers.",
            "The market price of our common stock has been volatile and may continue to fluctuate for reasons unrelated to our operating performance.",
            "We may be unable to protect our confidential information from unauthorised disclosure by employees, contractors, or business partners.",
            "Our effective tax rate depends on the mix of earnings among jurisdictions and could change as a result of new legislation.",
            "Interest rate movements affect the fair value of our fixed-income investments, which are classified as available for sale.",
            "We are subject to environmental regulations governing the use, storage, and disposal of hazardous materials in manufacturing.",
            "Our independent registered public accounting firm audited the effectiveness of our internal control over financial reporting.",
            "Compensation of our named executive officers is discussed in the proxy statement, which is incorporated herein by reference.",
            "We evaluate goodwill for impairment annually in the fourth quarter and whenever events indicate that its carrying value may not be recoverable.",
            "Foreign currency exchange rate fluctuations have not had a material effect on our results, because most sales are denominated in dollars."};
    private static final Pattern WINDOWS = Pattern.compile("Cross-encoder scoring completed: candidates=(\\d+), windows=(\\d+),");

    private static CrossEncoderModelFiles files;
    private static OnnxCrossEncoderScorer head;
    private static OnnxCrossEncoderScorer maxWindow;
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
        assertThat(properties.getPassageScoring()).isEqualTo(PassageScoring.MAX_WINDOW);
        maxWindow = new OnnxCrossEncoderScorer(files.modelPath(), files.tokenizerPath(), properties.getMaxLength(), properties.getBatchSize(),
                properties.getPassageScoring(), properties.getWindowOverlapTokens(), properties.getMaxWindows());
        head = new OnnxCrossEncoderScorer(files.modelPath(), files.tokenizerPath(), properties.getMaxLength(), properties.getBatchSize(),
                PassageScoring.HEAD, properties.getWindowOverlapTokens(), properties.getMaxWindows());
        counter = OnnxCrossEncoderScorer.singleSequenceTokenizer(files.tokenizerPath());
        System.out.println("CROSS_ENCODER_WINDOWS scoring maxWindow=" + maxWindow.scoring() + " head=" + head.scoring());
        assertThat(maxWindow.scoring()).isEqualTo("max-window/overlap=64/maxWindows=4");
        assertThat(head.scoring()).isEqualTo("head");
    }

    @AfterAll
    static void close() {
        if (counter != null) counter.close();
        if (head != null) head.close();
        if (maxWindow != null) maxWindow.close();
    }

    @Test
    void anAnswerAfterToken600ScoresHigherUnderMaxWindowThanUnderHeadAndRanksFirst() {
        String before = prose(0, 610);
        String late = before + " " + ANSWER + " " + prose(8, 230);
        int answerOffset = tokens(before);
        int total = tokens(late);
        System.out.println("CROSS_ENCODER_WINDOWS lateAnswer chunkTokens=" + total + " answerTokenOffset=" + answerOffset
                + " windowTokens=" + CrossEncoderPairAssembler.windowLength(tokens(QUESTION), total, 512));
        assertThat(answerOffset).isGreaterThan(600);
        assertThat(total).isBetween(800, 1_000);

        float headScore = head.score(QUESTION, List.of(late))[0];
        float windowedScore = maxWindow.score(QUESTION, List.of(late))[0];
        System.out.println("CROSS_ENCODER_WINDOWS lateAnswer head=" + headScore + " maxWindow=" + windowedScore);
        assertThat(windowedScore).isGreaterThan(headScore);

        // Three candidates, the answering chunk last: a long chunk of the same prose with an unrelated sentence in the answer's
        // place, a short unrelated chunk, and the late-answer chunk.
        String longDistractor = before + " " + UNRELATED + " " + prose(8, 230);
        List<RetrievedFilingChunk> candidates = List.of(chunk(1, longDistractor), chunk(2, UNRELATED), chunk(3, late));
        CrossEncoderReranker reranker = new CrossEncoderReranker(maxWindow, files.version());
        List<RetrievedFilingChunk> ranked = reranker.rerank(QUESTION, candidates, 3);
        float[] all = maxWindow.score(QUESTION, candidates.stream().map(RetrievedFilingChunk::content).toList());
        System.out.println("CROSS_ENCODER_WINDOWS lateAnswer candidates scores(longDistractor, shortDistractor, lateAnswer)=" + Arrays.toString(all)
                + " order=" + ranked.stream().map(RetrievedFilingChunk::chunkId).toList()
                + " headScores=" + Arrays.toString(head.score(QUESTION, candidates.stream().map(RetrievedFilingChunk::content).toList())));
        assertThat(ranked.get(0).chunkId()).isEqualTo(3L);
        assertThat(ranked).containsExactlyInAnyOrderElementsOf(candidates);
    }

    @Test
    void anAnswerInTheFirst200TokensScoresTheSameUnderBothModes() {
        String early = prose(3, 110) + " " + ANSWER + " " + prose(5, 720);
        int answerOffset = tokens(prose(3, 110));
        int total = tokens(early);
        System.out.println("CROSS_ENCODER_WINDOWS earlyAnswer chunkTokens=" + total + " answerTokenOffset=" + answerOffset);
        assertThat(answerOffset + tokens(ANSWER)).isLessThan(200);
        assertThat(total).isBetween(800, 1_000);
        float headScore = head.score(QUESTION, List.of(early))[0];
        float windowedScore = maxWindow.score(QUESTION, List.of(early))[0];
        System.out.println("CROSS_ENCODER_WINDOWS earlyAnswer head=" + headScore + " maxWindow=" + windowedScore);
        assertThat(windowedScore).isCloseTo(headScore, within(1e-4f));
        // A chunk that fits one window is the same tensors under both modes, so the same logit bit for bit.
        String fits = prose(0, 80) + " " + ANSWER;
        assertThat(tokens(fits)).isLessThan(400);
        assertThat(maxWindow.score(QUESTION, List.of(fits))[0]).isEqualTo(head.score(QUESTION, List.of(fits))[0]);
    }

    /**
     * Diagnostic, printed and not asserted beyond shape: the same answer sentence scored at increasing depths inside the filler,
     * under both modes, so the effect of an answer's position within a window is on record for the Milestone 2 measurement.
     */
    @Test
    void theAnswerPositionSweepIsPrintedForBothModes() {
        StringBuilder sweep = new StringBuilder();
        for (int sentencesBefore : new int[] {0, 2, 4, 6, 8, 10, 12, 16, 20, 24, 28, 32}) {
            String text = (sentencesBefore == 0 ? "" : prose(0, sentencesBefore * 20) + " ") + ANSWER + " " + prose(3, 90);
            int offset = sentencesBefore == 0 ? 0 : tokens(prose(0, sentencesBefore * 20));
            float headScore = head.score(QUESTION, List.of(text))[0];
            float windowedScore = maxWindow.score(QUESTION, List.of(text))[0];
            assertThat(windowedScore).isGreaterThanOrEqualTo(headScore); // the head window is one of the windows
            sweep.append(" [answerAt=").append(offset).append(" tokens=").append(tokens(text)).append(" head=").append(headScore)
                    .append(" maxWindow=").append(windowedScore).append(']');
        }
        System.out.println("CROSS_ENCODER_WINDOWS positionSweep answerAlone=" + head.score(QUESTION, List.of(ANSWER))[0] + sweep);
    }

    @Test
    void twoCallsOnIdenticalInputGiveIdenticalScoresAndOrder() {
        List<RetrievedFilingChunk> candidates = longCandidates(20);
        List<String> passages = candidates.stream().map(RetrievedFilingChunk::content).toList();
        float[] first = maxWindow.score(QUESTION, passages);
        float[] second = maxWindow.score(QUESTION, passages);
        assertThat(second).containsExactly(first);
        CrossEncoderReranker reranker = new CrossEncoderReranker(maxWindow, files.version());
        assertThat(reranker.rerank(QUESTION, candidates, 10)).containsExactlyElementsOf(reranker.rerank(QUESTION, candidates, 10));
        System.out.println("CROSS_ENCODER_WINDOWS determinism identical=" + Arrays.equals(first, second) + " scores=" + Arrays.toString(first));
    }

    @Test
    void theLogLineReportsAtLeast40WindowsFor20ChunksOfAbout1000Tokens(CapturedOutput output) {
        List<RetrievedFilingChunk> candidates = longCandidates(20);
        List<String> passages = candidates.stream().map(RetrievedFilingChunk::content).toList();
        int tokens = tokens(passages.get(0));
        PairScorer.Scored scored = maxWindow.scoreWithWindows(QUESTION, passages);
        assertThat(scored.scores()).hasSize(20);
        assertThat(scored.windows()).as("windows for 20 chunks of " + tokens + " tokens").isGreaterThanOrEqualTo(40);
        assertThat(head.scoreWithWindows(QUESTION, passages).windows()).isEqualTo(20);
        new CrossEncoderReranker(maxWindow, files.version()).rerank(QUESTION, candidates, 5);
        new CrossEncoderReranker(head, files.version()).rerank(QUESTION, candidates, 5);
        List<int[]> logged = new ArrayList<>();
        Matcher matcher = WINDOWS.matcher(output.getOut());
        while (matcher.find()) logged.add(new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))});
        System.out.println("CROSS_ENCODER_WINDOWS logged chunkTokens=" + tokens + " scoredWindows=" + scored.windows()
                + " logLines(candidates, windows)=" + logged.stream().map(Arrays::toString).toList());
        assertThat(logged).hasSizeGreaterThanOrEqualTo(2);
        assertThat(logged.get(logged.size() - 2)).containsExactly(20, scored.windows());
        assertThat(logged.get(logged.size() - 1)).containsExactly(20, 20);
    }

    @Test
    void latencyFor20And40ChunksOfAbout1000TokensIsPrintedForBothModes() {
        for (int count : new int[] {20, 40}) {
            List<RetrievedFilingChunk> candidates = longCandidates(count);
            int tokens = tokens(candidates.get(0).content());
            for (OnnxCrossEncoderScorer scorer : List.of(maxWindow, head)) {
                CrossEncoderReranker reranker = new CrossEncoderReranker(scorer, files.version());
                reranker.rerank(QUESTION, candidates, 5); // warm-up
                List<Long> elapsed = new ArrayList<>();
                for (int run = 0; run < 3; run++) {
                    long started = System.nanoTime();
                    assertThat(reranker.rerank(QUESTION, candidates, 5)).hasSize(5);
                    elapsed.add((System.nanoTime() - started) / 1_000_000);
                }
                System.out.println("CROSS_ENCODER_WINDOWS latency scoring=" + scorer.scoring() + " candidates=" + count + " tokensEach=" + tokens
                        + " windows=" + scorer.scoreWithWindows(QUESTION, candidates.stream().map(RetrievedFilingChunk::content).toList()).windows()
                        + " elapsedMs(after warm-up)=" + elapsed);
            }
        }
    }

    /** Filler sentences from index {@code from} of the cycle until the text has at least {@code minTokens} tokens (about 23 per sentence). */
    private static String prose(int from, int minTokens) {
        StringBuilder text = new StringBuilder();
        for (int i = from; tokens(text.toString()) < minTokens; i++) {
            if (!text.isEmpty()) text.append(' ');
            text.append(FILLER[i % FILLER.length]);
        }
        return text.toString();
    }

    private static int tokens(String text) {
        return counter.encode(text, false, false).getIds().length;
    }

    /** {@code count} distinct chunks of about 1,000 tokens each: a numbered lead sentence, then filler until 1,000 tokens. */
    private static List<RetrievedFilingChunk> longCandidates(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(id -> {
            StringBuilder text = new StringBuilder("Passage " + id + " of the annual report.");
            for (int i = id; tokens(text.toString()) < 1_000; i++) text.append(' ').append(FILLER[i % FILLER.length]);
            return chunk(id, text.toString());
        }).toList();
    }

    private static RetrievedFilingChunk chunk(long id, String content) {
        return new RetrievedFilingChunk(id, 100L + id, "NVDA", "0001045810", "0001045810-25-000023", "10-K",
                LocalDate.parse("2025-02-26"), LocalDate.parse("2025-01-26"), "ITEM_7", "MD&A", (int) id, content,
                "https://example.invalid/filing/" + id, 0.9 - id / 100.0);
    }
}

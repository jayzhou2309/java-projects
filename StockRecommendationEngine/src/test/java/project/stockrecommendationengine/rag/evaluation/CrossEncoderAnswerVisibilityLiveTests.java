package project.stockrecommendationengine.rag.evaluation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.evaluation.EvidenceChunkRepository.StoredChunk;
import project.stockrecommendationengine.rag.evaluation.PhraseOccurrences.CharacterSpan;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderModelFiles;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderProperties;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderReranker;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderTokenPositions;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderTokenPositions.HeadMembership;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderTokenPositions.Scoring;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderTokenPositions.TokenSpan;
import project.stockrecommendationengine.rag.retrieval.FilingReranker;
import project.stockrecommendationengine.rag.retrieval.OnnxCrossEncoderScorer;
import project.stockrecommendationengine.rag.retrieval.PassageScoring;
import project.stockrecommendationengine.rag.retrieval.PassageTokenizer;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Opt-in diagnostic (-Drag.rerank.live=true; model files under models/; reads the shared database, writes nothing to it, calls no chat
 * model): does the cross-encoder actually have an accepted answer in view when it scores the chunk that holds it, and does that chunk
 * rank first among the real chunks it competes with? Five experiments over every accepted phrase of the configured evaluation set, each
 * varying one factor and printing one {@code ANSWER_VISIBILITY} line per measurement:
 * <ul>
 * <li>{@code visibility}: the phrase's token span against the head window and against the windows the current settings score, so a
 * phrase no row holds wholly is named;</li>
 * <li>{@code truncation}: the stored chunk's logit under head scoring, under windowed scoring, and with the truncation removed (the
 * passage re-cut to start before the phrase, and the phrase alone);</li>
 * <li>{@code rank}: the accepted chunk's reranked position among every stored chunk of its own filing section, under head and windowed
 * scoring;</li>
 * <li>{@code overlap}: the same visibility, logit, and rank across a grid of {@code window-overlap-tokens} and {@code max-windows}
 * values, the current 64 / 4 among them;</li>
 * <li>{@code chunkSize}: the section's chunks re-split at smaller sizes, and the rank of the smaller piece that holds the phrase.</li>
 * </ul>
 * Nothing here asserts an outcome: an assertion would fix the answer the run is meant to measure. The assertions cover only the
 * arithmetic invariants ({@code windowedScore >= headScore}, because window 1 is the head row; a span inside the passage; a rank inside
 * the pool), so a printed line that contradicts an expectation is a measurement, not a failure. Reading the lines is the point, and any
 * conclusion belongs in a {@code claims.json} beside the committed output (RAG.md, Retrieval Evaluation, Claims), not here.
 * <p>
 * The chunk splitter in the {@code chunkSize} experiment is a test-local replica of {@link project.stockrecommendationengine.rag.ingestion.FilingChunker}'s
 * rule parameterised by size, not that class, whose 4,000-character window and 500-character overlap are fixed; the factor it varies is
 * chunk size under that rule, so its rows say nothing about a different splitting rule. Ingestion is untouched: the pieces live in this
 * JVM only.
 * <p>
 * Aborts rather than fails when the database holds no chunk for any accepted phrase (an empty or differently ingested store), so a run
 * without the evaluation set's filings reports skipped.
 * <p>
 * Runtime: every rank is one scoring of a whole filing section, which is 35 to 280 model rows on NVDA's longest sections, and the grid
 * experiment scores each pool nine times, so a run over all 42 questions takes hours on a CPU. Orderings are scored once and reused
 * ({@link #ranking}), and {@code -Drag.rerank.visibility.questions=nvda-01,nvda-11} restricts the run to the named question ids, which is
 * how to measure a handful of questions in minutes.
 */
@SpringBootTest(properties = "rag.retrieval.cross-encoder.enabled=true")
@EnabledIfSystemProperty(named = "rag.rerank.live", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossEncoderAnswerVisibilityLiveTests {
    /** Characters of context kept before an accepted phrase when the truncation is removed, as the 2026-09-13 truncation probe used. */
    private static final int CONTEXT_CHARS = 200;
    /** Chunk sizes in characters the last experiment re-splits a section into; the first is the stored size. */
    private static final int[] CHUNK_SIZES = {4_000, 2_000, 1_000, 500};
    /** Overlap and window-count grid of the fourth experiment; 64 and 4 are the application defaults. */
    private static final int[] OVERLAPS = {0, 64, 128, 224};
    private static final int[] MAX_WINDOWS = {4, 8};
    /** Comma-separated question ids to measure; empty measures every question of the set. */
    private static final String QUESTIONS_PROPERTY = "rag.rerank.visibility.questions";

    @Autowired CrossEncoderModelFiles files;
    @Autowired CrossEncoderProperties crossEncoder;
    @Autowired PassageTokenizer tokenizer;
    @Autowired EvidenceChunkRepository storedChunks;
    @Autowired RetrievalEvaluationSetLoader loader;
    @Autowired RetrievalEvaluationProperties evaluation;

    /** Scorers by {@link Scoring#label()}, built on demand and closed after the class. */
    private final Map<String, OnnxCrossEncoderScorer> scorers = new LinkedHashMap<>();
    /** Stored chunks by accession and section key, read once. */
    private final Map<String, List<StoredChunk>> sections = new LinkedHashMap<>();
    /** Reranked orderings by pool, question, and scoring, so no pool is scored twice ({@link #ranking}). */
    private final Map<String, List<Long>> rankings = new LinkedHashMap<>();
    private List<Target> targets;
    private Scoring configured;

    /**
     * One accepted phrase located in one stored chunk, the unit every experiment measures: the question that accepts it, the chunk that
     * holds it, and the phrase's first occurrence as UTF-16 offsets into that chunk's stored text.
     */
    private record Target(String questionId, RetrievalEvaluationQuestion.Kind kind, String ticker, String question,
                          String accessionNo, String sectionKey, long chunkId, String content, int charStart, int charEnd) {
        String phraseText() {
            return content.substring(charStart, charEnd);
        }
    }

    /** Where a target's phrase falls in the rows one scoring setting runs for its chunk. */
    private record Visibility(int questionTokens, int passageTokens, int windowLength, int[] starts, TokenSpan span,
                              HeadMembership head, List<Integer> holdingWholly) {
        boolean seen() {
            return !holdingWholly.isEmpty();
        }
    }

    @BeforeAll
    void locateEveryAcceptedPhrase() {
        configured = crossEncoder.getPassageScoring() == PassageScoring.HEAD
                ? Scoring.head()
                : Scoring.maxWindow(crossEncoder.getWindowOverlapTokens(), crossEncoder.getMaxWindows());
        RetrievalEvaluationSet set = loader.load();
        List<String> only = Arrays.stream(System.getProperty(QUESTIONS_PROPERTY, "").split(","))
                .map(String::trim).filter(id -> !id.isEmpty()).toList();
        List<Target> found = new ArrayList<>();
        List<String> unlocated = new ArrayList<>();
        for (RetrievalEvaluationQuestion question : set.questions()) {
            if (!only.isEmpty() && !only.contains(question.id())) continue;
            for (ExpectedPassage expected : question.expected()) {
                Target target = locate(question, expected);
                if (target == null) unlocated.add(question.id() + "/" + expected.sectionKey());
                else found.add(target);
            }
        }
        targets = List.copyOf(found);
        System.out.println("ANSWER_VISIBILITY setup set=" + set.version() + " resource=" + evaluation.getSet()
                + " questions=" + set.questions().size() + " questionsMeasured=" + (only.isEmpty() ? "all" : only.toString())
                + " acceptedPhrasesLocated=" + targets.size()
                + " acceptedPhrasesNotInAnyStoredChunk=" + unlocated.size() + unlocated
                + " model=" + files.version() + " maxLength=" + crossEncoder.getMaxLength()
                + " batchSize=" + crossEncoder.getBatchSize() + " configuredScoring=" + configured.label());
        assumeFalse(targets.isEmpty(), "no accepted phrase of " + evaluation.getSet() + " is in any stored chunk (or -D" + QUESTIONS_PROPERTY
                + " named none of it); is the store ingested?");
    }

    @AfterAll
    void closeScorers() {
        scorers.values().forEach(OnnxCrossEncoderScorer::close);
    }

    /**
     * Experiment 1, the question this class exists for: for every accepted phrase, whether the rows the cross-encoder runs for its chunk
     * hold the phrase at all. {@code headMembership} is what head scoring saw (WHOLLY, PARTLY when the cut splits the phrase, NOT when
     * the phrase begins past the window); {@code windowsHoldingPhrase} are the 1-based rows of the current settings that hold it wholly,
     * empty when no row does, which is the case where the reranker scores the right chunk without ever reading its answer.
     */
    @Test
    void whetherTheAcceptedPhraseIsInsideTheScoredRowsIsRecordedForEveryQuestion() {
        int headWholly = 0;
        int headPartly = 0;
        int headNot = 0;
        int windowedSeen = 0;
        List<String> unseenUnderCurrentSettings = new ArrayList<>();
        for (Target target : targets) {
            Visibility head = visibility(target, Scoring.head());
            Visibility current = visibility(target, configured);
            switch (head.head()) {
                case WHOLLY -> headWholly++;
                case PARTLY -> headPartly++;
                case NOT -> headNot++;
            }
            if (current.seen()) windowedSeen++;
            else unseenUnderCurrentSettings.add(target.questionId() + "/chunk" + target.chunkId());

            assertThat(current.span().end()).as(target.questionId() + " span inside the passage").isLessThanOrEqualTo(current.passageTokens());
            assertThat(current.starts()).as(target.questionId() + " at least one row").isNotEmpty();
            assertThat(head.starts()).as(target.questionId() + " head scores one row").containsExactly(0);
            assertThat(head.holdingWholly().isEmpty()).as(target.questionId() + " head row holds the phrase exactly when membership is WHOLLY")
                    .isEqualTo(head.head() != HeadMembership.WHOLLY);

            System.out.println("ANSWER_VISIBILITY visibility question=" + target.questionId() + " kind=" + target.kind()
                    + " ticker=" + target.ticker() + " chunk=" + target.chunkId() + " section=" + target.sectionKey()
                    + " questionTokens=" + current.questionTokens() + " chunkTokens=" + current.passageTokens()
                    + " windowLength=" + current.windowLength() + " phraseTokens=[" + current.span().start() + ", " + current.span().end() + ")"
                    + " headMembership=" + head.head() + " scoring=" + configured.label()
                    + " windowStarts=" + Arrays.toString(current.starts())
                    + " windowsHoldingPhrase=" + current.holdingWholly()
                    + " phrase=\"" + oneLine(target.phraseText()) + '"');
        }
        System.out.println("ANSWER_VISIBILITY visibility TOTALS phrases=" + targets.size()
                + " headWholly=" + headWholly + " headPartly=" + headPartly + " headNot=" + headNot
                + " scoring=" + configured.label() + " someRowHoldsPhrase=" + windowedSeen
                + " noRowHoldsPhrase=" + (targets.size() - windowedSeen) + unseenUnderCurrentSettings);
    }

    /**
     * Experiment 2, truncation against no truncation on the same chunk and the same question: the logit of the stored text under head
     * scoring, under the configured windowed settings, with the passage re-cut to start {@value #CONTEXT_CHARS} characters before the
     * phrase (so the phrase is in the first row whatever the chunk's length), and for the phrase on its own. The windowed logit is at
     * least the head logit by construction, since window 1 is the head row; nothing else is asserted, because a re-cut passage moves the
     * phrase to a different position inside its row and the model's logit is known to depend on that position.
     */
    @Test
    void theLogitWithAndWithoutTheProductionTruncationIsRecordedForEveryQuestion() {
        OnnxCrossEncoderScorer head = scorer(Scoring.head());
        OnnxCrossEncoderScorer current = scorer(configured);
        for (Target target : targets) {
            Visibility visibility = visibility(target, configured);
            String recut = target.content().substring(Math.max(0, target.charStart() - CONTEXT_CHARS));
            float headScore = head.score(target.question(), List.of(target.content()))[0];
            float windowedScore = current.score(target.question(), List.of(target.content()))[0];
            float recutScore = head.score(target.question(), List.of(recut))[0];
            float phraseScore = head.score(target.question(), List.of(target.phraseText()))[0];

            assertThat(headScore).as(target.questionId() + " head logit is a number").isNotNaN();
            assertThat(windowedScore).as(target.questionId() + " window 1 is the head row")
                    .isGreaterThanOrEqualTo(headScore);
            assertThat(recutScore).as(target.questionId() + " re-cut logit is a number").isNotNaN();
            assertThat(phraseScore).as(target.questionId() + " phrase-alone logit is a number").isNotNaN();

            System.out.println("ANSWER_VISIBILITY truncation question=" + target.questionId() + " chunk=" + target.chunkId()
                    + " chunkTokens=" + visibility.passageTokens() + " windowLength=" + visibility.windowLength()
                    + " phraseTokenStart=" + visibility.span().start() + " headMembership=" + visibility.head()
                    + " windowsHoldingPhrase=" + visibility.holdingWholly()
                    + " headScore=" + headScore + " windowedScore(" + configured.label() + ")=" + windowedScore
                    + " recutScore(from " + CONTEXT_CHARS + " chars before the phrase)=" + recutScore
                    + " phraseAloneScore=" + phraseScore
                    + " windowedMinusHead=" + (windowedScore - headScore) + " recutMinusHead=" + (recutScore - headScore));
        }
    }

    /**
     * Experiment 3, whether the right chunk is ranked: the reranked position, among every stored chunk of its own filing section, of every
     * chunk of that section holding the phrase, since stored chunks overlap by 500 characters and the evaluation counts a hit on any of
     * them; the best of those positions is the one a top-k cut would see. A section pool is not retrieval's candidate list (fusion decides
     * that, and a chunk outside the rerank input is never scored at all), so a rank here is the reranker's ordering in isolation, not a
     * retrieval result.
     */
    @Test
    void theAcceptedChunkRankAmongItsSectionIsRecordedUnderHeadAndWindowedScoring() {
        for (Target target : targets) {
            String poolKey = target.accessionNo() + '/' + target.sectionKey();
            List<RetrievedFilingChunk> pool = pool(target.accessionNo(), target.sectionKey());
            List<Long> holders = holderIds(target);
            List<Integer> headRanks = holders.stream().map(id -> rankOf(id, poolKey, pool, target.question(), Scoring.head())).toList();
            List<Integer> windowedRanks = holders.stream().map(id -> rankOf(id, poolKey, pool, target.question(), configured)).toList();
            assertThat(holders).as(target.questionId() + " the located chunk holds the phrase").contains(target.chunkId());
            assertThat(headRanks).as(target.questionId() + " head ranks inside the pool").allMatch(rank -> rank >= 1 && rank <= pool.size());
            assertThat(windowedRanks).as(target.questionId() + " windowed ranks inside the pool").allMatch(rank -> rank >= 1 && rank <= pool.size());
            System.out.println("ANSWER_VISIBILITY rank question=" + target.questionId() + " kind=" + target.kind()
                    + " section=" + target.sectionKey() + " poolChunks=" + pool.size()
                    + " chunksHoldingPhrase=" + holders + " headRanks=" + headRanks + " windowedRanks=" + windowedRanks
                    + " bestHeadRank=" + headRanks.stream().min(Integer::compareTo).orElseThrow()
                    + " bestWindowedRank(" + configured.label() + ")=" + windowedRanks.stream().min(Integer::compareTo).orElseThrow()
                    + " headTop5=" + topIds(poolKey, pool, target.question(), Scoring.head())
                    + " windowedTop5=" + topIds(poolKey, pool, target.question(), configured));
        }
    }

    /**
     * Experiment 4, one factor at a time over the window grid: for each {@code window-overlap-tokens} and {@code max-windows} pair, the
     * rows run for the accepted chunk, whether one of them holds the phrase, the chunk's logit, and its rank in the section pool. Head
     * scoring is the first row of each block as the no-windows comparison. A larger {@code max-windows} moves the last window rather than
     * only adding rows, so neither the logit nor the rank is monotone in either factor and none is asserted.
     */
    @Test
    void theOverlapAndMaxWindowsGridIsRecordedForEveryQuestion() {
        for (Target target : targets) {
            String poolKey = target.accessionNo() + '/' + target.sectionKey();
            List<RetrievedFilingChunk> pool = pool(target.accessionNo(), target.sectionKey());
            List<Scoring> grid = new ArrayList<>();
            grid.add(Scoring.head());
            for (int maxWindows : MAX_WINDOWS) {
                for (int overlap : OVERLAPS) grid.add(Scoring.maxWindow(overlap, maxWindows));
            }
            for (Scoring scoring : grid) {
                Visibility visibility = visibility(target, scoring);
                float score = scorer(scoring).score(target.question(), List.of(target.content()))[0];
                int rank = rankOf(target.chunkId(), poolKey, pool, target.question(), scoring);
                assertThat(rank).as(target.questionId() + " rank inside the pool at " + scoring.label()).isBetween(1, pool.size());
                assertThat(visibility.starts().length).as(target.questionId() + " rows at " + scoring.label())
                        .isBetween(1, scoring.mode() == PassageScoring.HEAD ? 1 : scoring.maxWindows());
                System.out.println("ANSWER_VISIBILITY overlap question=" + target.questionId() + " chunk=" + target.chunkId()
                        + " chunkTokens=" + visibility.passageTokens() + " scoring=" + scoring.label()
                        + " windowLength=" + visibility.windowLength() + " rows=" + visibility.starts().length
                        + " windowStarts=" + Arrays.toString(visibility.starts())
                        + " windowsHoldingPhrase=" + visibility.holdingWholly() + " phraseSeen=" + visibility.seen()
                        + " score=" + score + " rank=" + rank + " poolChunks=" + pool.size()
                        + " isDefault=" + scoring.label().equals(configured.label()));
            }
        }
    }

    /**
     * Experiment 5, chunk size as the factor: the accepted chunk's whole section re-split into pieces of at most each size in
     * {@link #CHUNK_SIZES} with an overlap of an eighth of the size (the stored rule's 500 of 4,000), then every piece holding the phrase
     * ranked against every other piece under the configured scoring; the best of those ranks is the one a top-k cut would see. The first
     * size is the stored one, so its row is the comparison. A phrase can straddle a boundary at a small size and land in no piece; that
     * is reported as {@code noPieceHoldsPhrase} rather than failed, since it is a property of the size under test.
     */
    @Test
    void theRankOfTheSmallerPieceHoldingThePhraseIsRecordedPerChunkSize() {
        for (Target target : targets) {
            String poolKey = target.accessionNo() + '/' + target.sectionKey();
            List<StoredChunk> section = section(target.accessionNo(), target.sectionKey());
            for (int size : CHUNK_SIZES) {
                List<RetrievedFilingChunk> pieces = new ArrayList<>();
                long id = 0;
                for (StoredChunk stored : section) {
                    for (String piece : split(stored.content(), size, size / 8)) pieces.add(chunk(++id, piece, target));
                }
                List<RetrievedFilingChunk> holders = pieces.stream().filter(piece -> holds(piece.content(), target.phraseText())).toList();
                if (holders.isEmpty()) {
                    System.out.println("ANSWER_VISIBILITY chunkSize question=" + target.questionId() + " storedChunk=" + target.chunkId()
                            + " sizeChars=" + size + " pieces=" + pieces.size() + " noPieceHoldsPhrase=true");
                    continue;
                }
                int bestRank = Integer.MAX_VALUE;
                List<String> rows = new ArrayList<>();
                for (RetrievedFilingChunk holder : holders) {
                    CharacterSpan span = PhraseOccurrences.find(holder.content(), target.phraseText()).get(0);
                    Visibility visibility = visibility(new Target(target.questionId(), target.kind(), target.ticker(), target.question(),
                            target.accessionNo(), target.sectionKey(), holder.chunkId(), holder.content(), span.start(), span.end()), configured);
                    int rank = rankOf(holder.chunkId(), poolKey + "/size" + size, pieces, target.question(), configured);
                    float score = scorer(configured).score(target.question(), List.of(holder.content()))[0];
                    assertThat(rank).as(target.questionId() + " rank inside the pieces at " + size + " chars").isBetween(1, pieces.size());
                    bestRank = Math.min(bestRank, rank);
                    rows.add("piece" + holder.chunkId() + "{chars=" + holder.content().length() + " tokens=" + visibility.passageTokens()
                            + " windowLength=" + visibility.windowLength() + " phraseTokenStart=" + visibility.span().start()
                            + " headMembership=" + visibility.head() + " windowsHoldingPhrase=" + visibility.holdingWholly()
                            + " phraseSeen=" + visibility.seen() + " score=" + score + " rank=" + rank + '}');
                }
                System.out.println("ANSWER_VISIBILITY chunkSize question=" + target.questionId() + " kind=" + target.kind()
                        + " storedChunk=" + target.chunkId() + " sizeChars=" + size + " overlapChars=" + size / 8
                        + " pieces=" + pieces.size() + " piecesHoldingPhrase=" + holders.size() + " bestRank=" + bestRank
                        + " isStoredSize=" + (size == CHUNK_SIZES[0]) + " holders=" + rows);
            }
        }
    }

    /**
     * Whether {@code text} contains {@code phrase} by the evaluation's own containment rule ({@link RetrievalEvaluationService#matches}:
     * whitespace collapsed, lower-cased), so a piece or chunk counts here exactly when the evaluation would accept it.
     */
    private static boolean holds(String text, String phrase) {
        List<CharacterSpan> found = PhraseOccurrences.find(text, phrase);
        return found != null && !found.isEmpty();
    }

    /** Every stored chunk of the target's section whose text holds the phrase, in stored order; stored chunks overlap, so there may be several. */
    private List<Long> holderIds(Target target) {
        return section(target.accessionNo(), target.sectionKey()).stream()
                .filter(stored -> holds(stored.content(), target.phraseText()))
                .map(StoredChunk::id)
                .toList();
    }

    /**
     * The lowest-id stored chunk of the expected section whose text contains the expected phrase, with the phrase's first occurrence: the
     * target the per-chunk experiments measure. The evaluation instead accepts whichever holder ranks highest
     * ({@link RetrievalEvaluationService#firstMatch} scans the window in rank order), which is why experiments 3 and 5 rank every holder.
     */
    private Target locate(RetrievalEvaluationQuestion question, ExpectedPassage expected) {
        for (StoredChunk stored : section(expected.accessionNo(), expected.sectionKey())) {
            List<CharacterSpan> found = PhraseOccurrences.find(stored.content(), expected.phrase());
            if (found == null || found.isEmpty()) continue;
            CharacterSpan span = found.get(0);
            return new Target(question.id(), question.kind(), question.ticker(), question.question(), expected.accessionNo(),
                    expected.sectionKey(), stored.id(), stored.content(), span.start(), span.end());
        }
        return null;
    }

    private List<StoredChunk> section(String accessionNo, String sectionKey) {
        return sections.computeIfAbsent(accessionNo + '/' + sectionKey, key -> storedChunks.chunks(accessionNo, sectionKey));
    }

    /**
     * The phrase's place in the rows {@code scoring} runs for the target's chunk, from the scorer's own arithmetic on the bounded text
     * the scorer would tokenize.
     */
    private Visibility visibility(Target target, Scoring scoring) {
        String bounded = CrossEncoderTokenPositions.bound(target.content());
        PassageTokenizer.Tokens tokens = tokenizer.tokenize(bounded);
        int questionTokens = tokenizer.tokenize(CrossEncoderTokenPositions.bound(target.question())).count();
        int passageTokens = tokens.count();
        int windowLength = CrossEncoderTokenPositions.windowLength(questionTokens, passageTokens, crossEncoder.getMaxLength());
        int[] starts = CrossEncoderTokenPositions.windowStarts(passageTokens, windowLength, scoring);
        TokenSpan span = CrossEncoderTokenPositions.tokenSpan(tokens, target.charStart(), target.charEnd());
        assertThat(span).as(target.questionId() + " phrase yields tokens").isNotNull();
        return new Visibility(questionTokens, passageTokens, windowLength, starts, span,
                CrossEncoderTokenPositions.head(span, windowLength),
                CrossEncoderTokenPositions.windowsHoldingWholly(passageTokens, windowLength, starts, span));
    }

    /**
     * Every chunk id of {@code pool} in reranked order for this question and scoring, scored once and kept: a pool of 35 chunks is 35 to
     * 280 model rows, and the grid and chunk-size experiments read many ranks out of the same ordering. Keyed by {@code poolKey}, which
     * must identify the pool's contents, since two pools of the same question and scoring differ only by it.
     */
    private List<Long> ranking(String poolKey, List<RetrievedFilingChunk> pool, String question, Scoring scoring) {
        return rankings.computeIfAbsent(poolKey + '|' + question + '|' + scoring.label(), key -> {
            FilingReranker.ScoredReranking ranked = new CrossEncoderReranker(scorer(scoring), files.version())
                    .rerankScored(question, pool, pool.size());
            return ranked.order().stream().map(candidate -> pool.get(candidate.inputIndex()).chunkId()).toList();
        });
    }

    /** The 1-based reranked position of {@code chunkId} in {@link #ranking}, or 0 when the pool does not hold it. */
    private int rankOf(long chunkId, String poolKey, List<RetrievedFilingChunk> pool, String question, Scoring scoring) {
        return ranking(poolKey, pool, question, scoring).indexOf(chunkId) + 1;
    }

    private List<Long> topIds(String poolKey, List<RetrievedFilingChunk> pool, String question, Scoring scoring) {
        List<Long> order = ranking(poolKey, pool, question, scoring);
        return order.subList(0, Math.min(5, order.size()));
    }

    /** A scorer at these settings, loaded once per distinct {@link Scoring#label()} and closed after the class. */
    private OnnxCrossEncoderScorer scorer(Scoring scoring) {
        return scorers.computeIfAbsent(scoring.label(), label -> {
            OnnxCrossEncoderScorer built = new OnnxCrossEncoderScorer(files.modelPath(), files.tokenizerPath(),
                    crossEncoder.getMaxLength(), crossEncoder.getBatchSize(), scoring.mode(),
                    scoring.mode() == PassageScoring.HEAD ? 0 : scoring.windowOverlapTokens(),
                    scoring.mode() == PassageScoring.HEAD ? 1 : scoring.maxWindows());
            assertThat(built.scoring()).isEqualTo(label);
            return built;
        });
    }

    /** Every stored chunk of one filing section as retrieval candidates, in stored order; only the id and content are read downstream. */
    private List<RetrievedFilingChunk> pool(String accessionNo, String sectionKey) {
        List<StoredChunk> section = section(accessionNo, sectionKey);
        List<RetrievedFilingChunk> pool = new ArrayList<>(section.size());
        for (StoredChunk stored : section) {
            pool.add(new RetrievedFilingChunk(stored.id(), null, null, null, accessionNo, null, null, null, sectionKey, null,
                    pool.size(), stored.content(), null, 0.0));
        }
        return List.copyOf(pool);
    }

    private static RetrievedFilingChunk chunk(long id, String content, Target target) {
        return new RetrievedFilingChunk(id, null, target.ticker(), null, target.accessionNo(), null, LocalDate.EPOCH, LocalDate.EPOCH,
                target.sectionKey(), null, (int) id, content, null, 0.0);
    }

    /**
     * {@code text} split into pieces of at most {@code size} characters that advance by {@code size - overlap}, each piece ending at the
     * last paragraph, sentence, or word boundary in its second half when there is one: the rule
     * {@link project.stockrecommendationengine.rag.ingestion.FilingChunker} applies at a fixed 4,000 and 500, parameterised here so size
     * is the only factor that varies between rows of the {@code chunkSize} experiment.
     */
    private static List<String> split(String text, int size, int overlap) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + size, text.length());
            if (end < text.length()) {
                int minimumEnd = start + size / 2;
                int paragraph = text.lastIndexOf("\n\n", end);
                int sentence = text.lastIndexOf(". ", end - 1);
                int word = text.lastIndexOf(' ', end);
                if (paragraph >= minimumEnd) end = paragraph;
                else if (sentence >= minimumEnd) end = sentence + 1;
                else if (word >= minimumEnd) end = word;
            }
            String piece = text.substring(start, end).trim();
            if (!piece.isBlank()) pieces.add(piece);
            if (end >= text.length()) break;
            start = Math.max(end - overlap, start + 1);
        }
        return pieces;
    }

    /** A phrase on one log line: whitespace collapsed, cut to 90 characters. */
    private static String oneLine(String text) {
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 90 ? collapsed : collapsed.substring(0, 90) + "...";
    }

}

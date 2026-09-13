package project.stockrecommendationengine.rag.evaluation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.evaluation.EvidenceChunkRepository.StoredChunk;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionResult;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionTrace;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.ChunkEvidence;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.OccurrenceEvidence;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.PhraseEvidence;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.QuestionEvidence;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.RankedChunk;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.Settings;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.Span;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderProperties;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderTokenPositions;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderTokenPositions.Scoring;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderTokenPositions.TokenSpan;
import project.stockrecommendationengine.rag.retrieval.PassageTokenizer;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.FusedCandidate;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.Outcome;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.RerankedCandidate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static project.stockrecommendationengine.rag.evaluation.EvidenceValue.derived;
import static project.stockrecommendationengine.rag.evaluation.EvidenceValue.observed;
import static project.stockrecommendationengine.rag.evaluation.EvidenceValue.unknown;

/**
 * Builds the per-question evidence report of a stored evaluation snapshot (RAG.md, Retrieval Evaluation, Evidence report). Reads the
 * snapshot (its results, properties, and traces), the bundled evaluation set the snapshot names, and stored chunk text; computes token
 * positions with the cross-encoder's tokenizer ({@link PassageTokenizer}) and window arithmetic ({@link CrossEncoderTokenPositions}).
 * Deterministic and model-free: nothing here embeds, scores, or calls a chat model, and nothing is written.
 * <p>
 * Rules that decide a basis:
 * <ul>
 * <li>Candidate and score fields come only from the question's trace; without one they are unknown ({@value #NO_TRACE} on a snapshot
 * stored without traces), never filled from a default or another snapshot.</li>
 * <li>Token fields need the tokenizer bean (else {@value #TOKENIZER_UNAVAILABLE}) and a snapshot {@code rerankerVersion} equal to the
 * loaded model's version (else unknown with the recorded and loaded versions), so they are never computed with another tokenizer.</li>
 * <li>{@code max-length} is not recorded in snapshots: it is observed from the current configuration and named as such.</li>
 * <li>Window starts and window membership also need a recorded, recognised {@code rerankerScoring}; head membership does not.</li>
 * </ul>
 */
@Service
public class RetrievalEvidenceService {
    static final String NO_TRACE = "no trace";
    static final String NO_TRACE_FOR_QUESTION = "no trace for this question (its retrieval failed)";
    static final String TOKENIZER_UNAVAILABLE = "tokenizer unavailable";
    static final String NO_RERANKER_VERSION = "snapshot records no rerankerVersion";
    static final String NO_RERANKER_SCORING = "snapshot records no rerankerScoring";
    static final String PHRASE_NOT_MAPPED = "phrase position not mappable: the per-character normalisation differs from RetrievalEvaluationService.normalise";
    static final String NO_OVERLAPPING_TOKEN = "no token overlaps the phrase";

    static final String SOURCE_RESULTS = "snapshot results";
    static final String SOURCE_FUSED = "trace fused (null: not in the fused list)";
    static final String SOURCE_RETURNED = "trace returnedChunkIds (null: not returned)";
    static final String SOURCE_CANDIDATES = "trace rerank candidates (null: not a rerank input)";
    static final String SOURCE_OFF = "trace rerank outcome OFF (not reranked)";
    static final String SOURCE_HOLDING = "sec_filing_chunks at report time: chunks of the phrase's accession and section whose text contains the phrase"
            + " (RetrievalEvaluationService.matches)";
    static final String SOURCE_MAX_LENGTH = "current configuration rag.retrieval.cross-encoder.max-length (snapshots do not record it)";
    static final String SOURCE_LOADED_MODEL = "loaded cross-encoder model files";

    static final String RULE_SCORING = "parsed from snapshot properties.rerankerScoring";
    static final String RULE_SCORING_HEAD = "parsed from snapshot properties.rerankerScoring (head scoring has no overlap or window count)";
    static final String RULE_QUERY_TOKENS = "tokens of the question text bounded to 20,000 characters, tokenized alone by the loaded cross-encoder tokenizer";
    static final String RULE_CHUNK_TOKENS = "tokens of the stored chunk text bounded to 20,000 characters, tokenized alone by the loaded cross-encoder tokenizer";
    static final String RULE_WINDOW_LENGTH = "W = max-length - 3 - the query tokens kept against the whole chunk, longest first"
            + " (CrossEncoderPairAssembler.windowLength)";
    static final String RULE_WINDOW_STARTS = "start token of each scored row under the snapshot's rerankerScoring: head one row at 0; max-window"
            + " CrossEncoderPairAssembler.windowStarts(chunk tokens, W, overlap, max-windows)";
    static final String RULE_CHARACTER_SPAN = "occurrence of the normalised phrase in the normalised chunk text (whitespace runs collapsed, trimmed,"
            + " lower-cased), mapped to UTF-16 offsets of the stored text, end exclusive";
    static final String RULE_TOKEN_SPAN = "tokens of the whole-chunk tokenization whose character span overlaps the occurrence, end exclusive";
    static final String RULE_HEAD = "wholly: token span end <= W; partly: start < W < end; not: start >= W";
    static final String RULE_WINDOWS_HOLDING = "1-based rows whose tokens [start, start + min(W, chunk tokens)) contain the whole token span"
            + " (empty: no row holds it wholly)";
    static final String RULE_FALLBACK_INPUT = "fused position <= trace rerank inputCount (the reranker receives the first inputCount fused chunks)";
    static final String RULE_SET_BY_VERSION = "the bundled set whose version equals the snapshot's set_version (the snapshot records no properties.set)";

    static final String RERANKED_ORDER = "reranked order";
    static final String FUSED_ORDER = "fused order";

    private final RetrievalEvaluationRepository snapshots;
    private final RetrievalEvaluationSetLoader loader;
    private final EvidenceChunkRepository chunks;
    private final CrossEncoderProperties crossEncoder;
    private final Optional<PassageTokenizer> tokenizer;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    public RetrievalEvidenceService(RetrievalEvaluationRepository snapshots, RetrievalEvaluationSetLoader loader, EvidenceChunkRepository chunks,
            CrossEncoderProperties crossEncoder, Optional<PassageTokenizer> tokenizer) {
        this.snapshots = snapshots;
        this.loader = loader;
        this.chunks = chunks;
        this.crossEncoder = crossEncoder;
        this.tokenizer = tokenizer;
    }

    /** The report for the stored snapshot {@code snapshotId}; empty when there is no such snapshot. */
    public Optional<RetrievalEvidenceReport> report(long snapshotId) {
        return snapshots.findById(snapshotId).map(this::report);
    }

    /** The report as JSON text, the document {@link #markdown} renders. */
    public String json(RetrievalEvidenceReport report) {
        return json.writeValueAsString(report);
    }

    /** The report rendered as markdown tables from its JSON alone ({@link EvidenceMarkdownRenderer}). */
    public String markdown(RetrievalEvidenceReport report) {
        return EvidenceMarkdownRenderer.render(parse(json(report)));
    }

    /** Parses report JSON keeping every number's digits as written. */
    JsonNode parse(String reportJson) {
        return json.readTree(reportJson);
    }

    RetrievalEvidenceReport report(RetrievalEvaluation snapshot) {
        return new Run(snapshot).report();
    }

    /** One report's state: the snapshot, the resolved set, and the caches of section chunks and tokenizations. */
    private final class Run {
        private final RetrievalEvaluation snapshot;
        private final Map<String, Object> properties;
        private final Map<String, List<StoredChunk>> sectionChunks = new HashMap<>();
        private final Map<Long, PassageTokenizer.Tokens> chunkTokens = new HashMap<>();
        private final Map<String, RetrievalTrace> traces = new HashMap<>();
        private final int maxLength = crossEncoder.getMaxLength();
        private RetrievalEvaluationSet set;
        private String setSource;
        private String setReason;
        private Scoring scoring;
        private String scoringReason;
        private String tokenReason;

        Run(RetrievalEvaluation snapshot) {
            this.snapshot = snapshot;
            this.properties = snapshot.properties() == null ? Map.of() : snapshot.properties();
            if (snapshot.traces() != null) {
                for (QuestionTrace trace : snapshot.traces()) traces.put(trace.id(), trace.trace());
            }
        }

        RetrievalEvidenceReport report() {
            EvidenceValue<String> setValue = resolveSet();
            Settings settings = settings();
            List<QuestionEvidence> questions = new ArrayList<>();
            for (QuestionResult result : snapshot.results()) questions.add(question(result));
            return new RetrievalEvidenceReport(snapshot.id(), observed(snapshot.setVersion(), "snapshot set_version"), setValue,
                    observed(snapshot.traces() != null, "snapshot traces (properties.trace " + (properties.containsKey("trace") ? properties.get("trace") : "absent") + ")"),
                    settings, List.copyOf(questions));
        }

        private EvidenceValue<String> resolveSet() {
            Object recorded = properties.get("set");
            if (recorded instanceof String resource) {
                try {
                    set = loader.load(resource);
                } catch (IllegalStateException failure) {
                    setReason = "bundled set " + resource + " cannot be loaded: " + failure.getMessage();
                    return observed(resource, "snapshot properties.set");
                }
                setSource = "bundled set " + resource;
                if (!set.version().equals(snapshot.setVersion())) {
                    setReason = "bundled set " + resource + " is version " + set.version() + " but the snapshot was evaluated on " + snapshot.setVersion();
                    set = null;
                }
                return observed(resource, "snapshot properties.set");
            }
            for (String resource : List.of(RetrievalEvaluationSetLoader.DEFAULT_RESOURCE, RetrievalEvaluationSetLoader.V1_RESOURCE)) {
                RetrievalEvaluationSet candidate = loader.load(resource);
                if (candidate.version().equals(snapshot.setVersion())) {
                    set = candidate;
                    setSource = "bundled set " + resource;
                    return derived(resource, RULE_SET_BY_VERSION);
                }
            }
            setReason = "snapshot records no properties.set and no bundled set has version " + snapshot.setVersion();
            return unknown(setReason);
        }

        private Settings settings() {
            Object recordedScoring = properties.get("rerankerScoring");
            EvidenceValue<String> passageScoring;
            EvidenceValue<Integer> overlap;
            EvidenceValue<Integer> maxWindows;
            if (recordedScoring == null) {
                scoringReason = NO_RERANKER_SCORING;
                passageScoring = unknown(scoringReason);
                overlap = unknown(scoringReason);
                maxWindows = unknown(scoringReason);
            } else {
                try {
                    scoring = Scoring.parse(String.valueOf(recordedScoring));
                    passageScoring = derived(scoring.mode().label(), RULE_SCORING);
                    String rule = scoring.windowOverlapTokens() == null ? RULE_SCORING_HEAD : RULE_SCORING;
                    overlap = derived(scoring.windowOverlapTokens(), rule);
                    maxWindows = derived(scoring.maxWindows(), rule);
                } catch (IllegalArgumentException unrecognised) {
                    scoringReason = unrecognised.getMessage();
                    passageScoring = unknown(scoringReason);
                    overlap = unknown(scoringReason);
                    maxWindows = unknown(scoringReason);
                }
            }
            Object recordedVersion = properties.get("rerankerVersion");
            String loadedVersion = tokenizer.map(PassageTokenizer::modelVersion).orElse(null);
            if (tokenizer.isEmpty()) tokenReason = TOKENIZER_UNAVAILABLE;
            else if (recordedVersion == null) tokenReason = NO_RERANKER_VERSION;
            else if (!recordedVersion.equals(loadedVersion)) {
                tokenReason = "snapshot rerankerVersion " + recordedVersion + " differs from the loaded model version " + loadedVersion;
            }
            return new Settings(property("rerank", Boolean.class), property("rerankCandidates", Integer.class), property("reranker", String.class),
                    property("rerankerVersion", String.class),
                    tokenizer.isPresent() ? observed(loadedVersion, SOURCE_LOADED_MODEL) : unknown(TOKENIZER_UNAVAILABLE),
                    property("rerankerScoring", String.class), passageScoring, overlap, maxWindows, observed(maxLength, SOURCE_MAX_LENGTH));
        }

        /** A snapshot property as recorded; unknown when the snapshot does not record it. */
        private <T> EvidenceValue<T> property(String name, Class<T> type) {
            if (!properties.containsKey(name)) return unknown("snapshot records no properties." + name);
            Object value = properties.get(name);
            if (value == null) return observed(null, "snapshot properties." + name);
            if (type == Integer.class && value instanceof Number number) return observed(type.cast(number.intValue()), "snapshot properties." + name);
            if (type.isInstance(value)) return observed(type.cast(value), "snapshot properties." + name);
            return unknown("snapshot properties." + name + " is not a " + type.getSimpleName() + ": " + value);
        }

        private QuestionEvidence question(QuestionResult result) {
            RetrievalEvaluationQuestion question = set == null ? null : set.questions().stream().filter(q -> q.id().equals(result.id())).findFirst().orElse(null);
            String phraseReason = set == null ? setReason : question == null ? "question " + result.id() + " is not in the " + setSource : null;
            String traceReason = snapshot.traces() == null ? NO_TRACE
                    : !traces.containsKey(result.id()) ? "no trace recorded for this question" : traces.get(result.id()) == null ? NO_TRACE_FOR_QUESTION : null;
            RetrievalTrace trace = traceReason == null ? traces.get(result.id()) : null;
            String rerankReason = trace != null && trace.rerank() == null ? "trace records no rerank step" : traceReason;

            Integer queryTokens = null;
            EvidenceValue<Integer> queryTokensValue;
            if (tokenReason != null) queryTokensValue = unknown(tokenReason);
            else if (question == null) queryTokensValue = unknown(phraseReason);
            else {
                queryTokens = tokenizer.orElseThrow().tokenize(CrossEncoderTokenPositions.bound(question.question())).count();
                queryTokensValue = derived(queryTokens, RULE_QUERY_TOKENS);
            }

            List<PhraseEvidence> phrases = new ArrayList<>();
            Set<Long> holding = new LinkedHashSet<>();
            if (question != null) {
                for (ExpectedPassage passage : question.expected()) {
                    List<StoredChunk> held = sectionChunks.computeIfAbsent(passage.accessionNo() + '\n' + passage.sectionKey(),
                            key -> chunks.chunks(passage.accessionNo(), passage.sectionKey())).stream().filter(chunk -> holds(chunk, passage)).toList();
                    List<ChunkEvidence> chunkEvidence = new ArrayList<>();
                    for (StoredChunk chunk : held) {
                        holding.add(chunk.id());
                        chunkEvidence.add(chunk(chunk, passage, queryTokens, trace, rerankReason));
                    }
                    phrases.add(new PhraseEvidence(passage.accessionNo(), passage.sectionKey(), passage.phrase(), observed(!held.isEmpty(), SOURCE_HOLDING),
                            List.copyOf(chunkEvidence)));
                }
            }

            EvidenceValue<String> outcome;
            EvidenceValue<String> fallbackReason;
            EvidenceValue<String> scoresNotRecorded;
            EvidenceValue<Integer> fusedCount;
            EvidenceValue<Integer> inputCount;
            if (rerankReason != null) {
                outcome = unknown(rerankReason);
                fallbackReason = unknown(rerankReason);
                scoresNotRecorded = unknown(rerankReason);
                inputCount = unknown(rerankReason);
            } else {
                outcome = observed(trace.rerank().outcome().name(), "trace rerank outcome");
                fallbackReason = observed(trace.rerank().fallbackReason(), "trace rerank fallbackReason (null: not a fallback)");
                scoresNotRecorded = observed(trace.rerank().scoresNotRecorded(),
                        "trace rerank scoresNotRecorded (null: every rerank input carries its position and score, or not reranked)");
                inputCount = observed(trace.rerank().inputCount(), "trace rerank inputCount (null: reranking off)");
            }
            fusedCount = traceReason != null ? unknown(traceReason) : observed(trace.fused().size(), "trace fused");

            Ranking ranking = ranking(trace, rerankReason, phraseReason, holding);
            return new QuestionEvidence(result.id(), result.ticker(), result.kind() == null ? null : result.kind().name(),
                    question == null ? null : question.question(), observed(result.rank(), SOURCE_RESULTS + " rank (null: no matching chunk in the window)"),
                    observed(result.matchedChunkId(), SOURCE_RESULTS + " matchedChunkId (null: no matching chunk in the window)"),
                    observed(result.retrievalStrategy(), SOURCE_RESULTS + " retrievalStrategy (null: retrieval error, or stored before the field)"),
                    observed(result.error(), SOURCE_RESULTS + " error (null: none)"), outcome, fallbackReason, scoresNotRecorded, fusedCount, inputCount,
                    queryTokensValue, question == null ? unknown(phraseReason) : observed(question.expected().size(), setSource), List.copyOf(phrases),
                    ranking.name(), ranking.best(), ranking.bestPosition(), ranking.above());
        }

        private boolean holds(StoredChunk chunk, ExpectedPassage passage) {
            RetrievedFilingChunk asRetrieved = new RetrievedFilingChunk(chunk.id(), null, null, null, passage.accessionNo(), null, null, null,
                    passage.sectionKey(), null, null, chunk.content(), null, 0);
            return RetrievalEvaluationService.matches(asRetrieved, passage);
        }

        private ChunkEvidence chunk(StoredChunk chunk, ExpectedPassage passage, Integer queryTokens, RetrievalTrace trace, String rerankReason) {
            String content = chunk.content() == null ? "" : chunk.content();
            String bounded = CrossEncoderTokenPositions.bound(content);
            PassageTokenizer.Tokens tokens = tokenReason == null
                    ? chunkTokens.computeIfAbsent(chunk.id(), id -> tokenizer.orElseThrow().tokenize(bounded)) : null;
            Integer windowLength = tokens == null ? null : CrossEncoderTokenPositions.windowLength(queryTokens, tokens.count(), maxLength);
            String windowReason = tokenReason != null ? tokenReason : scoringReason;
            int[] starts = windowReason == null ? CrossEncoderTokenPositions.windowStarts(tokens.count(), windowLength, scoring) : null;

            List<OccurrenceEvidence> occurrences = new ArrayList<>();
            List<PhraseOccurrences.CharacterSpan> spans = PhraseOccurrences.find(content, passage.phrase());
            if (spans == null || spans.isEmpty()) {
                EvidenceValue<Span> none = unknown(PHRASE_NOT_MAPPED);
                occurrences.add(new OccurrenceEvidence(none, unknown(PHRASE_NOT_MAPPED), unknown(PHRASE_NOT_MAPPED), unknown(PHRASE_NOT_MAPPED)));
            } else {
                for (PhraseOccurrences.CharacterSpan span : spans) {
                    String spanReason = tokenReason;
                    TokenSpan tokenSpan = null;
                    if (spanReason == null && span.end() > bounded.length()) {
                        spanReason = "phrase ends past the " + CrossEncoderTokenPositions.maxChars() + "-character input bound";
                    }
                    if (spanReason == null) {
                        tokenSpan = CrossEncoderTokenPositions.tokenSpan(tokens, span.start(), span.end());
                        if (tokenSpan == null) spanReason = NO_OVERLAPPING_TOKEN;
                    }
                    String holdingReason = spanReason != null ? spanReason : windowReason;
                    occurrences.add(new OccurrenceEvidence(derived(new Span(span.start(), span.end()), RULE_CHARACTER_SPAN),
                            spanReason != null ? unknown(spanReason) : derived(new Span(tokenSpan.start(), tokenSpan.end()), RULE_TOKEN_SPAN),
                            spanReason != null ? unknown(spanReason)
                                    : derived(CrossEncoderTokenPositions.head(tokenSpan, windowLength).name().toLowerCase(java.util.Locale.ROOT), RULE_HEAD),
                            holdingReason != null ? unknown(holdingReason)
                                    : derived(CrossEncoderTokenPositions.windowsHoldingWholly(tokens.count(), windowLength, starts, tokenSpan), RULE_WINDOWS_HOLDING)));
                }
            }

            Candidate candidate = candidate(chunk.id(), trace, rerankReason);
            return new ChunkEvidence(chunk.id(), tokens == null ? unknown(tokenReason) : derived(tokens.count(), RULE_CHUNK_TOKENS),
                    tokens == null ? unknown(tokenReason) : derived(windowLength, RULE_WINDOW_LENGTH),
                    starts == null ? unknown(windowReason) : derived(Arrays.stream(starts).boxed().toList(), RULE_WINDOW_STARTS), List.copyOf(occurrences),
                    candidate.fusedPosition(), candidate.rerankInput(), candidate.rerankedPosition(), candidate.score(), candidate.windowCount(),
                    candidate.windowScores(), candidate.returnedPosition());
        }
    }

    private record Candidate(EvidenceValue<Integer> fusedPosition, EvidenceValue<Boolean> rerankInput, EvidenceValue<Integer> rerankedPosition,
            EvidenceValue<Float> score, EvidenceValue<Integer> windowCount, EvidenceValue<List<Float>> windowScores, EvidenceValue<Integer> returnedPosition) {
    }

    /** A chunk's candidate fields from the question's trace; every field unknown with {@code reason} when there is no usable trace. */
    private static Candidate candidate(long chunkId, RetrievalTrace trace, String reason) {
        if (trace == null) {
            return new Candidate(unknown(reason), unknown(reason), unknown(reason), unknown(reason), unknown(reason), unknown(reason), unknown(reason));
        }
        Integer fusedPosition = fusedPosition(trace, chunkId);
        int returned = trace.returnedChunkIds() == null ? -1 : trace.returnedChunkIds().indexOf(chunkId);
        EvidenceValue<Integer> fused = observed(fusedPosition, SOURCE_FUSED);
        EvidenceValue<Integer> returnedPosition = trace.returnedChunkIds() == null ? unknown("trace records no returnedChunkIds")
                : observed(returned < 0 ? null : returned + 1, SOURCE_RETURNED);
        if (reason != null) {
            return new Candidate(fused, unknown(reason), unknown(reason), unknown(reason), unknown(reason), unknown(reason), returnedPosition);
        }
        RetrievalTrace.Rerank rerank = trace.rerank();
        return switch (rerank.outcome()) {
            case OFF -> new Candidate(fused, observed(false, SOURCE_OFF), observed(null, SOURCE_OFF), observed(null, SOURCE_OFF), observed(null, SOURCE_OFF),
                    observed(null, SOURCE_OFF), returnedPosition);
            case FALLBACK -> {
                String noScores = fallbackNoScores(rerank);
                int inputCount = rerank.inputCount() == null ? 0 : rerank.inputCount();
                yield new Candidate(fused, rerank.inputCount() == null ? unknown("trace records no rerank inputCount")
                        : derived(fusedPosition != null && fusedPosition <= inputCount, RULE_FALLBACK_INPUT), unknown(noScores), unknown(noScores),
                        unknown(noScores), unknown(noScores), returnedPosition);
            }
            case RERANKED -> {
                RerankedCandidate reranked = rerank.candidates() == null ? null
                        : rerank.candidates().stream().filter(c -> c.chunkId() == chunkId).findFirst().orElse(null);
                if (rerank.candidates() == null) {
                    String missing = "trace records no rerank candidates";
                    yield new Candidate(fused, unknown(missing), unknown(missing), unknown(missing), unknown(missing), unknown(missing), returnedPosition);
                }
                if (reranked == null) {
                    yield new Candidate(fused, observed(false, SOURCE_CANDIDATES), observed(null, SOURCE_CANDIDATES), observed(null, SOURCE_CANDIDATES),
                            observed(null, SOURCE_CANDIDATES), observed(null, SOURCE_CANDIDATES), returnedPosition);
                }
                String notRecorded = rerank.scoresNotRecorded() == null ? "not recorded in the trace" : rerank.scoresNotRecorded();
                yield new Candidate(fused, observed(true, SOURCE_CANDIDATES), orUnknown(reranked.rerankedPosition(), "trace rerank candidates rerankedPosition", notRecorded),
                        orUnknown(reranked.score(), "trace rerank candidates score", notRecorded),
                        orUnknown(reranked.windowCount(), "trace rerank candidates windowCount", notRecorded),
                        orUnknown(reranked.windowScores(), "trace rerank candidates windowScores", notRecorded), returnedPosition);
            }
        };
    }

    private static String fallbackNoScores(RetrievalTrace.Rerank rerank) {
        return "rerank fell back (" + rerank.fallbackReason() + "): the trace records no reranked positions or scores";
    }

    private static <T> EvidenceValue<T> orUnknown(T value, String source, String reason) {
        return value == null ? unknown(reason) : observed(value, source);
    }

    private static Integer fusedPosition(RetrievalTrace trace, long chunkId) {
        if (trace.fused() == null) return null;
        return trace.fused().stream().filter(f -> f.chunkId() == chunkId).map(FusedCandidate::fusedPosition).findFirst().orElse(null);
    }

    private record Ranking(EvidenceValue<String> name, EvidenceValue<Long> best, EvidenceValue<Integer> bestPosition,
            EvidenceValue<List<RankedChunk>> above) {
        static Ranking unknownBecause(String reason) {
            return new Ranking(unknown(reason), unknown(reason), unknown(reason), unknown(reason));
        }
    }

    /**
     * The order the response was cut from and the chunks ranked above the best accepted chunk in it: the reranker's order when RERANKED,
     * the fused order when OFF or FALLBACK (retrieval returned the fused order).
     */
    private static Ranking ranking(RetrievalTrace trace, String rerankReason, String phraseReason, Set<Long> holding) {
        if (rerankReason != null) return Ranking.unknownBecause(rerankReason);
        RetrievalTrace.Rerank rerank = trace.rerank();
        boolean reranked = rerank.outcome() == Outcome.RERANKED;
        if (reranked && rerank.candidates() == null) return Ranking.unknownBecause("trace records no rerank candidates");
        String name = reranked ? RERANKED_ORDER : FUSED_ORDER;
        EvidenceValue<String> rankingName = observed(name, "trace rerank outcome " + rerank.outcome());
        if (phraseReason != null) {
            String reason = "accepted phrases unknown: " + phraseReason;
            return new Ranking(rankingName, unknown(reason), unknown(reason), unknown(reason));
        }
        String positionSource = reranked ? "trace rerank candidates rerankedPosition" : "trace fused fusedPosition";
        String notRecorded = rerank.scoresNotRecorded() == null ? "not recorded in the trace" : rerank.scoresNotRecorded();
        String noScores = rerank.outcome() == Outcome.FALLBACK ? fallbackNoScores(rerank) : null;
        List<RankedChunk> entries = new ArrayList<>();
        boolean incomplete = false;
        if (reranked) {
            for (RerankedCandidate candidate : rerank.candidates()) {
                if (candidate.rerankedPosition() == null) {
                    incomplete = true;
                    continue;
                }
                entries.add(new RankedChunk(candidate.chunkId(), observed(candidate.rerankedPosition(), positionSource),
                        observed(candidate.fusedPosition(), "trace rerank candidates fusedPosition"),
                        orUnknown(candidate.score(), "trace rerank candidates score", notRecorded),
                        orUnknown(candidate.windowScores(), "trace rerank candidates windowScores", notRecorded)));
            }
        } else {
            for (FusedCandidate fused : trace.fused()) {
                entries.add(new RankedChunk(fused.chunkId(), observed(fused.fusedPosition(), positionSource), observed(fused.fusedPosition(), "trace fused"),
                        noScores != null ? unknown(noScores) : observed(null, SOURCE_OFF), noScores != null ? unknown(noScores) : observed(null, SOURCE_OFF)));
            }
        }
        for (int index = 0; index < entries.size(); index++) {
            RankedChunk entry = entries.get(index);
            if (holding.contains(entry.chunkId())) {
                return new Ranking(rankingName, observed(entry.chunkId(), "trace " + name + ": first chunk holding an accepted phrase"), entry.position(),
                        observed(List.copyOf(entries.subList(0, index)), "trace " + name + ": every chunk ranked above the best accepted chunk"));
            }
        }
        if (incomplete) {
            String reason = "reranked positions not recorded for every rerank input: " + notRecorded;
            return new Ranking(rankingName, unknown(reason), unknown(reason), unknown(reason));
        }
        String none = "trace " + name + ": no chunk holding an accepted phrase is in it";
        return new Ranking(rankingName, observed(null, none), observed(null, none),
                observed(List.copyOf(entries), "trace " + name + ": every chunk (no chunk holding an accepted phrase is in it)"));
    }
}

package project.stockrecommendationengine.rag.evaluation;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.evaluation.AnswerEvaluation.Aggregates;
import project.stockrecommendationengine.rag.evaluation.AnswerEvaluation.QuestionResult;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationQuestion.Kind;
import project.stockrecommendationengine.rag.ingestion.FilingIngestionProperties;
import project.stockrecommendationengine.rag.repository.FilingRetrievalRepository;
import project.stockrecommendationengine.rag.repository.SECFilingRepository;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalProperties;
import project.stockrecommendationengine.recommendation.EvaluationRun;
import project.stockrecommendationengine.recommendation.EvaluationRun.ShownPassage;
import project.stockrecommendationengine.recommendation.RecommendationProperties;
import project.stockrecommendationengine.recommendation.RecommendationRequest;
import project.stockrecommendationengine.recommendation.RecommendationResponse;
import project.stockrecommendationengine.recommendation.RecommendationService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs evaluation-set questions through the recommendation loop and stores one snapshot per pass. Each question is sent
 * as a request any user could send (the set's ticker and question text, no conid, no portfolio) to
 * {@link RecommendationService#recommendForEvaluation}, which validates it, runs it with the same tools, passage screen
 * and citation checks as a user run, and stores it with purpose EVALUATION. This class builds no prompt and calls no
 * model itself; it reads what the run returned and computes deterministic measures from it.
 *
 * <p>Runs are sequential with {@code rag.evaluation.answers.pause-ms} between them (none after the last). A pass stops
 * and is stored partial when the service answers HTTP 429 (both worker slots busy; that question is listed with the
 * error and no run), when a run reports MODEL_UNAVAILABLE (the provider failed after the service's single rate-limit
 * retry, at any role), when a run reports searchFilings:TOOL_UNAVAILABLE (retrieval or its embedding call failed, so
 * further runs would spend chat tokens on no evidence), when any other exception leaves the service, or when the pause
 * is interrupted. A request the service rejects as invalid (HTTP 400) is listed with the error and the pass continues.
 * One pass runs at a time.
 *
 * <p>The snapshot is written once, at the end. NUL characters are removed from run text first (PostgreSQL's jsonb
 * refuses them), the write runs with the thread's interrupt flag cleared (restored afterwards), and when the write
 * still fails the snapshot is kept: logged on one line after {@value #SNAPSHOT_LOG_PREFIX}, written to a file under
 * {@code rag.evaluation.answers.fallback-dir}, and reported by {@link AnswerEvaluationNotStoredException} (HTTP 500).
 */
@Service
@Slf4j
public class AnswerEvaluationService {
    static final String INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE";
    static final String INVALID_CITATION = "INVALID_CITATION";
    static final String MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";
    static final String RATE_LIMIT_RETRIED = "MODEL_RATE_LIMITED_RETRIED";
    static final String CAPACITY_REACHED = "RECOMMENDATION_CAPACITY_REACHED";
    static final String INVALID_REQUEST = "INVALID_REQUEST";
    /** The limitation a run carries when a filing search failed for a reason other than its arguments (RecommendationService.executeCall). */
    static final String RETRIEVAL_TOOL_UNAVAILABLE = "searchFilings:TOOL_UNAVAILABLE";
    static final String RETRIEVAL_UNAVAILABLE = "RETRIEVAL_UNAVAILABLE";
    /** Prefix of the one INFO line that carries the whole serialised snapshot when it could not be stored. */
    static final String SNAPSHOT_LOG_PREFIX = "ANSWER_EVALUATION_SNAPSHOT ";
    /** Statuses RecommendationService gives a validated answer; every other status is a stop code with an empty reasoning. */
    private static final Set<String> ANSWER_STATUSES = Set.of("COMPLETE", "PARTIAL", INSUFFICIENT_EVIDENCE);
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
    private static final int SCALE = 6;

    /** The wait between runs; a seam so tests record pauses instead of sleeping. */
    @FunctionalInterface
    interface Pauser {
        void pause(long millis) throws InterruptedException;
    }

    private final RetrievalEvaluationSetLoader loader;
    private final ObjectProvider<RecommendationService> recommendations;
    private final AnswerEvaluationRepository repository;
    private final RetrievalEvaluationProperties properties;
    private final RecommendationProperties recommendationProperties;
    private final FilingRetrievalProperties retrievalProperties;
    private final FilingIngestionProperties ingestionProperties;
    private final SECFilingRepository filings;
    private final Environment environment;
    private final Pauser pauser;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    public AnswerEvaluationService(RetrievalEvaluationSetLoader loader, ObjectProvider<RecommendationService> recommendations,
            AnswerEvaluationRepository repository, RetrievalEvaluationProperties properties,
            RecommendationProperties recommendationProperties, FilingRetrievalProperties retrievalProperties,
            FilingIngestionProperties ingestionProperties, SECFilingRepository filings, Environment environment) {
        this(loader, recommendations, repository, properties, recommendationProperties, retrievalProperties, ingestionProperties,
                filings, environment, Thread::sleep);
    }

    AnswerEvaluationService(RetrievalEvaluationSetLoader loader, ObjectProvider<RecommendationService> recommendations,
            AnswerEvaluationRepository repository, RetrievalEvaluationProperties properties,
            RecommendationProperties recommendationProperties, FilingRetrievalProperties retrievalProperties,
            FilingIngestionProperties ingestionProperties, SECFilingRepository filings, Environment environment, Pauser pauser) {
        this.loader = loader;
        this.recommendations = recommendations;
        this.repository = repository;
        this.properties = properties;
        this.recommendationProperties = recommendationProperties;
        this.retrievalProperties = retrievalProperties;
        this.ingestionProperties = ingestionProperties;
        this.filings = filings;
        this.environment = environment;
        this.pauser = pauser;
    }

    /**
     * Run one pass and store its snapshot; returns it with its id. {@code questions} is an optional comma-separated list of
     * question ids (kept in set order); {@code limit} then keeps the first so many. Refused with
     * {@link AnswerEvaluationRefusedException} before any run, and so before any model call: 503 when the recommendation
     * service is not enabled, 400 for a blank or unknown id or a limit below 1, 409 while another pass is running.
     */
    public AnswerEvaluation evaluate(String questions, Integer limit) {
        RecommendationService service = recommendations.getIfAvailable();
        if (service == null) {
            throw new AnswerEvaluationRefusedException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Answer evaluation runs the recommendation service, which is disabled: set RECOMMENDATION_ENABLED=true "
                            + "(recommendation.enabled) with a chat model configured. No run was started and no model was called.");
        }
        RetrievalEvaluationSet set = loader.load();
        List<String> filter = parseFilter(questions, set);
        if (limit != null && limit < 1) throw new AnswerEvaluationRefusedException(HttpStatus.BAD_REQUEST, "limit must be 1 or more");
        List<RetrievalEvaluationQuestion> selected = set.questions().stream().filter(q -> filter == null || filter.contains(q.id()))
                .limit(limit == null ? Long.MAX_VALUE : limit).toList();
        if (!running.compareAndSet(false, true)) {
            throw new AnswerEvaluationRefusedException(HttpStatus.CONFLICT,
                    "An answer-evaluation pass is already running; this request started no run and called no model.");
        }
        try {
            return pass(service, set, selected, filter, limit);
        } finally {
            running.set(false);
        }
    }

    private AnswerEvaluation pass(RecommendationService service, RetrievalEvaluationSet set, List<RetrievalEvaluationQuestion> selected,
            List<String> filter, Integer limit) {
        int pauseMs = properties.getAnswers().getPauseMs();
        log.info("Answer evaluation: set={}, selected={}, pauseMs={}, model={}", set.version(), selected.size(), pauseMs, recommendationProperties.getModel());
        List<QuestionResult> results = new ArrayList<>();
        String stopped = null;
        for (int index = 0; index < selected.size() && stopped == null; index++) {
            RetrievalEvaluationQuestion question = selected.get(index);
            if (index > 0) {
                try {
                    pauser.pause(pauseMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    stopped = "INTERRUPTED before " + question.id();
                    break;
                }
            }
            long started = System.nanoTime();
            QuestionResult result;
            try {
                EvaluationRun run = service.recommendForEvaluation(new RecommendationRequest(question.ticker(), question.question(), null, false));
                result = measure(question, run, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                if (providerUnavailable(result)) {
                    stopped = MODEL_UNAVAILABLE + " at " + question.id()
                            + (result.limitations().contains(RATE_LIMIT_RETRIED) ? " after the rate-limit retry" : "");
                } else if (result.limitations().contains(RETRIEVAL_TOOL_UNAVAILABLE)) {
                    stopped = RETRIEVAL_UNAVAILABLE + " at " + question.id();
                }
            } catch (ResponseStatusException refused) {
                int code = refused.getStatusCode().value();
                String error = code == 429 ? CAPACITY_REACHED : code == 400 ? INVALID_REQUEST : "HTTP_" + code;
                result = notRun(question, error, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                if (code != 400) stopped = error + " at " + question.id();
            } catch (RuntimeException failure) {
                String error = failure.getClass().getSimpleName();
                result = notRun(question, error, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                stopped = error + " at " + question.id();
            }
            results.add(result);
            log.info("Answer evaluation question={} run={} status={} error={} retrievedExpected={} visibleToModel={} citedExpected={} "
                            + "cited={} figuresInReasoning={} tokens={} elapsedMs={}", result.id(), result.runId(), result.status(), result.error(),
                    result.retrievedExpected(), result.visibleToModel(), result.citedExpected(), result.citedCount(),
                    result.figuresInReasoning(), result.observedTokens(), result.elapsedMs());
        }
        List<String> notAttempted = selected.stream().skip(results.size()).map(RetrievalEvaluationQuestion::id).toList();
        AnswerEvaluation stored = store(new AnswerEvaluation(null,
                // Microseconds are what the column keeps, so the snapshot returned equals the one read back.
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), set.version(), selected.size(), results.size(),
                stopped != null, stopped, aggregate(results), runProperties(set, pauseMs, filter, limit), List.copyOf(results), notAttempted));
        log.info("Answer evaluation stored: id={}, attempted={} of {}, partial={}, reason={}, totalTokens={}", stored.id(), stored.attempted(),
                stored.questionCount(), stored.partial(), stored.partialReason(), stored.aggregates().totalTokens());
        return stored;
    }

    /**
     * Write the snapshot, or keep it another way. An interrupted service call or pause leaves the thread's interrupt flag
     * set, and a JDBC write on such a thread can fail, so the flag is cleared for the write and restored after it. When
     * the write fails the pass has already spent its tokens: the snapshot goes to the log and to a file, and the caller
     * is told where (exception messages can carry SQL detail, so only the class is logged above debug).
     */
    private AnswerEvaluation store(AnswerEvaluation snapshot) {
        boolean interrupted = Thread.interrupted();
        try {
            return repository.save(snapshot);
        } catch (RuntimeException failure) {
            log.error("Answer evaluation snapshot not stored: {}", failure.getClass().getSimpleName());
            log.debug("Answer evaluation snapshot write failure detail", failure);
            throw keep(snapshot, failure);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private AnswerEvaluationNotStoredException keep(AnswerEvaluation snapshot, RuntimeException failure) {
        String serialised;
        try {
            serialised = json.writeValueAsString(snapshot);
        } catch (RuntimeException unserialisable) {
            log.error("Answer evaluation snapshot could not be serialised: {}", unserialisable.getClass().getSimpleName());
            return new AnswerEvaluationNotStoredException("The pass ended but its snapshot could not be stored or serialised; "
                    + "the per-question INFO log lines of the pass are what remains.", null, failure);
        }
        log.info("{}{}", SNAPSHOT_LOG_PREFIX, serialised);
        try {
            Path directory = Path.of(properties.getAnswers().getFallbackDir()).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path file = directory.resolve("answer-evaluation-" + FILE_STAMP.format(snapshot.evaluatedAt()) + ".json");
            if (Files.exists(file)) file = directory.resolve("answer-evaluation-" + FILE_STAMP.format(snapshot.evaluatedAt()) + "-" + System.nanoTime() + ".json");
            Files.writeString(file, serialised, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            log.error("Answer evaluation snapshot not stored; written to {}. Keep this file: it is the only copy of the pass's snapshot outside the log.", file);
            return new AnswerEvaluationNotStoredException("The pass ended but its snapshot could not be stored in answer_evaluations. "
                    + "It was written to " + file + " and logged on one line after \"" + SNAPSHOT_LOG_PREFIX.trim() + "\". "
                    + "Keep that file: it is the only copy of the snapshot outside the log. "
                    + "The runs of the pass are stored; do not repeat the pass to recover it.", file, failure);
        } catch (IOException | RuntimeException unwritable) {
            log.error("Answer evaluation snapshot not stored and the fallback file could not be written: {}", unwritable.getClass().getSimpleName());
            return new AnswerEvaluationNotStoredException("The pass ended but its snapshot could not be stored in answer_evaluations or "
                    + "written to " + properties.getAnswers().getFallbackDir() + ". It was logged on one line after \""
                    + SNAPSHOT_LOG_PREFIX.trim() + "\". The runs of the pass are stored; do not repeat the pass to recover it.", null, failure);
        }
    }

    /** A blank filter or an id the set does not hold is refused rather than ignored: a typo must not widen a pass that spends tokens. */
    private static List<String> parseFilter(String questions, RetrievalEvaluationSet set) {
        if (questions == null) return null;
        List<String> ids = Arrays.stream(questions.split(",")).map(String::trim).toList();
        Set<String> known = new LinkedHashSet<>();
        set.questions().forEach(question -> known.add(question.id()));
        List<String> unknown = ids.stream().filter(id -> !known.contains(id)).toList();
        if (ids.isEmpty() || !unknown.isEmpty()) {
            throw new AnswerEvaluationRefusedException(HttpStatus.BAD_REQUEST, "questions must list ids of set " + set.version()
                    + " separated by commas; not in the set: " + unknown);
        }
        return ids.stream().distinct().toList();
    }

    /** The provider failed for good during this run: as the run's status, or at the critic or a revision where the draft stands. */
    static boolean providerUnavailable(QuestionResult result) {
        return MODEL_UNAVAILABLE.equals(result.status())
                || result.limitations().stream().anyMatch(code -> code.endsWith(":" + MODEL_UNAVAILABLE));
    }

    /** The frozen per-question measures (plan 2026-09-19, Boundaries), computed from what the run returned. Package-private for tests. */
    static QuestionResult measure(RetrievalEvaluationQuestion question, EvaluationRun run, long elapsedMs) {
        RecommendationResponse response = run.response();
        List<Long> expected = new ArrayList<>();
        List<Long> visible = new ArrayList<>();
        for (ShownPassage shown : run.retrieved()) {
            boolean holds = false;
            boolean seen = false;
            for (ExpectedPassage passage : question.expected()) {
                if (!RetrievalEvaluationService.matches(shown.chunk(), passage)) continue;
                holds = true;
                seen |= shown.shownToModel() != null && RetrievalEvaluationService.normalise(shown.shownToModel())
                        .contains(RetrievalEvaluationService.normalise(passage.phrase()));
            }
            if (holds) expected.add(shown.chunk().chunkId());
            if (seen) visible.add(shown.chunk().chunkId());
        }
        List<RetrievedFilingChunk> cited = response.sources() == null ? List.of() : response.sources();
        List<Long> citedIds = cited.stream().map(RetrievedFilingChunk::chunkId).toList();
        int citedHolding = (int) cited.stream().filter(chunk -> question.expected().stream()
                .anyMatch(passage -> RetrievalEvaluationService.matches(chunk, passage))).count();
        boolean captured = run.evidenceCaptured();
        // Measured on the reasoning as it is stored (U+0000 removed), so the stored snapshot recomputes to the same values.
        String reasoning = clean(response.reasoning());
        FigureCheck figures = question.kind() == Kind.FIGURE ? figures(question, reasoning) : null;
        var critique = response.critique();
        return new QuestionResult(question.id(), question.ticker(), question.kind(), response.runId(), null, clean(response.status()),
                clean(response.assessment()), captured, run.retrieved().size(), captured ? !expected.isEmpty() : null, captured ? List.copyOf(expected) : null,
                captured ? !visible.isEmpty() : null, captured ? List.copyOf(visible) : null, citedHolding > 0, cited.size(), citedHolding, citedIds,
                figures == null ? null : figures.missing().isEmpty(), figures == null ? null : figures.tokens(),
                figures == null ? null : figures.missing(), clean(response.limitations()),
                critique == null ? null : clean(critique.verdict()), critique == null ? null : clean(critique.unsupportedNumerals()),
                response.modelCalls(), response.observedTokens(), elapsedMs, reasoning);
    }

    /** Text without NUL characters: PostgreSQL's jsonb refuses U+0000, and model text is the one place it can come from. */
    static String clean(String text) {
        return text == null || text.indexOf('\0') < 0 ? text : text.replace("\0", "");
    }

    private static List<String> clean(List<String> texts) {
        return texts == null ? List.of() : texts.stream().map(AnswerEvaluationService::clean).toList();
    }

    /**
     * The run produced an answer: its status is one RecommendationService gives a validated draft (COMPLETE, PARTIAL, or
     * INSUFFICIENT_EVIDENCE when that is the model's own assessment) and its reasoning is not blank. A run stopped by a
     * limit or a failure has the stop code as its status and an empty reasoning.
     */
    static boolean answered(QuestionResult result) {
        return result.runId() != null && result.status() != null && ANSWER_STATUSES.contains(result.status())
                && result.reasoning() != null && !result.reasoning().isBlank();
    }

    private static QuestionResult notRun(RetrievalEvaluationQuestion question, String error, long elapsedMs) {
        return new QuestionResult(question.id(), question.ticker(), question.kind(), null, error, null, null, false, 0, null, null, null, null,
                null, 0, 0, List.of(), null, null, null, List.of(), null, null, 0, 0, elapsedMs, null);
    }

    record FigureCheck(List<String> tokens, List<String> missing) { }

    /**
     * {@code figuresInReasoning}: the figures of an accepted phrase are its numeric tokens under the retrieval figure rule
     * ({@link FilingRetrievalRepository#figureTokens}: length 2 or more, years left out). A figure appears in the reasoning when
     * a numeric token of the reasoning (same tokeniser, any length, years kept) has the same numeric value once thousands
     * commas are dropped: 42,000 matches 42000, 40.4 matches "$40.4 billion" and 40.40, 65 matches 65%, 7.0 matches
     * "$7 billion". Tokens are whole numerals, so 7.0 does not match 17. A rescaled or rounded figure (64.4 billion for
     * 64,377) does not match. The check holds when every figure of some accepted phrase appears; phrases
     * without a figure are skipped, and null is returned when no accepted phrase has one. When it does not hold, the phrase
     * with the fewest missing figures (the first on a tie) is the one reported.
     */
    static FigureCheck figures(RetrievalEvaluationQuestion question, String reasoning) {
        Set<String> inReasoning = new LinkedHashSet<>();
        FilingRetrievalRepository.numericTokens(reasoning, 1).forEach(token -> inReasoning.add(canonical(token)));
        FigureCheck best = null;
        for (ExpectedPassage passage : question.expected()) {
            List<String> tokens = FilingRetrievalRepository.figureTokens(passage.phrase());
            if (tokens.isEmpty()) continue;
            List<String> missing = tokens.stream().filter(token -> !inReasoning.contains(canonical(token))).toList();
            if (best == null || missing.size() < best.missing().size()) best = new FigureCheck(tokens, missing);
        }
        return best;
    }

    /** A numeral without thousands commas and trailing zeros, so equal values compare equal; anything unparseable stays as written. */
    static String canonical(String token) {
        String plain = token.replace(",", "");
        try {
            return new BigDecimal(plain).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException notANumber) {
            return plain;
        }
    }

    /** Counts and shares over the attempted questions; see {@link Aggregates} for each denominator. Package-private for tests. */
    static Aggregates aggregate(List<QuestionResult> results) {
        List<QuestionResult> withRun = results.stream().filter(r -> r.runId() != null).toList();
        List<QuestionResult> measured = withRun.stream().filter(QuestionResult::evidenceCaptured).toList();
        int retrieved = count(measured, r -> Boolean.TRUE.equals(r.retrievedExpected()));
        int visible = count(measured, r -> Boolean.TRUE.equals(r.visibleToModel()));
        int citedAndVisible = count(measured, r -> Boolean.TRUE.equals(r.visibleToModel()) && Boolean.TRUE.equals(r.citedExpected()));
        int figureQuestions = count(withRun, r -> r.figuresInReasoning() != null);
        int figuresInReasoning = count(withRun, r -> Boolean.TRUE.equals(r.figuresInReasoning()));
        int insufficient = count(withRun, r -> INSUFFICIENT_EVIDENCE.equals(r.status()));
        List<QuestionResult> answered = withRun.stream().filter(AnswerEvaluationService::answered).toList();
        List<QuestionResult> measuredAnswered = answered.stream().filter(QuestionResult::evidenceCaptured).toList();
        int retrievedAnswered = count(measuredAnswered, r -> Boolean.TRUE.equals(r.retrievedExpected()));
        int visibleAnswered = count(measuredAnswered, r -> Boolean.TRUE.equals(r.visibleToModel()));
        int citedAndVisibleAnswered = count(measuredAnswered, r -> Boolean.TRUE.equals(r.visibleToModel()) && Boolean.TRUE.equals(r.citedExpected()));
        int figureQuestionsAnswered = count(answered, r -> r.figuresInReasoning() != null);
        int figuresInReasoningAnswered = count(answered, r -> Boolean.TRUE.equals(r.figuresInReasoning()));
        int insufficientAnswered = count(answered, r -> INSUFFICIENT_EVIDENCE.equals(r.status()));
        Map<String, Integer> statuses = new TreeMap<>();
        Map<String, Integer> limitations = new TreeMap<>();
        for (QuestionResult result : withRun) {
            statuses.merge(String.valueOf(result.status()), 1, Integer::sum);
            for (String code : result.limitations()) limitations.merge(code.replaceFirst(":\\d+$", ""), 1, Integer::sum);
        }
        return new Aggregates(results.size(), withRun.size(), measured.size(), retrieved, share(retrieved, measured.size()), visible,
                share(visible, retrieved), citedAndVisible, share(citedAndVisible, visible), figureQuestions, figuresInReasoning,
                share(figuresInReasoning, figureQuestions), insufficient, share(insufficient, withRun.size()),
                statuses.getOrDefault(INVALID_CITATION, 0), statuses, limitations,
                withRun.stream().mapToLong(QuestionResult::observedTokens).sum(), withRun.stream().mapToLong(QuestionResult::modelCalls).sum(),
                results.stream().mapToLong(QuestionResult::elapsedMs).sum(), answered.size(), withRun.size() - answered.size(),
                measuredAnswered.size(), retrievedAnswered, share(retrievedAnswered, measuredAnswered.size()), visibleAnswered,
                share(visibleAnswered, retrievedAnswered), citedAndVisibleAnswered, share(citedAndVisibleAnswered, visibleAnswered),
                figureQuestionsAnswered, figuresInReasoningAnswered, share(figuresInReasoningAnswered, figureQuestionsAnswered),
                insufficientAnswered, share(insufficientAnswered, answered.size()));
    }

    private static int count(List<QuestionResult> results, java.util.function.Predicate<QuestionResult> test) {
        return (int) results.stream().filter(test).count();
    }

    private static BigDecimal share(int numerator, int denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), SCALE, RoundingMode.HALF_UP);
    }

    private Map<String, Object> runProperties(RetrievalEvaluationSet set, int pauseMs, List<String> filter, Integer limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("searchTopK", recommendationProperties.getSearchTopK());
        out.put("modelPassageChars", recommendationProperties.getModelPassageChars());
        out.put("criticRounds", recommendationProperties.getCriticRounds());
        out.put("maxOutputTokens", recommendationProperties.getMaxOutputTokens());
        out.put("maxObservedTokens", recommendationProperties.getMaxObservedTokens());
        out.put("maxModelCalls", recommendationProperties.getMaxModelCalls());
        out.put("trackRecordRuns", recommendationProperties.getTrackRecordRuns());
        out.put("prefetchFilings", recommendationProperties.isPrefetchFilings());
        out.put("rateLimitRetryMs", recommendationProperties.getRateLimitRetryMs());
        out.put("deadlineMs", recommendationProperties.getDeadlineMs());
        out.put("promptVersion", RecommendationService.PROMPT_VERSION);
        out.put("chatModel", recommendationProperties.getModel());
        out.put("activeProfiles", List.of(environment.getActiveProfiles()));
        out.put("set", properties.getSet());
        out.put("setVersion", set.version());
        out.put("setCreatedOn", set.createdOn().toString());
        out.put("storeVersions", new ArrayList<>(filings.findDistinctProcessingVersionsOfEmbeddedFilings()));
        out.put("chunkMaxChars", ingestionProperties.getChunkMaxChars());
        out.put("chunkOverlapChars", ingestionProperties.getChunkOverlapChars());
        out.put("hybridEnabled", retrievalProperties.isHybridEnabled());
        out.put("rerankingEnabled", retrievalProperties.isRerankingEnabled());
        out.put("candidateCount", retrievalProperties.getCandidateCount());
        out.put("pauseMs", pauseMs);
        out.put("questions", filter);
        out.put("limit", limit);
        return out;
    }
}

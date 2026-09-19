package project.stockrecommendationengine.recommendation;

import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import project.stockrecommendationengine.broker.BrokerReadService;
import project.stockrecommendationengine.outcome.CalibrationRepository;
import project.stockrecommendationengine.outcome.ConfidenceCalibrationService;
import project.stockrecommendationengine.outcome.OutcomeProperties;
import project.stockrecommendationengine.outcome.OutcomeRecord;
import project.stockrecommendationengine.outcome.OutcomeRepository;
import project.stockrecommendationengine.outcome.TrackRecord;
import project.stockrecommendationengine.outcome.TrackRecordService;
import project.stockrecommendationengine.quant.QuantAnalysisService;
import project.stockrecommendationengine.quant.QuantProperties;
import project.stockrecommendationengine.rag.dto.RetrievalResponse;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.freshness.FilingFreshness;
import project.stockrecommendationengine.rag.freshness.FilingFreshnessService;
import project.stockrecommendationengine.rag.freshness.FilingFreshnessService.EnsureOutcome;
import project.stockrecommendationengine.rag.ingestion.FilingIngestionProperties;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalService;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Evaluation runs are marked and kept out of product data. These run against the shared database inside a rolled-back
 * transaction; every row written here uses a ticker and a prompt version unique to the test instance, and request times
 * in 1990 so the oldest-first pending query reaches them before any pre-existing row.
 */
@SpringBootTest
@Transactional
class RunPurposeTests {
    private static final Instant LONG_AGO = Instant.parse("1990-01-01T00:00:00Z");
    @Autowired RecommendationRepository recommendations;
    @Autowired OutcomeRepository outcomes;
    @Autowired CalibrationRepository calibrations;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper applicationMapper;
    private final String ticker = "PU" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();

    @Test void anEvaluationRunIsStoredAndReadableByIdButAbsentFromTheListingTheTrackRecordAndPendingScoring() {
        String user = save(RunPurpose.USER, LONG_AGO, "BULLISH");
        String evaluation = save(RunPurpose.EVALUATION, LONG_AGO.plusSeconds(60), "BULLISH");

        assertThat(recommendations.findByRunId(evaluation).orElseThrow().purpose()).isEqualTo(RunPurpose.EVALUATION);
        assertThat(recommendations.findByRunId(user).orElseThrow().purpose()).isEqualTo(RunPurpose.USER);
        assertThat(recommendations.findByTicker(ticker, 200)).extracting(RecommendationRecord::runId).containsExactly(user);
        // The evaluation run is the newer of the two, so a limit of one would return it if it were listed at all.
        assertThat(recommendations.findByTicker(ticker, 1)).extracting(RecommendationRecord::runId).containsExactly(user);

        // Both rows are scorable and older than every other row, so both would lead the oldest-first pending list.
        assertThat(recommendations.findPendingEvaluation(3, 5)).extracting(RecommendationRecord::runId)
                .contains(user).doesNotContain(evaluation);
        assertThat(recommendations.findPendingEvaluation(3, 100_000)).extracting(RecommendationRecord::runId)
                .contains(user).doesNotContain(evaluation);

        // The track record given to the manager is built by the real service over the real repositories.
        TrackRecord record = new TrackRecordService(recommendations, outcomes, new OutcomeProperties()).trackRecord(ticker, 200);
        assertThat(record.runsConsidered()).isEqualTo(1);
        assertThat(record.runs()).extracting(TrackRecord.PriorRun::runId).containsExactly(user);
        assertThat(record.stats()).singleElement().satisfies(stats -> assertThat(stats.runs()).isEqualTo(1));
    }

    @Test void outcomesOfAnEvaluationRunNeverReachTheSummaryOrTheCalibrationSamples() {
        // An evaluation run should never get outcomes; if one did, the joins still leave it out. The assessment label is
        // unique to this test so the summary row counts only rows written here.
        String label = "T" + ticker;
        String user = save(RunPurpose.USER, LONG_AGO, label);
        String evaluation = save(RunPurpose.EVALUATION, LONG_AGO.plusSeconds(60), label);
        int horizon = 977;
        outcomes.upsert(outcome(user, horizon, "0.050000", true));
        outcomes.upsert(outcome(evaluation, horizon, "-0.500000", false));

        var row = outcomes.summary().stream().filter(s -> s.assessment().equals(label)).toList();
        assertThat(row).singleElement().satisfies(s -> {
            assertThat(s.horizonDays()).isEqualTo(horizon);
            assertThat(s.outcomes()).isEqualTo(1);
            assertThat(s.averageReturnPct()).isEqualByComparingTo("0.050000");
            assertThat(s.directionHitRate()).isEqualByComparingTo("1");
        });
        var samples = calibrations.samples(horizon).stream().filter(s -> s.promptVersion().equals(ticker)).toList();
        assertThat(samples).singleElement().satisfies(s -> assertThat(s.directionCorrect()).isTrue());
        // The outcome itself stays readable by run id; only the aggregates leave it out.
        assertThat(outcomes.findByRunId(evaluation)).hasSize(1);
    }

    @Test void migrationDefaultsEveryRowWrittenWithoutAPurposeToUserAndTheSchemaAllowsOnlyTheTwoValues() {
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '10'", Boolean.class)).isTrue();
        var column = jdbc.queryForMap("""
                SELECT is_nullable, column_default FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'recommendations' AND column_name = 'purpose'
                """);
        assertThat(column.get("is_nullable")).isEqualTo("NO");
        assertThat((String) column.get("column_default")).startsWith("'USER'");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendations WHERE purpose NOT IN ('USER', 'EVALUATION')", Long.class)).isZero();

        // A row written the way every row was written before V10 (no purpose column named) reads back as a user run.
        String legacy = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO recommendations (run_id, ticker, requested_at, question, status, assessment, prompt_version, model,
                    processing_version, response)
                VALUES (?, ?, now(), 'q', 'COMPLETE', 'NEUTRAL', 'p', 'm', 'v', '{}'::jsonb)
                """, legacy, ticker);
        assertThat(recommendations.findByRunId(legacy).orElseThrow().purpose()).isEqualTo(RunPurpose.USER);
        assertThat(recommendations.findByTicker(ticker, 10)).extracting(RecommendationRecord::runId).containsExactly(legacy);

        assertThatThrownBy(() -> jdbc.update("UPDATE recommendations SET purpose = 'OTHER' WHERE run_id = ?", legacy))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void theRequestHasNoPurposeFieldAndARequestBodyCarryingOneStillStoresAUserRun() throws Exception {
        assertThat(Arrays.stream(RecommendationRequest.class.getRecordComponents()))
                .as("no request component can carry a purpose")
                .noneMatch(c -> c.getType() == RunPurpose.class || c.getName().toLowerCase().contains("purpose"))
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("ticker", "question", "conid", "includePortfolio");

        var model = mock(ChatModel.class);
        var service = scriptedService(model);
        try {
            // The application's own mapper, so unknown-property handling is the running application's, not a test default.
            var mvc = MockMvcBuilders.standaloneSetup(new RecommendationController(service, recommendations))
                    .setMessageConverters(new JacksonJsonHttpMessageConverter(applicationMapper)).build();
            for (String extra : List.of("\"purpose\":\"EVALUATION\"", "\"runPurpose\":\"EVALUATION\"",
                    "\"purpose\":{\"name\":\"EVALUATION\"}")) {
                script(model);
                mvc.perform(post("/api/recommendations").param("purpose", "EVALUATION").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ticker\":\"" + ticker + "\",\"question\":\"Assess risks\",\"includePortfolio\":false," + extra + "}"))
                        // Unknown properties are ignored by the application's mapper; PARTIAL because the broker is off here.
                        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL"))
                        .andExpect(jsonPath("$.sources[0].chunkId").value(11))
                        .andExpect(jsonPath("$.purpose").doesNotExist());
            }
            assertThat(jdbc.queryForList("SELECT purpose FROM recommendations WHERE ticker = ?", String.class, ticker))
                    .containsExactly("USER", "USER", "USER");

            // The stored run shows its purpose by run id; an evaluation run is returned there and nowhere in the listing.
            String evaluation = save(RunPurpose.EVALUATION, Instant.now().plusSeconds(3600), "NEUTRAL");
            mvc.perform(get("/api/recommendations/" + evaluation)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.purpose").value("EVALUATION"));
            mvc.perform(get("/api/recommendations").param("ticker", ticker)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$[?(@.purpose != 'USER')]").isEmpty())
                    .andExpect(jsonPath("$[?(@.runId == '" + evaluation + "')]").isEmpty());
        } finally {
            service.close();
        }
    }

    private String save(RunPurpose purpose, Instant requestedAt, String assessment) {
        String runId = UUID.randomUUID().toString();
        // The prompt version carries this test's ticker so the calibration sample query can be filtered to rows written here.
        recommendations.save(new RecommendationRecord(runId, ticker, 265598L, requestedAt, requestedAt.plusSeconds(15), "q", "COMPLETE",
                assessment, new BigDecimal("110"), new BigDecimal("95"), new BigDecimal("0.5000"), new BigDecimal("100"),
                LocalDate.of(1989, 12, 29), "DELAYED", List.of(), List.of(), 0, 0, ticker, "m", null, "v",
                "{\"runId\":\"" + runId + "\"}", purpose));
        return runId;
    }

    private static OutcomeRecord outcome(String runId, int horizon, String ret, Boolean correct) {
        return new OutcomeRecord(runId, horizon, Instant.now(), LocalDate.of(1989, 12, 29), new BigDecimal("100"), LocalDate.of(1990, 1, 31),
                new BigDecimal("105"), new BigDecimal(ret), null, null, null, correct, "NONE", null, null,
                new BigDecimal("0.080000"), new BigDecimal("-0.030000"), horizon);
    }

    /** The real harness over a scripted chat model and scripted retrieval, writing to the real audit store. */
    private RecommendationService scriptedService(ChatModel model) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("model", model);
        var filings = mock(FilingRetrievalService.class);
        when(filings.retrieve(any())).thenReturn(new RetrievalResponse(ticker, "risks", "FILTERED_VECTOR", true, 5, 1,
                List.of(new RetrievedFilingChunk(11L, 1L, ticker, "0000000000", "accession", "10-K", LocalDate.of(2025, 10, 31),
                        LocalDate.of(2025, 9, 30), "ITEM_1A", "Risk factors", 0, "Material business risks.", "https://www.sec.gov/example", 0.8))));
        var freshness = mock(FilingFreshnessService.class);
        var fresh = new FilingFreshness(ticker, java.util.Map.of("10-K", LocalDate.of(2025, 10, 31)), LocalDate.of(2025, 10, 31),
                false, Instant.now(), false);
        when(freshness.ensure(any())).thenReturn(new EnsureOutcome("FRESH", null, fresh));
        when(freshness.assess(any())).thenReturn(fresh);
        var properties = new RecommendationProperties();
        properties.setModel("scripted-test-model");
        properties.setParallelSpecialists(false);
        properties.setCriticRounds(0);
        properties.setPrefetchFilings(false);
        return new RecommendationService(beans.getBeanProvider(ChatModel.class), filings, freshness,
                beans.getBeanProvider(BrokerReadService.class), beans.getBeanProvider(QuantAnalysisService.class),
                beans.getBeanProvider(QuantProperties.class), recommendations, beans.getBeanProvider(TrackRecordService.class),
                beans.getBeanProvider(ConfidenceCalibrationService.class), properties,
                Validation.buildDefaultValidatorFactory().getValidator(), new FilingIngestionProperties());
    }

    private static void script(ChatModel model) {
        var manager = new java.util.ArrayDeque<>(List.of(
                calls(new AssistantMessage.ToolCall("m1", "function", "researchFilings", "{}")),
                text("{\"assessment\":\"NEUTRAL\",\"reasoning\":\"The filing describes material business risks.\",\"citedChunkIds\":[11]}")));
        var rag = new java.util.ArrayDeque<>(List.of(
                calls(new AssistantMessage.ToolCall("r1", "function", "searchFilings", "{\"query\":\"risks\"}")),
                text("{\"summary\":\"Findings from the available evidence.\"}")));
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            String system = invocation.<Prompt>getArgument(0).getInstructions().get(0).getText();
            var next = (system.contains("You are the manager") ? manager : rag).pollFirst();
            if (next == null) throw new IllegalStateException("No scripted response");
            return next;
        });
    }
    private static ChatResponse calls(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
    }
    private static ChatResponse text(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
}

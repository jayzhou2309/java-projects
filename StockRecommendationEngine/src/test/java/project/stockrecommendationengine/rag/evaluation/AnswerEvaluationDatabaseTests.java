package project.stockrecommendationengine.rag.evaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.stockrecommendationengine.rag.evaluation.AnswerEvaluation.Aggregates;
import project.stockrecommendationengine.rag.evaluation.AnswerEvaluation.QuestionResult;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationQuestion.Kind;
import project.stockrecommendationengine.recommendation.RecommendationRepository;
import project.stockrecommendationengine.recommendation.RecommendationService;
import project.stockrecommendationengine.recommendation.RunPurpose;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static project.stockrecommendationengine.rag.evaluation.ScriptedAnswers.SECTION;
import static project.stockrecommendationengine.rag.evaluation.ScriptedAnswers.answer;
import static project.stockrecommendationengine.rag.evaluation.ScriptedAnswers.chunk;

/**
 * Answer-evaluation storage (V11) and one scripted pass against the shared database, inside a rolled-back transaction.
 * Rows written here use future timestamps so "latest" holds whatever snapshots already exist. The chat model is scripted.
 */
@SpringBootTest
@Transactional
class AnswerEvaluationDatabaseTests {
    @Autowired AnswerEvaluationRepository repository;
    @Autowired AnswerEvaluationService wired;
    @Autowired RecommendationRepository recommendations;
    @Autowired JdbcTemplate jdbc;

    @Test void theMigrationIsAppliedAndSnapshotsRoundTripWithTheNewestReturned() {
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '11'", Boolean.class)).isTrue();
        Instant base = Instant.parse("2099-01-01T00:00:00Z");
        var results = List.of(
                new QuestionResult("aapl-08", "AAPL", Kind.NARRATIVE, "00000000-0000-0000-0000-00000000000a", null, "PARTIAL", "NEUTRAL", true, 3, true,
                        List.of(5L), false, List.of(), true, 2, 1, List.of(5L, 6L), null, null, null, List.of("BROKER_DISABLED"), "ACCEPT", List.of("12"),
                        4, 6500, 31000, "Reasoning text."),
                new QuestionResult("nvda-04", "NVDA", Kind.FIGURE, null, "RECOMMENDATION_CAPACITY_REACHED", null, null, false, 0, null, null, null, null,
                        null, 0, 0, List.of(), null, null, null, List.of(), null, null, 0, 0, 3, null));
        var aggregates = new Aggregates(2, 1, 1, 1, new BigDecimal("1.000000"), 0, new BigDecimal("0.000000"), 0, null, 0, 0, null, 0,
                new BigDecimal("0.000000"), 0, Map.of("PARTIAL", 1), Map.of("BROKER_DISABLED", 1), 6500, 4, 31003);
        var partial = repository.save(new AnswerEvaluation(null, base, "v2", 3, 2, true, "RECOMMENDATION_CAPACITY_REACHED at nvda-04", aggregates,
                Map.of("searchTopK", 3, "chatModel", "scripted"), results, List.of("msft-01")));
        var complete = repository.save(new AnswerEvaluation(null, base.plusSeconds(60), "v2", 1, 1, false, null, aggregates, Map.of(), results.subList(0, 1), List.of()));

        assertThat(repository.findById(partial.id()).orElseThrow()).isEqualTo(partial);
        assertThat(repository.findById(complete.id()).orElseThrow()).isEqualTo(complete);
        assertThat(repository.latest().orElseThrow().id()).isEqualTo(complete.id());
        assertThat(repository.findById(-1)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT results -> 'questions' -> 0 ->> 'runId' FROM answer_evaluations WHERE id = ?", String.class, partial.id()))
                .as("each stored result carries its run id").isEqualTo("00000000-0000-0000-0000-00000000000a");

        // A partial row always says why, and a complete one never carries a reason.
        assertThatThrownBy(() -> jdbc.update("UPDATE answer_evaluations SET partial_reason = NULL WHERE id = ?", partial.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void aScriptedPassStoresExactlyOneSnapshotAndItsRunsAreEvaluationRunsKeptOutOfTheListing() {
        var scripted = new ScriptedAnswers();
        scripted.question("t-one", Kind.NARRATIVE, "First stored question?", List.of("the first expected phrase"),
                        List.of(chunk(9_000_001L, SECTION, "Here is the first expected phrase.")), answer("NEUTRAL", "Fine.", "[9000001]"))
                .question("t-two", Kind.NARRATIVE, "Second stored question?", List.of("the second expected phrase"),
                        List.of(chunk(9_000_002L, SECTION, "Nothing relevant.")), answer("INSUFFICIENT_EVIDENCE", "Not stated.", "[]"));
        RecommendationService service = scripted.recommendationService(recommendations);
        try {
            long snapshotsBefore = jdbc.queryForObject("SELECT count(*) FROM answer_evaluations", Long.class);
            AnswerEvaluation evaluation = scripted.runner(service, repository).evaluate(null, null);

            assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_evaluations", Long.class)).isEqualTo(snapshotsBefore + 1);
            assertThat(repository.findById(evaluation.id()).orElseThrow()).as("the snapshot returned is the snapshot stored").isEqualTo(evaluation);
            assertThat(evaluation.attempted()).isEqualTo(2);
            assertThat(evaluation.partial()).isFalse();
            for (QuestionResult result : evaluation.results()) {
                var stored = recommendations.findByRunId(result.runId()).orElseThrow();
                assertThat(stored.purpose()).as("run of %s", result.id()).isEqualTo(RunPurpose.EVALUATION);
                assertThat(stored.status()).isEqualTo(result.status());
            }
            assertThat(jdbc.queryForList("SELECT purpose FROM recommendations WHERE ticker = 'TSTA' AND run_id IN (?, ?)", String.class,
                    evaluation.results().get(0).runId(), evaluation.results().get(1).runId())).containsExactly("EVALUATION", "EVALUATION");
            assertThat(recommendations.findByTicker("TSTA", 200)).extracting(r -> r.runId())
                    .doesNotContain(evaluation.results().get(0).runId(), evaluation.results().get(1).runId());
        } finally {
            service.close();
        }
    }

    @Test void inTheDefaultContextRecommendationsAreDisabledAndTheWiredRunnerRefusesWithoutStoringAnything() {
        long before = jdbc.queryForObject("SELECT count(*) FROM answer_evaluations", Long.class);
        assertThatThrownBy(() -> wired.evaluate("aapl-08", null)).isInstanceOfSatisfying(AnswerEvaluationRefusedException.class, refused -> {
            assertThat(refused.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(refused.getMessage()).contains("RECOMMENDATION_ENABLED");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_evaluations", Long.class)).isEqualTo(before);
    }
}

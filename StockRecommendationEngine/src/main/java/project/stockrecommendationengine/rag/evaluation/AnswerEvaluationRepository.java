package project.stockrecommendationengine.rag.evaluation;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import project.stockrecommendationengine.rag.evaluation.AnswerEvaluation.Aggregates;
import project.stockrecommendationengine.rag.evaluation.AnswerEvaluation.QuestionResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Stored answer-evaluation snapshots ({@code answer_evaluations}, V11). Appended, never updated. */
@Repository
@RequiredArgsConstructor
public class AnswerEvaluationRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    /** The results column: the attempted questions' measures in pass order and the selected questions never attempted. */
    record StoredResults(List<QuestionResult> questions, List<String> notAttempted) { }

    /**
     * A JSON escape of U+0000: the six characters backslash, u, 0000 behind an even number of backslashes (an odd run
     * would make the backslash itself the escaped character and the rest plain text).
     */
    private static final Pattern ESCAPED_NUL = Pattern.compile("(?<!\\\\)((?:\\\\\\\\)*)\\\\u0000");

    /**
     * PostgreSQL's jsonb refuses U+0000, and one such character in model text would lose a whole pass. The runner already
     * removes it from run text (so the snapshot it returns equals the stored one); this removes any that is left, from
     * whatever string, before the write.
     */
    String storable(Object value) {
        String text = json.writeValueAsString(value);
        return text.contains("\\u0000") ? ESCAPED_NUL.matcher(text).replaceAll("$1") : text;
    }

    public AnswerEvaluation save(AnswerEvaluation e) {
        Long id = jdbc.queryForObject("""
                INSERT INTO answer_evaluations (evaluated_at, set_version, question_count, attempted, partial, partial_reason,
                    aggregates, properties, results)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb)) RETURNING id
                """, Long.class, Timestamp.from(e.evaluatedAt()), e.setVersion(), e.questionCount(), e.attempted(), e.partial(),
                AnswerEvaluationService.clean(e.partialReason()), storable(e.aggregates()), storable(e.properties()),
                storable(new StoredResults(e.results(), e.notAttempted())));
        return e.withId(id);
    }

    /** The newest snapshot by evaluation time. */
    public Optional<AnswerEvaluation> latest() {
        return jdbc.query("SELECT * FROM answer_evaluations ORDER BY evaluated_at DESC, id DESC LIMIT 1", mapper).stream().findFirst();
    }

    public Optional<AnswerEvaluation> findById(long id) {
        return jdbc.query("SELECT * FROM answer_evaluations WHERE id = ?", mapper, id).stream().findFirst();
    }

    private final RowMapper<AnswerEvaluation> mapper = (rs, i) -> {
        StoredResults stored = json.readValue(rs.getString("results"), StoredResults.class);
        Map<String, Object> properties = json.readValue(rs.getString("properties"), new TypeReference<Map<String, Object>>() { });
        return new AnswerEvaluation(rs.getLong("id"), rs.getTimestamp("evaluated_at").toInstant(), rs.getString("set_version"),
                rs.getInt("question_count"), rs.getInt("attempted"), rs.getBoolean("partial"), rs.getString("partial_reason"),
                json.readValue(rs.getString("aggregates"), Aggregates.class), properties,
                stored.questions() == null ? List.of() : stored.questions(),
                stored.notAttempted() == null ? List.of() : stored.notAttempted());
    };
}

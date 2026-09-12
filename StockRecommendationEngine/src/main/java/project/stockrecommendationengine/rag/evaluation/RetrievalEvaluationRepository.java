package project.stockrecommendationengine.rag.evaluation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.Miss;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Stored retrieval evaluation snapshots. Appended, never updated. */
@Repository
@RequiredArgsConstructor
public class RetrievalEvaluationRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    /** The results column: per-question ranks, per-ticker hit@5, and the misses, as one JSON document. */
    record StoredResults(List<QuestionResult> questions, Map<String, BigDecimal> tickerHitAt5, List<Miss> misses) { }

    public RetrievalEvaluation save(RetrievalEvaluation e) {
        Long id = jdbc.queryForObject("""
                INSERT INTO retrieval_evaluations (evaluated_at, set_version, question_count, hit_at_1, hit_at_3, hit_at_5, mrr,
                    window_size, retrieval_strategy, properties, results)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb)) RETURNING id
                """, Long.class, Timestamp.from(e.evaluatedAt()), e.setVersion(), e.questionCount(), e.hitAt1(), e.hitAt3(), e.hitAt5(),
                e.mrr(), e.window(), e.retrievalStrategy(), json.writeValueAsString(e.properties()),
                json.writeValueAsString(new StoredResults(e.results(), e.tickerHitAt5(), e.misses())));
        return e.withId(id);
    }

    /** The newest snapshot by evaluation time. */
    public Optional<RetrievalEvaluation> latest() {
        return jdbc.query("SELECT * FROM retrieval_evaluations ORDER BY evaluated_at DESC, id DESC LIMIT 1", mapper).stream().findFirst();
    }

    public Optional<RetrievalEvaluation> findById(long id) {
        return jdbc.query("SELECT * FROM retrieval_evaluations WHERE id = ?", mapper, id).stream().findFirst();
    }

    private final RowMapper<RetrievalEvaluation> mapper = (rs, i) -> {
        StoredResults stored = json.readValue(rs.getString("results"), StoredResults.class);
        Map<String, Object> properties = json.readValue(rs.getString("properties"), new TypeReference<Map<String, Object>>() { });
        return new RetrievalEvaluation(rs.getLong("id"), rs.getTimestamp("evaluated_at").toInstant(), rs.getString("set_version"),
                rs.getInt("question_count"), rs.getBigDecimal("hit_at_1"), rs.getBigDecimal("hit_at_3"), rs.getBigDecimal("hit_at_5"),
                rs.getBigDecimal("mrr"), rs.getInt("window_size"), rs.getString("retrieval_strategy"), properties,
                stored.questions() == null ? List.of() : stored.questions(), stored.tickerHitAt5() == null ? Map.of() : stored.tickerHitAt5(),
                stored.misses() == null ? List.of() : stored.misses());
    };
}

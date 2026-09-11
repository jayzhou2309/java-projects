package project.stockrecommendationengine.recommendation;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Audit store for recommendation runs. Rows are written once and never updated by the recommendation loop. */
@Repository
@RequiredArgsConstructor
public class RecommendationRepository {
    private final JdbcTemplate jdbc;

    public void save(RecommendationRecord record) {
        jdbc.update("""
                INSERT INTO recommendations (run_id, ticker, conid, requested_at, completed_at, question, status, assessment,
                    take_profit, stop_loss, confidence, last_close, bars_as_of, quote_availability, cited_chunk_ids, limitations,
                    model_calls, observed_tokens, prompt_version, model, quant_version, processing_version, response)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, record.runId(), record.ticker(), record.conid(), Timestamp.from(record.requestedAt()),
                Timestamp.from(record.completedAt()), record.question(), record.status(), record.assessment(),
                record.takeProfit(), record.stopLoss(), record.confidence(), record.lastClose(), record.barsAsOf(),
                record.quoteAvailability(), record.citedChunkIds().toArray(new Long[0]), record.limitations().toArray(new String[0]),
                record.modelCalls(), record.observedTokens(), record.promptVersion(), record.model(), record.quantVersion(),
                record.processingVersion(), record.responseJson());
    }

    public Optional<RecommendationRecord> findByRunId(String runId) {
        var rows = jdbc.query("SELECT * FROM recommendations WHERE run_id = ?", MAPPER, runId);
        return rows.stream().findFirst();
    }

    /** Newest first. */
    public List<RecommendationRecord> findByTicker(String ticker, int limit) {
        return jdbc.query("SELECT * FROM recommendations WHERE ticker = ? ORDER BY requested_at DESC LIMIT ?", MAPPER, ticker, limit);
    }

    private static final RowMapper<RecommendationRecord> MAPPER = (rs, i) -> {
        var chunkIds = rs.getArray("cited_chunk_ids");
        var limitations = rs.getArray("limitations");
        var asOf = rs.getDate("bars_as_of");
        var conid = rs.getObject("conid", Long.class);
        return new RecommendationRecord(rs.getString("run_id"), rs.getString("ticker"), conid,
                rs.getTimestamp("requested_at").toInstant(), rs.getTimestamp("completed_at").toInstant(),
                rs.getString("question"), rs.getString("status"), rs.getString("assessment"),
                rs.getBigDecimal("take_profit"), rs.getBigDecimal("stop_loss"), rs.getBigDecimal("confidence"),
                rs.getBigDecimal("last_close"), asOf == null ? null : asOf.toLocalDate(), rs.getString("quote_availability"),
                chunkIds == null ? List.of() : Arrays.asList((Long[]) chunkIds.getArray()),
                limitations == null ? List.of() : Arrays.asList((String[]) limitations.getArray()),
                rs.getInt("model_calls"), rs.getInt("observed_tokens"), rs.getString("prompt_version"), rs.getString("model"),
                rs.getString("quant_version"), rs.getString("processing_version"), rs.getString("response"));
    };
}

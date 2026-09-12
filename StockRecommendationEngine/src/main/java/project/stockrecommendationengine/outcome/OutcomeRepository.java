package project.stockrecommendationengine.outcome;

import java.sql.Timestamp;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class OutcomeRepository {
    private final JdbcTemplate jdbc;

    /** Insert or replace the outcome for a run and horizon; later evaluations with more bars do not change a finished horizon. */
    public void upsert(OutcomeRecord o) {
        jdbc.update("""
                INSERT INTO recommendation_outcomes (run_id, horizon_days, evaluated_at, as_of, entry_price, exit_date, exit_price,
                    return_pct, benchmark_conid, benchmark_return_pct, excess_return_pct, direction_correct, first_touch, touch_date,
                    days_to_touch, max_favorable_pct, max_adverse_pct, bars_used)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, horizon_days) DO UPDATE SET evaluated_at = EXCLUDED.evaluated_at, exit_date = EXCLUDED.exit_date,
                    exit_price = EXCLUDED.exit_price, return_pct = EXCLUDED.return_pct, benchmark_conid = EXCLUDED.benchmark_conid,
                    benchmark_return_pct = EXCLUDED.benchmark_return_pct, excess_return_pct = EXCLUDED.excess_return_pct,
                    direction_correct = EXCLUDED.direction_correct, first_touch = EXCLUDED.first_touch, touch_date = EXCLUDED.touch_date,
                    days_to_touch = EXCLUDED.days_to_touch, max_favorable_pct = EXCLUDED.max_favorable_pct,
                    max_adverse_pct = EXCLUDED.max_adverse_pct, bars_used = EXCLUDED.bars_used
                """, o.runId(), o.horizonDays(), Timestamp.from(o.evaluatedAt()), o.asOf(), o.entryPrice(), o.exitDate(), o.exitPrice(),
                o.returnPct(), o.benchmarkConid(), o.benchmarkReturnPct(), o.excessReturnPct(), o.directionCorrect(), o.firstTouch(),
                o.touchDate(), o.daysToTouch(), o.maxFavorablePct(), o.maxAdversePct(), o.barsUsed());
    }

    public List<OutcomeRecord> findByRunId(String runId) {
        return jdbc.query("SELECT * FROM recommendation_outcomes WHERE run_id = ? ORDER BY horizon_days", MAPPER, runId);
    }

    public List<Integer> evaluatedHorizons(String runId) {
        return jdbc.queryForList("SELECT horizon_days FROM recommendation_outcomes WHERE run_id = ? ORDER BY horizon_days", Integer.class, runId);
    }

    /** Per assessment and horizon over every stored outcome joined to its recommendation. */
    public List<OutcomeSummary> summary() {
        return jdbc.query("""
                SELECT r.assessment, o.horizon_days, count(*) AS outcomes,
                       avg(o.return_pct) AS avg_return, avg(o.excess_return_pct) AS avg_excess,
                       avg(CASE WHEN o.direction_correct IS NULL THEN NULL WHEN o.direction_correct THEN 1.0 ELSE 0.0 END) AS hit_rate,
                       avg(CASE WHEN o.first_touch = 'NO_LEVELS' THEN NULL WHEN o.first_touch = 'TAKE_PROFIT' THEN 1.0 ELSE 0.0 END) AS tp_rate,
                       avg(CASE WHEN o.first_touch = 'NO_LEVELS' THEN NULL WHEN o.first_touch = 'STOP_LOSS' THEN 1.0 ELSE 0.0 END) AS sl_rate
                FROM recommendation_outcomes o JOIN recommendations r ON r.run_id = o.run_id
                GROUP BY r.assessment, o.horizon_days ORDER BY r.assessment, o.horizon_days
                """, (rs, i) -> new OutcomeSummary(rs.getString("assessment"), rs.getInt("horizon_days"), rs.getLong("outcomes"),
                scaled(rs.getBigDecimal("avg_return")), scaled(rs.getBigDecimal("avg_excess")), scaled(rs.getBigDecimal("hit_rate")),
                scaled(rs.getBigDecimal("tp_rate")), scaled(rs.getBigDecimal("sl_rate"))));
    }

    private static java.math.BigDecimal scaled(java.math.BigDecimal value) {
        return value == null ? null : value.setScale(6, java.math.RoundingMode.HALF_UP);
    }

    private static final RowMapper<OutcomeRecord> MAPPER = (rs, i) -> {
        var touch = rs.getDate("touch_date");
        var benchmark = rs.getObject("benchmark_conid", Long.class);
        var days = rs.getObject("days_to_touch", Integer.class);
        var correct = rs.getObject("direction_correct", Boolean.class);
        return new OutcomeRecord(rs.getString("run_id"), rs.getInt("horizon_days"), rs.getTimestamp("evaluated_at").toInstant(),
                rs.getDate("as_of").toLocalDate(), rs.getBigDecimal("entry_price"), rs.getDate("exit_date").toLocalDate(),
                rs.getBigDecimal("exit_price"), rs.getBigDecimal("return_pct"), benchmark, rs.getBigDecimal("benchmark_return_pct"),
                rs.getBigDecimal("excess_return_pct"), correct, rs.getString("first_touch"), touch == null ? null : touch.toLocalDate(),
                days, rs.getBigDecimal("max_favorable_pct"), rs.getBigDecimal("max_adverse_pct"), rs.getInt("bars_used"));
    };
}

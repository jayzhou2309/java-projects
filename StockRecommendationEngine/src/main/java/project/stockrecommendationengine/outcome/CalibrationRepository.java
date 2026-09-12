package project.stockrecommendationengine.outcome;

import java.sql.Timestamp;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import project.stockrecommendationengine.outcome.ConfidenceCalibration.Bin;
import project.stockrecommendationengine.outcome.ConfidenceCalibration.Sample;
import tools.jackson.databind.json.JsonMapper;

/** Calibration samples from the outcome tables and stored calibration snapshots. Snapshots are appended, never updated. */
@Repository
@RequiredArgsConstructor
public class CalibrationRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Every stored run with a raw confidence and a scored direction at the horizon. The stored confidence is the
     * input-coverage composite, never a calibrated value, so calibrating on it is not circular.
     */
    public List<Sample> samples(int horizonDays) {
        return jdbc.query("""
                SELECT r.confidence, o.direction_correct, r.assessment, r.prompt_version
                FROM recommendation_outcomes o JOIN recommendations r ON r.run_id = o.run_id
                WHERE o.horizon_days = ? AND o.direction_correct IS NOT NULL AND r.confidence IS NOT NULL
                ORDER BY r.requested_at, r.run_id
                """, (rs, i) -> new Sample(rs.getBigDecimal("confidence"), rs.getBoolean("direction_correct"),
                rs.getString("assessment"), rs.getString("prompt_version")), horizonDays);
    }

    public ConfidenceCalibration save(ConfidenceCalibration c) {
        Long id = jdbc.queryForObject("""
                INSERT INTO confidence_calibrations (computed_at, horizon_days, status, samples, min_samples, prior_weight, base_rate,
                    expected_calibration_error, brier_score, bins, assessments, prompt_versions)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb)) RETURNING id
                """, Long.class, Timestamp.from(c.computedAt()), c.horizonDays(), c.status(), c.samples(), c.minSamples(), c.priorWeight(),
                c.baseRate(), c.expectedCalibrationError(), c.brierScore(), json.writeValueAsString(c.bins()),
                json.writeValueAsString(c.assessments()), json.writeValueAsString(c.promptVersions()));
        return new ConfidenceCalibration(id, c.computedAt(), c.horizonDays(), c.status(), c.samples(), c.minSamples(), c.priorWeight(),
                c.baseRate(), c.expectedCalibrationError(), c.brierScore(), c.bins(), c.assessments(), c.promptVersions(), c.caveat());
    }

    /** The newest snapshot for the horizon, whatever its status. */
    public Optional<ConfidenceCalibration> latest(int horizonDays) {
        return jdbc.query("SELECT * FROM confidence_calibrations WHERE horizon_days = ? ORDER BY computed_at DESC, id DESC LIMIT 1",
                mapper, horizonDays).stream().findFirst();
    }

    private final RowMapper<ConfidenceCalibration> mapper = (rs, i) -> new ConfidenceCalibration(rs.getLong("id"),
            rs.getTimestamp("computed_at").toInstant(), rs.getInt("horizon_days"), rs.getString("status"), rs.getInt("samples"),
            rs.getInt("min_samples"), rs.getInt("prior_weight"), rs.getBigDecimal("base_rate"), rs.getBigDecimal("expected_calibration_error"),
            rs.getBigDecimal("brier_score"), List.of(json.readValue(rs.getString("bins"), Bin[].class)),
            counts(rs.getString("assessments")), counts(rs.getString("prompt_versions")), ConfidenceCalibration.CAVEAT);

    private Map<String, Integer> counts(String text) {
        var result = new LinkedHashMap<String, Integer>();
        json.readTree(text).properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asInt()));
        return Map.copyOf(result);
    }
}

package project.stockrecommendationengine.recommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class RecommendationRepositoryTests {
    @Autowired RecommendationRepository repository;
    @Autowired JdbcTemplate jdbc;
    private final String ticker = "RC" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();

    @Test void storesAndReadsBackEveryFieldNewestFirst() {
        var first = record(UUID.randomUUID().toString(), Instant.parse("2026-09-11T07:00:00Z"), "NEUTRAL", null);
        var second = record(UUID.randomUUID().toString(), Instant.parse("2026-09-11T08:00:00Z"), "BULLISH", new BigDecimal("232.54"));
        repository.save(first);
        repository.save(second);
        var stored = repository.findByRunId(second.runId()).orElseThrow();
        assertThat(stored).usingRecursiveComparison().ignoringFields("completedAt", "responseJson")
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class).isEqualTo(second);
        assertThat(stored.responseJson()).contains("\"runId\"");
        assertThat(jdbc.queryForObject("SELECT response->>'status' FROM recommendations WHERE run_id = ?", String.class, second.runId()))
                .isEqualTo("COMPLETE");
        assertThat(repository.findByTicker(ticker, 10)).extracting(RecommendationRecord::runId).containsExactly(second.runId(), first.runId());
        assertThat(repository.findByTicker(ticker, 1)).hasSize(1);
        assertThat(repository.findByRunId(UUID.randomUUID().toString())).isEmpty();
    }

    @Test void runIdsAreUnique() {
        var record = record(UUID.randomUUID().toString(), Instant.now(), "NEUTRAL", null);
        repository.save(record);
        assertThatThrownBy(() -> repository.save(record)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void confidenceIsBoundedBySchema() {
        var invalid = new RecommendationRecord(UUID.randomUUID().toString(), ticker, null, Instant.now(), Instant.now(), "q",
                "COMPLETE", "NEUTRAL", null, null, new BigDecimal("1.5"), null, null, null, List.of(), List.of(), 0, 0,
                "p", "m", null, "v", "{}");
        assertThatThrownBy(() -> repository.save(invalid)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private RecommendationRecord record(String runId, Instant requestedAt, String assessment, BigDecimal takeProfit) {
        return new RecommendationRecord(runId, ticker, 4815747L, requestedAt, requestedAt.plusSeconds(15),
                "Assess the main risks.", "COMPLETE", assessment, takeProfit, takeProfit == null ? null : new BigDecimal("211.27"),
                new BigDecimal("0.5000"), new BigDecimal("218.36"), LocalDate.parse("2026-09-10"), "UNAVAILABLE",
                List.of(11L, 12L), List.of("CONFIDENCE_UNCALIBRATED", "NO_VERIFIED_CURRENT_QUOTE"), 5, 11880,
                "manager-specialists-v3-prefetch", "gpt-4.1", "atr14-tp2.0-sl1.0", "sections-v2-context-v2",
                "{\"runId\":\"" + runId + "\",\"status\":\"COMPLETE\"}");
    }
}

package project.stockrecommendationengine.quant;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.stockrecommendationengine.broker.BrokerData.DailyBar;
import project.stockrecommendationengine.broker.BrokerData.PriceHistory;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class PriceBarRepositoryTests {
    @Autowired PriceBarRepository repository;
    @Autowired JdbcTemplate jdbc;
    private final long conid = 900_000_000L + (System.nanoTime() % 1_000_000);

    @Test void upsertReplacesOverlappingDatesAndReturnsNewestBarsOldestFirst() {
        assertThat(repository.findLatest(conid, 10)).isEmpty();
        Instant first = Instant.parse("2026-09-10T00:00:00Z"), second = Instant.parse("2026-09-11T00:00:00Z");
        repository.upsert(new PriceHistory(conid, "TEST", "USD", first,
                List.of(bar("2026-09-08", 100), bar("2026-09-09", 101), bar("2026-09-10", 102))));
        repository.upsert(new PriceHistory(conid, "TEST", "USD", second,
                List.of(bar("2026-09-10", 103), bar("2026-09-11", 104))));
        var stored = repository.findLatest(conid, 3).orElseThrow();
        assertThat(stored.symbol()).isEqualTo("TEST");
        assertThat(stored.observedAt()).isEqualTo(second);
        assertThat(stored.bars()).extracting(b -> b.date().toString()).containsExactly("2026-09-09", "2026-09-10", "2026-09-11");
        assertThat(stored.bars().get(1).close()).isEqualByComparingTo("103");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM price_bars WHERE conid = ?", Integer.class, conid)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT source FROM price_bars WHERE conid = ? AND bar_date = '2026-09-08'", String.class, conid))
                .isEqualTo(PriceBarRepository.SOURCE_TWS_DAILY);
    }

    @Test void schemaRejectsInvertedRanges() {
        assertThatThrownBy(() -> repository.upsert(new PriceHistory(conid, "TEST", "USD", Instant.now(), List.of(
                new DailyBar(LocalDate.parse("2026-09-10"), new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("101"),
                        new BigDecimal("100"), null))))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private static DailyBar bar(String date, double close) {
        return new DailyBar(LocalDate.parse(date), BigDecimal.valueOf(close), BigDecimal.valueOf(close + 1),
                BigDecimal.valueOf(close - 1), BigDecimal.valueOf(close), BigDecimal.valueOf(1000));
    }
}

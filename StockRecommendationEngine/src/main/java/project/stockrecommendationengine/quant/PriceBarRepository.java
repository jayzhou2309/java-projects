package project.stockrecommendationengine.quant;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import project.stockrecommendationengine.broker.BrokerData.DailyBar;
import project.stockrecommendationengine.broker.BrokerData.PriceHistory;

/** Stores broker daily bars so repeated analyses do not repeat historical-data requests. */
@Repository
@RequiredArgsConstructor
public class PriceBarRepository {
    public static final String SOURCE_TWS_DAILY = "TWS_DAILY_TRADES";
    private final JdbcTemplate jdbc;

    /** The newest {@code limit} stored bars for a contract, oldest first, with the most recent retrieval time. */
    public Optional<PriceHistory> findLatest(long conid, int limit) {
        var rows = jdbc.query("""
                SELECT symbol, currency, bar_date, open, high, low, close, volume, observed_at
                FROM price_bars WHERE conid = ? ORDER BY bar_date DESC LIMIT ?
                """, (rs, i) -> new Row(rs.getString("symbol"), rs.getString("currency"),
                new DailyBar(rs.getDate("bar_date").toLocalDate(), rs.getBigDecimal("open"), rs.getBigDecimal("high"),
                        rs.getBigDecimal("low"), rs.getBigDecimal("close"), rs.getBigDecimal("volume")),
                rs.getTimestamp("observed_at").toInstant()), conid, limit);
        if (rows.isEmpty()) return Optional.empty();
        Collections.reverse(rows);
        Instant observed = rows.stream().map(Row::observedAt).max(Instant::compareTo).orElseThrow();
        var newest = rows.get(rows.size() - 1);
        return Optional.of(new PriceHistory(conid, newest.symbol(), newest.currency(), observed,
                rows.stream().map(Row::bar).toList()));
    }

    /** Insert or update each bar by contract and date. Existing dates absent from the new series are retained. */
    @Transactional
    public void upsert(PriceHistory history) {
        var args = new ArrayList<Object[]>();
        for (DailyBar bar : history.bars()) {
            args.add(new Object[]{history.conid(), bar.date(), history.symbol(), history.currency(), bar.open(),
                    bar.high(), bar.low(), bar.close(), bar.volume(), SOURCE_TWS_DAILY, Timestamp.from(history.observedAt())});
        }
        jdbc.batchUpdate("""
                INSERT INTO price_bars (conid, bar_date, symbol, currency, open, high, low, close, volume, source, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (conid, bar_date) DO UPDATE SET symbol = EXCLUDED.symbol, currency = EXCLUDED.currency,
                    open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low, close = EXCLUDED.close,
                    volume = EXCLUDED.volume, source = EXCLUDED.source, observed_at = EXCLUDED.observed_at
                """, args);
    }

    private record Row(String symbol, String currency, DailyBar bar, Instant observedAt) { }
}

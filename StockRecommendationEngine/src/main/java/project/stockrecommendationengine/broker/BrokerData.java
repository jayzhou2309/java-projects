package project.stockrecommendationengine.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class BrokerData {
    private BrokerData() { }

    public record SessionStatus(boolean connected, boolean authenticated, Boolean established,
                                boolean competing) {
        public boolean ready() {
            return connected && authenticated && !Boolean.FALSE.equals(established) && !competing;
        }
    }
    public record Account(String id, String currency) { }
    public record Instrument(long conid, String symbol, String name, String exchange,
                             String currency, String securityType) { }
    public record Position(long conid, String symbol, String currency, String securityType,
                           BigDecimal quantity, BigDecimal marketPrice, BigDecimal marketValue) { }
    // observedAt is retrieval time, not a claim that IBKR's cached positions were updated then.
    public record Portfolio(Instant observedAt, List<Position> positions) { }
    public record Quote(long conid, BigDecimal last, BigDecimal bid, BigDecimal ask,
                        Instant updatedAt, Instant observedAt, String availability,
                        String rawAvailability) {
        public boolean hasPrice() { return last != null || bid != null || ask != null; }
    }
    /** One regular-trading-hours daily bar. Volume is null when the provider reports it as unavailable. */
    public record DailyBar(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low,
                           BigDecimal close, BigDecimal volume) { }
    /** Ascending daily bars for one contract. observedAt is retrieval time, not a market timestamp. */
    public record PriceHistory(long conid, String symbol, String currency, Instant observedAt,
                               List<DailyBar> bars) { }
}

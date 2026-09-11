package project.stockrecommendationengine.broker;

import java.util.List;
import project.stockrecommendationengine.broker.BrokerData.*;

/** Read operations only. Account selection belongs to application configuration, never tool arguments. */
public interface BrokerReadService {
    SessionStatus sessionStatus();
    List<Account> getAccounts();
    Portfolio getPositions();
    List<Instrument> searchInstruments(String symbol);
    Quote getQuote(long conid);
    /** Up to the requested number of regular-hours daily bars (IBKR counts trading days for a day-unit duration), oldest first. */
    PriceHistory getDailyBars(long conid, int days);
}

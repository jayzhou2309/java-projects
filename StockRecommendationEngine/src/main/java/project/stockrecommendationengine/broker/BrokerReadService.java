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
}

package project.stockrecommendationengine.broker.ibkr;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import project.stockrecommendationengine.broker.BrokerReadService;
import project.stockrecommendationengine.broker.BrokerData.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "broker.ibkr.enabled", havingValue = "true")
public class IbkrBrokerAdapter implements BrokerReadService {
    private final TwsClient client;

    @Override public SessionStatus sessionStatus() { return client.sessionStatus(); }
    @Override public List<Account> getAccounts() { return client.accounts(); }
    @Override public Portfolio getPositions() { return client.positions(); }
    @Override public List<Instrument> searchInstruments(String symbol) { return client.search(symbol); }
    @Override public Quote getQuote(long conid) { return client.quote(conid); }
    @Override public PriceHistory getDailyBars(long conid, int days) { return client.bars(conid, days); }
}

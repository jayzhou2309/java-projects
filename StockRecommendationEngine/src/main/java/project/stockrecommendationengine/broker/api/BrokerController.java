package project.stockrecommendationengine.broker.api;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import project.stockrecommendationengine.broker.BrokerReadService;
import project.stockrecommendationengine.broker.BrokerData.*;

@RestController
@RequestMapping("/api/broker")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "broker.ibkr.enabled", havingValue = "true")
public class BrokerController {
    private final BrokerReadService broker;
    @GetMapping("/session") public SessionStatus session() { return broker.sessionStatus(); }
    @GetMapping("/accounts") public List<Account> accounts() { return broker.getAccounts(); }
    @GetMapping("/positions") public Portfolio positions() { return broker.getPositions(); }
    @GetMapping("/instruments") public List<Instrument> instruments(@RequestParam String symbol) { return broker.searchInstruments(symbol); }
    @GetMapping("/quotes/{conid}") public Quote quote(@PathVariable long conid) { return broker.getQuote(conid); }
    @GetMapping("/history/{conid}") public PriceHistory history(@PathVariable long conid,
            @RequestParam(defaultValue = "120") int days) { return broker.getDailyBars(conid, days); }
}

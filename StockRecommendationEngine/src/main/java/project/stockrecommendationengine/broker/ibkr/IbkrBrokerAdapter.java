package project.stockrecommendationengine.broker.ibkr;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import project.stockrecommendationengine.broker.BrokerReadService;
import project.stockrecommendationengine.broker.BrokerException;
import project.stockrecommendationengine.broker.BrokerData.*;
import static project.stockrecommendationengine.broker.BrokerException.Code.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "broker.ibkr.enabled", havingValue = "true")
public class IbkrBrokerAdapter implements BrokerReadService {
    private final IbkrApiClient client;
    private final IbkrSessionManager sessions;
    private final IbkrProperties properties;

    @Override public SessionStatus sessionStatus() { return sessions.status(); }

    @Override public List<Account> getAccounts() {
        var result = new ArrayList<Account>();
        for (var node : array(client.get("/portfolio/accounts"))) {
            String id = text(node, "accountId");
            if (id == null) id = text(node, "id");
            // This single-owner integration exposes only the configured account.
            if (properties.getAccountId().equals(id)) result.add(new Account(id, text(node, "currency")));
        }
        if (result.isEmpty()) throw new BrokerException(ACCOUNT_NOT_ALLOWED);
        return List.copyOf(result);
    }

    @Override public Portfolio getPositions() {
        getAccounts(); // IBKR prerequisite and account authorization check.
        var positions = new ArrayList<Position>();
        var seen = new HashSet<Long>();
        for (int page = 0; page < properties.getMaxPositionPages(); page++) {
            var nodes = array(client.get("/portfolio/" + properties.getAccountId() + "/positions/" + page));
            if (nodes.isEmpty()) return new Portfolio(Instant.now(), List.copyOf(positions));
            for (var node : nodes) {
                long conid = positiveId(node, "conid");
                if (!seen.add(conid)) throw new BrokerException(INVALID_RESPONSE);
                var quantity = decimal(node, "position");
                if (quantity == null) throw new BrokerException(INVALID_RESPONSE);
                positions.add(new Position(conid, text(node, "contractDesc"), text(node, "currency"),
                        text(node, "assetClass"), quantity, decimal(node, "mktPrice"), decimal(node, "mktValue")));
            }
            if (nodes.size() < 100) return new Portfolio(Instant.now(), List.copyOf(positions));
        }
        // Never present a truncated portfolio as complete.
        throw new BrokerException(POSITION_LIMIT);
    }

    @Override public List<Instrument> searchInstruments(String symbol) {
        if (symbol == null || !symbol.trim().matches("[A-Za-z0-9.-]{1,16}")) throw new BrokerException(INVALID_ARGUMENT);
        sessions.requireBrokerageSession();
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        var result = new ArrayList<Instrument>();
        for (var node : array(client.get("/iserver/secdef/search?symbol=" + normalized + "&secType=STK"))) {
            if (!normalized.equalsIgnoreCase(text(node, "symbol"))) continue;
            boolean stock = false;
            for (var section : node.path("sections")) if ("STK".equals(text(section, "secType"))) stock = true;
            if (!stock) continue;
            result.add(new Instrument(positiveId(node, "conid"), normalized, text(node, "companyName"),
                    text(node, "description"), text(node, "currency"), "STK"));
            if (result.size() > 20) throw new BrokerException(INVALID_RESPONSE);
        }
        return List.copyOf(result);
    }

    @Override public Quote getQuote(long conid) {
        if (conid <= 0) throw new BrokerException(INVALID_ARGUMENT);
        sessions.requireBrokerageSession();
        Quote quote = new Quote(conid, null, null, null, null, Instant.now(), "UNAVAILABLE", null);
        for (int attempt = 0; attempt < properties.getSnapshotAttempts(); attempt++) {
            var nodes = array(client.get("/iserver/marketdata/snapshot?conids=" + conid + "&fields=31,84,86,6509"));
            for (var node : nodes) {
                if (positiveId(node, "conid") != conid) throw new BrokerException(INVALID_RESPONSE);
                String raw = text(node, "6509");
                var last = price(node, "31");
                var bid = price(node, "84");
                var ask = price(node, "86");
                long updated = node.path("_updated").asLong(0);
                String availability = raw == null ? "UNKNOWN" : raw.startsWith("R") ? "REALTIME"
                        : raw.startsWith("D") ? "DELAYED" : raw.startsWith("Z") ? "FROZEN" : "UNKNOWN";
                quote = new Quote(conid, last, bid, ask, updated > 0 ? Instant.ofEpochMilli(updated) : null,
                        Instant.now(), last == null && bid == null && ask == null ? "UNAVAILABLE" : availability, raw);
            }
            if (quote.hasPrice()) return quote;
            if (attempt + 1 < properties.getSnapshotAttempts()) IbkrApiClient.pause(500);
        }
        return quote;
    }

    private static JsonNode array(JsonNode node) {
        if (!node.isArray()) throw new BrokerException(INVALID_RESPONSE);
        return node;
    }
    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
    private static long positiveId(JsonNode node, String field) {
        long id = node.path(field).asLong(0);
        if (id <= 0) throw new BrokerException(INVALID_RESPONSE);
        return id;
    }
    private static BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank() || value.equals("N/A") || value.equals("--")) return null;
        try { return new BigDecimal(value.replace(",", "")); }
        catch (NumberFormatException ex) { return null; }
    }
    private static BigDecimal price(JsonNode node, String field) {
        var value = decimal(node, field);
        return value == null || value.signum() <= 0 ? null : value;
    }
}

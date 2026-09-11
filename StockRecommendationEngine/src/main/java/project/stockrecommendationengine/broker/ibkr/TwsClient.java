package project.stockrecommendationengine.broker.ibkr;

import com.ib.client.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import project.stockrecommendationengine.broker.BrokerException;
import project.stockrecommendationengine.broker.BrokerData.*;
import static project.stockrecommendationengine.broker.BrokerException.Code.*;

/** Bounded synchronous reads over correlated TWS callbacks; connects lazily, never logs in or places orders. */
@Slf4j
public final class TwsClient extends DefaultEWrapper implements AutoCloseable {
    /** IBKR accepts day-unit durations up to one year; longer history needs a different unit and pacing plan. */
    static final int MAX_HISTORY_DAYS = 365;
    private final TwsTransport transport;
    private final IbkrProperties properties;
    // TWS market-data mode is connection-wide; serialize requests and bound callers waiting for the socket.
    private final ReentrantLock operation = new ReentrantLock();
    private final AtomicInteger ids = new AtomicInteger(1);
    private final Map<Integer, Pending<?>> pending = new ConcurrentHashMap<>();
    private volatile CompletableFuture<Void> handshake = new CompletableFuture<>();
    private volatile CompletableFuture<Set<String>> managed = new CompletableFuture<>();
    private volatile CompletableFuture<Void> heartbeat;
    private volatile Set<String> allowedAccounts = Set.of();
    private volatile boolean serverReady;
    private volatile boolean competing;
    private volatile boolean closed;
    private long deadline;
    private long nextSearch;

    TwsClient(TwsTransport transport, IbkrProperties properties) {
        this.transport = transport;
        this.properties = properties;
    }

    public SessionStatus sessionStatus() {
        try {
            return execute(() -> {
                heartbeat = new CompletableFuture<>();
                try {
                    transport.requestTime();
                    await(heartbeat);
                    return new SessionStatus(true, true, true, competing);
                } finally { heartbeat = null; }
            });
        } catch (BrokerException ex) {
            if (ex.code() == UNAVAILABLE) serverReady = false;
            if (ex.code() == INTERRUPTED || ex.code() == RATE_LIMITED) throw ex;
            return new SessionStatus(transport.connected(), false, false, competing);
        }
    }

    public List<Account> accounts() {
        return execute(() -> {
            authorize();
            // managedAccounts contains IDs only; do not invent base currency.
            return List.of(new Account(properties.getAccountId(), null));
        });
    }

    public List<Instrument> search(String symbol) {
        if (symbol == null || !symbol.trim().matches("[A-Za-z0-9.-]{1,16}")) throw new BrokerException(INVALID_ARGUMENT);
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        return execute(() -> {
            long wait = nextSearch - System.nanoTime();
            if (wait > 0) sleep(wait);
            nextSearch = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            int id = ids.getAndIncrement();
            var request = new Search(normalized);
            pending.put(id, request);
            try {
                transport.search(id, normalized);
                return await(request.result);
            } finally { pending.remove(id); }
        });
    }

    public Portfolio positions() {
        return execute(() -> {
            authorize();
            int id = ids.getAndIncrement();
            var request = new Positions();
            pending.put(id, request);
            try {
                transport.positions(id, properties.getAccountId());
                var result = await(request.result);
                return new Portfolio(Instant.now(), result);
            } finally {
                pending.remove(id);
                transport.cancelPositions(id);
            }
        });
    }

    public Quote quote(long conid) {
        if (conid <= 0 || conid > Integer.MAX_VALUE) throw new BrokerException(INVALID_ARGUMENT);
        return execute(() -> {
            Contract contract = resolveContract(conid);
            int id = ids.getAndIncrement();
            var request = new Prices(conid);
            pending.put(id, request);
            try {
                log.info("TWS quote request={} conid={} exchange={} requestedType={}", id, conid,
                        contract.exchange(), properties.getMarketDataType());
                transport.quote(id, contract, properties.getMarketDataType());
                long end = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getQuoteWaitMs()));
                synchronized (request) {
                    while (!request.complete() && !request.result.isDone() && System.nanoTime() < end) {
                        TimeUnit.NANOSECONDS.timedWait(request, Math.max(1, end - System.nanoTime()));
                    }
                    if (request.result.isCompletedExceptionally()) await(request.result);
                    Quote quote = request.snapshot();
                    log.info("TWS quote request={} availability={} rawType={} hasPrice={} "
                                    + "hasLast={} hasBid={} hasAsk={} updatedAt={} notSubscribed={}",
                            id, quote.availability(), quote.rawAvailability(), quote.hasPrice(),
                            quote.last() != null, quote.bid() != null, quote.ask() != null,
                            quote.updatedAt(), request.notSubscribed);
                    return quote;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new BrokerException(INTERRUPTED);
            } finally {
                pending.remove(id);
                transport.cancelQuote(id);
            }
        });
    }

    public PriceHistory bars(long conid, int days) {
        if (conid <= 0 || conid > Integer.MAX_VALUE || days < 1 || days > MAX_HISTORY_DAYS) throw new BrokerException(INVALID_ARGUMENT);
        return execute(() -> {
            Contract contract = resolveContract(conid);
            int id = ids.getAndIncrement();
            var request = new History(days);
            pending.put(id, request);
            try {
                log.info("TWS history request={} conid={} days={}", id, conid, days);
                transport.history(id, contract, days);
                var bars = await(request.result);
                log.info("TWS history request={} bars={} first={} last={}", id, bars.size(),
                        bars.isEmpty() ? null : bars.get(0).date(), bars.isEmpty() ? null : bars.get(bars.size() - 1).date());
                return new PriceHistory(conid, contract.symbol(), contract.currency(), Instant.now(), bars);
            } finally {
                pending.remove(id);
                // A request that reached its end marker needs no cancellation; incomplete ones must not keep streaming.
                if (!request.result.isDone() || request.result.isCompletedExceptionally()) transport.cancelHistory(id);
            }
        });
    }

    private Contract resolveContract(long conid) {
        int detailId = ids.getAndIncrement();
        var details = new Details(conid);
        pending.put(detailId, details);
        try {
            transport.contract(detailId, conid);
            return await(details.result);
        } finally {
            // Contract details is a finite request. Late callbacks are ignored after timeout.
            pending.remove(detailId);
        }
    }

    private <T> T execute(Supplier<T> action) {
        boolean locked = false;
        try {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getTimeoutMs());
            locked = operation.tryLock(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!locked) throw new BrokerException(RATE_LIMITED);
            deadline = end;
            if (closed) throw new BrokerException(UNAVAILABLE);
            if (!transport.connected() || !serverReady || !handshake.isDone()) {
                transport.close();
                handshake = new CompletableFuture<>();
                managed = new CompletableFuture<>();
                allowedAccounts = Set.of();
                serverReady = false;
                competing = false;
                transport.connect(this, properties);
                try { await(handshake); await(managed); }
                catch (BrokerException ex) { transport.close(); throw ex; }
            }
            if (!serverReady) throw new BrokerException(SESSION_NOT_READY);
            if (System.nanoTime() >= deadline) throw new BrokerException(UNAVAILABLE);
            return action.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrokerException(INTERRUPTED);
        } catch (BrokerException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BrokerException(UNAVAILABLE);
        } finally { if (locked) operation.unlock(); }
    }

    private void authorize() {
        await(managed);
        if (!allowedAccounts.contains(properties.getAccountId())) throw new BrokerException(ACCOUNT_NOT_ALLOWED);
    }
    private <T> T await(CompletableFuture<T> future) {
        try { return future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new BrokerException(INTERRUPTED); }
        catch (TimeoutException ex) { throw new BrokerException(UNAVAILABLE); }
        catch (ExecutionException ex) {
            if (ex.getCause() instanceof BrokerException broker) throw broker;
            throw new BrokerException(UNAVAILABLE);
        }
    }
    private void sleep(long nanos) {
        if (System.nanoTime() + nanos >= deadline) throw new BrokerException(RATE_LIMITED);
        try { TimeUnit.NANOSECONDS.sleep(nanos); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new BrokerException(INTERRUPTED); }
    }

    @Override public void nextValidId(int ignored) { serverReady = true; handshake.complete(null); }
    @Override public void managedAccounts(String accounts) {
        Set<String> selected = new HashSet<>();
        if (accounts != null) for (String account : accounts.split(",")) selected.add(account.trim());
        allowedAccounts = Set.copyOf(selected);
        managed.complete(allowedAccounts);
    }
    @Override public void currentTime(long time) {
        var future = heartbeat;
        if (future != null && time > 0) future.complete(null);
    }
    @Override public void symbolSamples(int id, ContractDescription[] descriptions) {
        if (!(pending.get(id) instanceof Search request)) return;
        var result = new LinkedHashMap<Long, Instrument>();
        if (descriptions == null) { request.fail(INVALID_RESPONSE); return; }
        for (var description : descriptions) {
            Contract c = description.contract();
            if (!request.symbol.equalsIgnoreCase(c.symbol()) || c.secType() != Types.SecType.STK) continue;
            if (c.conid() <= 0) { request.fail(INVALID_RESPONSE); return; }
            result.put((long)c.conid(), new Instrument(c.conid(), c.symbol(), c.description(), c.primaryExch(), c.currency(), "STK"));
            if (result.size() > 20) { request.fail(INVALID_RESPONSE); return; }
        }
        request.result.complete(List.copyOf(result.values()));
    }
    @Override public void contractDetails(int id, ContractDetails details) {
        if (!(pending.get(id) instanceof Details request)) return;
        Contract c = details.contract();
        if (c.conid() != request.conid || c.secType() != Types.SecType.STK) { request.fail(INVALID_RESPONSE); return; }
        if (request.contract != null) { request.fail(INVALID_RESPONSE); return; }
        request.contract = c;
    }
    @Override public void contractDetailsEnd(int id) {
        if (!(pending.get(id) instanceof Details request)) return;
        if (request.contract == null) request.fail(INVALID_RESPONSE);
        else request.result.complete(request.contract);
    }
    @Override public void positionMulti(int id, String account, String modelCode, Contract c, Decimal quantity, double avgCost) {
        if (!(pending.get(id) instanceof Positions request)) return;
        if (!properties.getAccountId().equals(account)) { request.fail(ACCOUNT_NOT_ALLOWED); return; }
        if (c.conid() <= 0 || !Decimal.isValid(quantity)) { request.fail(INVALID_RESPONSE); return; }
        // Position callbacks may update the same contract before the end marker.
        request.values.put((long)c.conid(), new Position(c.conid(), c.symbol(), c.currency(), c.getSecType(),
                quantity.value(), null, null));
        if (request.values.size() > properties.getMaxPositions()) request.fail(POSITION_LIMIT);
    }
    @Override public void positionMultiEnd(int id) {
        if (pending.get(id) instanceof Positions request) request.result.complete(List.copyOf(request.values.values()));
    }
    @Override public void historicalData(int id, Bar bar) {
        if (!(pending.get(id) instanceof History request)) return;
        LocalDate date = barDate(bar.time());
        BigDecimal open = price(bar.open()), high = price(bar.high()), low = price(bar.low()), close = price(bar.close());
        if (date == null || open == null || high == null || low == null || close == null || high.compareTo(low) < 0) {
            request.fail(INVALID_RESPONSE);
            return;
        }
        BigDecimal volume = Decimal.isValid(bar.volume()) && bar.volume().value().signum() >= 0 ? bar.volume().value() : null;
        request.values.put(date, new DailyBar(date, open, high, low, close, volume));
        // The day-unit duration bounds the bar count; anything larger is not the requested daily series.
        if (request.values.size() > request.days) request.fail(INVALID_RESPONSE);
    }
    @Override public void historicalDataEnd(int id, String start, String end) {
        if (pending.get(id) instanceof History request) request.result.complete(List.copyOf(request.values.values()));
    }
    private static LocalDate barDate(String time) {
        if (time == null) return null;
        String digits = time.trim();
        if (digits.length() < 8) return null;
        try { return LocalDate.parse(digits.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE); }
        catch (RuntimeException ex) { return null; }
    }
    private static BigDecimal price(double value) {
        return Double.isFinite(value) && value > 0 && value != Double.MAX_VALUE ? BigDecimal.valueOf(value) : null;
    }
    @Override public void marketDataType(int id, int type) {
        if (pending.get(id) instanceof Prices request) synchronized (request) {
            request.type = type;
            request.notifyAll();
        }
    }
    @Override public void tickPrice(int id, int field, double value, TickAttrib attributes) {
        if (!(pending.get(id) instanceof Prices request)) return;
        synchronized (request) {
            BigDecimal price = Double.isFinite(value) && value > 0 && value != Double.MAX_VALUE ? BigDecimal.valueOf(value) : null;
            switch (field) {
                case 1, 66 -> request.bid = price;
                case 2, 67 -> request.ask = price;
                case 4, 68 -> request.last = price;
                default -> { return; }
            }
            request.notifyAll();
        }
    }
    @Override public void tickString(int id, int field, String value) {
        if (!(pending.get(id) instanceof Prices request) || (field != 45 && field != 88)) return;
        synchronized (request) {
            try {
                long seconds = Long.parseLong(value);
                if (seconds > 0) request.updatedAt = Instant.ofEpochSecond(seconds);
            } catch (RuntimeException ignored) { /* Missing timestamp cannot establish freshness. */ }
            request.notifyAll();
        }
    }
    @Override public void error(int id, long time, int code, String message, String advanced) {
        // Never retain provider messages: they can contain account or connection details.
        if (code < 2100 || code > 2199) log.info("TWS callback request={} code={}", id, code);
        if (code == 1100 || code == 1300 || code == 502 || code == 504 || code == 326) {
            serverReady = false;
            failAll(SESSION_NOT_READY);
            return;
        }
        if (code == 1101 || code == 1102) { serverReady = true; return; }
        if (code == 10197) competing = true;
        Pending<?> request = pending.get(id);
        if (request == null) return;
        // IBKR reserves 2100-2199 for warnings (farm status, time-zone notes); a request-scoped one is not a failure.
        if (code >= 2100 && code <= 2199) { log.info("TWS warning request={} code={}", id, code); return; }
        if (code == 10167) return;
        if (request instanceof Prices prices && (code == 354 || code == 10089)) {
            log.warn("TWS market-data subscription rejected request={} conid={} code={} "
                            + "requestedType={} actualType={}",
                    id, prices.conid, code, properties.getMarketDataType(), prices.type);
            synchronized (prices) { prices.notSubscribed = true; prices.result.complete(null); prices.notifyAll(); }
            return;
        }
        request.fail(code == 100 || code == 420 ? RATE_LIMITED : code == 10197 ? SESSION_NOT_READY : UNAVAILABLE);
    }
    @Override public void error(Exception ex) { serverReady = false; failAll(UNAVAILABLE); }
    @Override public void error(String message) { serverReady = false; failAll(UNAVAILABLE); }
    @Override public void connectionClosed() { serverReady = false; failAll(SESSION_NOT_READY); }
    private void failAll(BrokerException.Code code) {
        handshake.completeExceptionally(new BrokerException(code));
        managed.completeExceptionally(new BrokerException(code));
        var future = heartbeat;
        if (future != null) future.completeExceptionally(new BrokerException(code));
        pending.values().forEach(request -> request.fail(code));
    }
    @Override public void close() {
        closed = true;
        serverReady = false;
        failAll(UNAVAILABLE);
        transport.close();
    }

    private static class Pending<T> {
        final CompletableFuture<T> result = new CompletableFuture<>();
        synchronized void fail(BrokerException.Code code) {
            result.completeExceptionally(new BrokerException(code));
            notifyAll();
        }
    }
    private static final class Search extends Pending<List<Instrument>> {
        final String symbol;
        Search(String symbol) { this.symbol = symbol; }
    }
    private static final class Details extends Pending<Contract> {
        final long conid;
        Contract contract;
        Details(long conid) { this.conid = conid; }
    }
    private static final class Positions extends Pending<List<Position>> {
        final Map<Long, Position> values = new LinkedHashMap<>();
    }
    private static final class History extends Pending<List<DailyBar>> {
        final int days;
        final Map<LocalDate, DailyBar> values = new TreeMap<>();
        History(int days) { this.days = days; }
    }
    private static final class Prices extends Pending<Void> {
        final long conid;
        BigDecimal last, bid, ask;
        Instant updatedAt;
        int type;
        boolean notSubscribed;
        Prices(long conid) { this.conid = conid; }
        boolean complete() { return type >= 1 && type <= 4 && last != null && bid != null && ask != null; }
        Quote snapshot() {
            String availability = notSubscribed ? "NOT_SUBSCRIBED" : switch (type) {
                case 1 -> "REALTIME";
                case 2 -> "FROZEN";
                case 3 -> "DELAYED";
                case 4 -> "DELAYED_FROZEN";
                default -> "UNAVAILABLE";
            };
            return new Quote(conid, last, bid, ask, updatedAt, Instant.now(), availability,
                    type == 0 ? null : Integer.toString(type));
        }
    }
}

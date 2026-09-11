package project.stockrecommendationengine.broker.ibkr;

import com.ib.client.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import project.stockrecommendationengine.broker.BrokerException;
import static org.assertj.core.api.Assertions.*;

class IbkrBrokerAdapterTests {
    private final IbkrProperties properties = new IbkrProperties();
    private final FakeTransport transport = new FakeTransport();
    private TwsClient client;
    private IbkrBrokerAdapter broker;

    @BeforeEach void setup() {
        properties.setAccountId("DU_TEST");
        properties.setTimeoutMs(250);
        properties.setQuoteWaitMs(20); // Test duration bypasses production minimum.
        client = new TwsClient(transport, properties);
        broker = new IbkrBrokerAdapter(client);
    }
    @AfterEach void cleanup() { client.close(); }

    @Test void connectsLazilyAndChecksHeartbeat() {
        assertThat(transport.connects).isZero();
        assertThat(broker.sessionStatus().ready()).isTrue();
        assertThat(transport.connects).isEqualTo(1);
        assertThat(broker.sessionStatus().ready()).isTrue();
        assertThat(transport.connects).isEqualTo(1);
        assertThat(transport.timeRequests).isEqualTo(2);
    }
    @Test void authorizesOnlyConfiguredAccount() {
        assertThat(broker.getAccounts()).hasSize(1);
        assertThat(broker.getAccounts().get(0).id()).isEqualTo("DU_TEST");
        properties.setAccountId("DU_MISSING");
        assertCode("ACCOUNT_NOT_ALLOWED", () -> broker.getPositions());
        assertThat(transport.positionRequests).isZero();
    }
    @Test void accountRemovalInvalidatesCachedAuthorization() {
        assertThat(broker.getAccounts()).hasSize(1);
        transport.callback.managedAccounts("DU_OTHER");
        assertCode("ACCOUNT_NOT_ALLOWED", () -> broker.getAccounts());
    }
    @Test void positionsWaitForEndAndFilterConfiguredAccount() {
        transport.positionAction = id -> {
            transport.callback.positionMulti(id, "DU_TEST", "", stock(1), Decimal.get(2.5), 12);
            transport.callback.positionMulti(id, "DU_TEST", "", stock(1), Decimal.get(3), 12);
            transport.callback.positionMultiEnd(id);
        };
        var portfolio = broker.getPositions();
        assertThat(portfolio.positions()).hasSize(1);
        assertThat(portfolio.positions().get(0).quantity()).isEqualByComparingTo("3");
        assertThat(portfolio.positions().get(0).marketPrice()).isNull();
        assertThat(portfolio.positions().get(0).marketValue()).isNull();
        assertThat(transport.positionsAccount).isEqualTo("DU_TEST");
        assertThat(transport.positionsCancelled).isEqualTo(1);
    }
    @Test void rejectsUnexpectedAccountAndCancelsSubscription() {
        transport.positionAction = id -> transport.callback.positionMulti(id, "DU_OTHER", "", stock(1), Decimal.ONE, 1);
        assertCode("ACCOUNT_NOT_ALLOWED", () -> broker.getPositions());
        assertThat(transport.positionsCancelled).isEqualTo(1);
    }
    @Test void positionLimitDoesNotReturnTruncatedPortfolio() {
        properties.setMaxPositions(1);
        transport.positionAction = id -> {
            transport.callback.positionMulti(id, "DU_TEST", "", stock(1), Decimal.ONE, 1);
            transport.callback.positionMulti(id, "DU_TEST", "", stock(2), Decimal.ONE, 1);
            transport.callback.positionMultiEnd(id);
        };
        assertCode("POSITION_LIMIT", () -> broker.getPositions());
        assertThat(transport.positionsCancelled).isEqualTo(1);
    }
    @Test void incompletePositionsTimeOutAndCancel() {
        transport.positionAction = id -> {};
        assertCode("UNAVAILABLE", () -> broker.getPositions());
        assertThat(transport.positionsCancelled).isEqualTo(1);
    }
    @Test void filtersSymbolMatchesAndPreservesAmbiguousStocks() {
        transport.searchAction = id -> {
            var other = stock(3); other.symbol("AAPLX");
            var option = stock(4); option.secType("OPT");
            transport.callback.symbolSamples(id, new ContractDescription[]{
                    description(stock(1)), description(stock(2)), description(other), description(option)});
        };
        assertThat(broker.searchInstruments("aapl")).extracting(i -> i.conid()).containsExactly(1L, 2L);
        assertThat(transport.searchSymbol).isEqualTo("AAPL");
    }
    @Test void invalidInputsNeverConnect() {
        assertCode("INVALID_ARGUMENT", () -> broker.searchInstruments("AAPL&x"));
        assertCode("INVALID_ARGUMENT", () -> broker.getQuote(0));
        assertCode("INVALID_ARGUMENT", () -> broker.getQuote((long)Integer.MAX_VALUE + 1));
        assertThat(transport.connects).isZero();
    }
    @ParameterizedTest @CsvSource({"1,REALTIME", "2,FROZEN", "3,DELAYED", "4,DELAYED_FROZEN"})
    void mapsActualCallbackTypeAndRetainsPriceTimestamp(int type, String expected) {
        transport.quoteAction = id -> {
            transport.callback.marketDataType(id, type);
            transport.callback.tickString(id, 88, "1750000000");
            transport.callback.tickPrice(id, 66, 100, new TickAttrib());
            transport.callback.tickPrice(id, 67, 101, new TickAttrib());
            transport.callback.tickPrice(id, 68, 100.5, new TickAttrib());
        };
        var quote = broker.getQuote(265598);
        assertThat(quote.availability()).isEqualTo(expected);
        assertThat(quote.rawAvailability()).isEqualTo(Integer.toString(type));
        assertThat(quote.last()).isEqualByComparingTo("100.5");
        assertThat(quote.updatedAt().getEpochSecond()).isEqualTo(1750000000);
        assertThat(transport.requestedDataType).isEqualTo(4);
        assertThat(transport.quotesCancelled).isEqualTo(1);
    }
    @Test void quotesAreRequestedOnThePrimaryExchangeNotSmart() {
        transport.quoteAction = id -> transport.callback.marketDataType(id, 3);
        broker.getQuote(265598);
        assertThat(transport.quoteContract.exchange()).isEqualTo("NASDAQ");
        assertThat(transport.quoteContract.primaryExch()).isEqualTo("NASDAQ");
        assertThat(transport.quoteContract.conid()).isEqualTo(265598);
        assertThat(transport.quoteContract.symbol()).isEqualTo("AAPL");
    }
    @Test void closedMarketDelayedCloseCountsAsAPrice() {
        // Outside regular hours TWS sends delayed bid/ask as -1, delayed last as 0, and the real price in tick 75.
        transport.quoteAction = id -> {
            transport.callback.marketDataType(id, 3);
            transport.callback.tickPrice(id, 66, -1, new TickAttrib());
            transport.callback.tickPrice(id, 67, -1, new TickAttrib());
            transport.callback.tickPrice(id, 68, 0, new TickAttrib());
            transport.callback.tickPrice(id, 75, 326.57, new TickAttrib());
        };
        var quote = broker.getQuote(265598);
        assertThat(quote.availability()).isEqualTo("DELAYED");
        assertThat(quote.hasPrice()).isTrue();
        assertThat(quote.close()).isEqualByComparingTo("326.57");
        assertThat(quote.last()).isNull();
        assertThat(quote.bid()).isNull();
        assertThat(quote.updatedAt()).isNull();
    }
    @Test void typeWithoutPricesIsStillDelayedFrozen() {
        transport.quoteAction = id -> transport.callback.marketDataType(id, 4);
        var quote = broker.getQuote(265598);
        assertThat(quote.availability()).isEqualTo("DELAYED_FROZEN");
        assertThat(quote.hasPrice()).isFalse();
        assertThat(quote.updatedAt()).isNull();
        assertThat(transport.quotesCancelled).isEqualTo(1);
    }
    @Test void requestedTypeIsNotProofOfActualDataType() {
        transport.quoteAction = id -> transport.callback.tickPrice(id, 68, 100, new TickAttrib());
        var quote = broker.getQuote(265598);
        assertThat(quote.last()).isEqualByComparingTo("100");
        assertThat(quote.availability()).isEqualTo("UNAVAILABLE");
        assertThat(quote.rawAvailability()).isNull();
        assertThat(quote.updatedAt()).isNull();
    }
    @Test void rejectsInvalidPricesAndIgnoresUnrelatedRequestIds() {
        transport.quoteAction = id -> {
            transport.callback.marketDataType(id, 3);
            transport.callback.tickPrice(id, 66, -1, new TickAttrib());
            transport.callback.tickPrice(id, 67, Double.NaN, new TickAttrib());
            transport.callback.tickPrice(id, 68, Double.MAX_VALUE, new TickAttrib());
            transport.callback.tickPrice(id + 1, 68, 100, new TickAttrib());
        };
        assertThat(broker.getQuote(265598).hasPrice()).isFalse();
    }
    @Test void quoteStateDoesNotLeakBetweenRequests() {
        transport.quoteAction = id -> {
            transport.callback.marketDataType(id, 3);
            transport.callback.tickPrice(id, 68, 100, new TickAttrib());
        };
        assertThat(broker.getQuote(265598).hasPrice()).isTrue();
        int oldId = transport.quoteId;
        transport.quoteAction = id -> transport.callback.tickPrice(oldId, 68, 999, new TickAttrib());
        assertThat(broker.getQuote(265598).hasPrice()).isFalse();
    }
    @Test void subscriptionErrorDoesNotInventPriceAndCancellationAlwaysRuns() {
        transport.quoteAction = id -> transport.callback.error(id, 0, 354, "private account data", "");
        var quote = broker.getQuote(265598);
        assertThat(quote.availability()).isEqualTo("NOT_SUBSCRIBED");
        assertThat(quote.hasPrice()).isFalse();
        assertThat(transport.quotesCancelled).isEqualTo(1);
    }
    @Test void pacingAndProviderErrorsAreSanitized() {
        transport.quoteAction = id -> transport.callback.error(id, 0, 420, "secret", "");
        assertCode("RATE_LIMITED", () -> broker.getQuote(265598));
        assertThat(transport.quotesCancelled).isEqualTo(1);
    }
    @Test void wrongContractIsRejectedBeforeSubscribing() {
        transport.detailConid = 5;
        assertCode("INVALID_RESPONSE", () -> broker.getQuote(265598));
        assertThat(transport.quoteId).isZero();
    }
    @Test void connectionLossFailsPendingRequestAndNextReadReconnects() {
        transport.quoteAction = id -> transport.callback.connectionClosed();
        assertCode("SESSION_NOT_READY", () -> broker.getQuote(265598));
        transport.quoteAction = id -> {};
        assertThat(broker.sessionStatus().ready()).isTrue();
        assertThat(transport.connects).isEqualTo(2);
    }
    @Test void missingHeartbeatReportsUnreadyAndNextCallReconnects() {
        transport.sendHeartbeat = false;
        assertThat(broker.sessionStatus().ready()).isFalse();
        transport.sendHeartbeat = true;
        assertThat(broker.sessionStatus().ready()).isTrue();
        assertThat(transport.connects).isEqualTo(2);
    }
    @Test void shutdownDoesNotReconnect() {
        assertThat(broker.sessionStatus().ready()).isTrue();
        client.close();
        assertCode("UNAVAILABLE", () -> broker.getAccounts());
        assertThat(transport.connects).isEqualTo(1);
    }
    @Test void handshakeTimeoutClosesSocket() {
        transport.sendHandshake = false;
        assertThat(broker.sessionStatus().ready()).isFalse();
        assertThat(transport.connected()).isFalse();
    }
    @Test void interruptionCancelsQuote() throws Exception {
        properties.setTimeoutMs(5000);
        properties.setQuoteWaitMs(3000);
        var subscribed = new CountDownLatch(1);
        transport.quoteAction = id -> subscribed.countDown();
        var outcome = new CompletableFuture<Throwable>();
        var thread = new Thread(() -> {
            try { broker.getQuote(265598); outcome.complete(null); }
            catch (Throwable ex) { outcome.complete(ex); }
        });
        thread.start();
        assertThat(subscribed.await(1, TimeUnit.SECONDS)).isTrue();
        thread.interrupt();
        assertThat(outcome.get(1, TimeUnit.SECONDS)).isInstanceOf(BrokerException.class).hasMessage("INTERRUPTED");
        thread.join(1000);
        assertThat(transport.quotesCancelled).isEqualTo(1);
    }
    @Test void socketBoundaryContainsNoOrderMethods() {
        assertThat(TwsTransport.class.getDeclaredMethods()).extracting(m -> m.getName())
                .containsExactlyInAnyOrder("connect", "connected", "requestTime", "search", "contract", "positions",
                        "cancelPositions", "quote", "cancelQuote", "history", "cancelHistory", "close");
    }
    @Test void dailyBarsArriveSortedWithContractMetadataAndNoCancellationAfterEnd() {
        transport.historyAction = id -> {
            transport.callback.historicalData(id, bar("20260910", 101, 105, 100, 104, 5000));
            transport.callback.historicalData(id, bar("20260909 US/Eastern", 100, 103, 99, 101, 4000));
            transport.callback.historicalData(id, bar("20260910", 101, 106, 100, 105, 5500));
            transport.callback.historicalDataEnd(id, "", "");
        };
        var history = broker.getDailyBars(265598, 30);
        assertThat(history.conid()).isEqualTo(265598);
        assertThat(history.symbol()).isEqualTo("AAPL");
        assertThat(history.currency()).isEqualTo("USD");
        assertThat(history.observedAt()).isNotNull();
        assertThat(history.bars()).extracting(b -> b.date().toString()).containsExactly("2026-09-09", "2026-09-10");
        assertThat(history.bars().get(1).close()).isEqualByComparingTo("105");
        assertThat(history.bars().get(1).volume()).isEqualByComparingTo("5500");
        assertThat(transport.historyDays).isEqualTo(30);
        assertThat(transport.historyCancelled).isZero();
    }
    @Test void incompleteHistoryTimesOutAndCancels() {
        transport.historyAction = id -> transport.callback.historicalData(id, bar("20260910", 101, 105, 100, 104, 1));
        assertCode("UNAVAILABLE", () -> broker.getDailyBars(265598, 30));
        assertThat(transport.historyCancelled).isEqualTo(1);
    }
    @Test void invalidBarsAreRejectedRatherThanStored() {
        transport.historyAction = id -> transport.callback.historicalData(id, bar("20260910", 101, 99, 100, 104, 1));
        assertCode("INVALID_RESPONSE", () -> broker.getDailyBars(265598, 30));
        transport.historyAction = id -> transport.callback.historicalData(id, bar("finished", 101, 105, 100, 104, 1));
        assertCode("INVALID_RESPONSE", () -> broker.getDailyBars(265598, 30));
        transport.historyAction = id -> transport.callback.historicalData(id, bar("20260910", 0, 105, 100, 104, 1));
        assertCode("INVALID_RESPONSE", () -> broker.getDailyBars(265598, 30));
        assertThat(transport.historyCancelled).isEqualTo(3);
    }
    @Test void missingVolumeIsNullAndUnrelatedRequestIdsAreIgnored() {
        transport.historyAction = id -> {
            transport.callback.historicalData(id + 7, bar("20260901", 1, 1, 1, 1, 1));
            transport.callback.historicalData(id, new Bar("20260910", 101, 105, 100, 104, Decimal.INVALID, 1, Decimal.ONE));
            transport.callback.historicalDataEnd(id, "", "");
        };
        var history = broker.getDailyBars(265598, 30);
        assertThat(history.bars()).hasSize(1);
        assertThat(history.bars().get(0).volume()).isNull();
    }
    @Test void historyArgumentsAreBoundedBeforeConnecting() {
        assertCode("INVALID_ARGUMENT", () -> broker.getDailyBars(265598, 0));
        assertCode("INVALID_ARGUMENT", () -> broker.getDailyBars(265598, TwsClient.MAX_HISTORY_DAYS + 1));
        assertCode("INVALID_ARGUMENT", () -> broker.getDailyBars(0, 30));
        assertThat(transport.connects).isZero();
    }
    @Test void requestScopedWarningsDoNotFailTheRequest() {
        transport.historyAction = id -> {
            transport.callback.error(id, 0, 2174, "time zone warning", "");
            transport.callback.historicalData(id, bar("20260910", 101, 105, 100, 104, 1));
            transport.callback.historicalDataEnd(id, "", "");
        };
        assertThat(broker.getDailyBars(265598, 30).bars()).hasSize(1);
        transport.quoteAction = id -> {
            transport.callback.error(id, 0, 2119, "farm connecting", "");
            transport.callback.marketDataType(id, 3);
            transport.callback.tickPrice(id, 68, 100, new TickAttrib());
        };
        assertThat(broker.getQuote(265598).hasPrice()).isTrue();
    }
    @Test void historyProviderErrorsAreSanitizedAndCancelled() {
        transport.historyAction = id -> transport.callback.error(id, 0, 162, "HMDS query returned no data", "");
        assertCode("UNAVAILABLE", () -> broker.getDailyBars(265598, 30));
        assertThat(transport.historyCancelled).isEqualTo(1);
    }

    private static Contract stock(int id) {
        var c = new Contract(); c.conid(id); c.symbol("AAPL"); c.secType("STK");
        c.currency("USD"); c.exchange("SMART"); c.primaryExch("NASDAQ");
        return c;
    }
    private static ContractDescription description(Contract c) { return new ContractDescription(c, new String[0]); }
    private static Bar bar(String time, double open, double high, double low, double close, long volume) {
        return new Bar(time, open, high, low, close, Decimal.get(volume), 1, Decimal.get(close));
    }
    private static void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(BrokerException.class).hasMessage(code);
    }
    private static final class FakeTransport implements TwsTransport {
        EWrapper callback;
        boolean connected, sendHandshake = true, sendHeartbeat = true;
        int connects, timeRequests, positionRequests, positionsCancelled, quotesCancelled, quoteId, requestedDataType;
        int detailConid, historyDays, historyCancelled;
        Contract quoteContract;
        String positionsAccount, searchSymbol;
        IntConsumer quoteAction = id -> {}, positionAction = id -> {}, searchAction = id -> {}, historyAction = id -> {};
        @Override public void connect(EWrapper callback, IbkrProperties properties) {
            this.callback = callback; connected = true; connects++;
            if (sendHandshake) {
                callback.nextValidId(1);
                callback.managedAccounts("DU_OTHER,DU_TEST");
            }
        }
        @Override public boolean connected() { return connected; }
        @Override public void requestTime() { timeRequests++; if (sendHeartbeat) callback.currentTime(1750000000); }
        @Override public void search(int id, String symbol) { searchSymbol = symbol; searchAction.accept(id); }
        @Override public void contract(int id, long conid) {
            var d = new ContractDetails(); d.contract(stock(detailConid == 0 ? (int)conid : detailConid));
            callback.contractDetails(id, d); callback.contractDetailsEnd(id);
        }
        @Override public void positions(int id, String account) { positionRequests++; positionsAccount = account; positionAction.accept(id); }
        @Override public void cancelPositions(int id) { positionsCancelled++; }
        @Override public void quote(int id, Contract contract, int dataType) { quoteId = id; quoteContract = contract; requestedDataType = dataType; quoteAction.accept(id); }
        @Override public void cancelQuote(int id) { quotesCancelled++; }
        @Override public void history(int id, Contract contract, int days) { historyDays = days; historyAction.accept(id); }
        @Override public void cancelHistory(int id) { historyCancelled++; }
        @Override public void close() { connected = false; }
    }
}

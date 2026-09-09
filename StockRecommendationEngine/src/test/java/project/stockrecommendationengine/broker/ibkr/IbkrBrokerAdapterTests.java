package project.stockrecommendationengine.broker.ibkr;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import project.stockrecommendationengine.broker.BrokerException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class IbkrBrokerAdapterTests {
    private MockRestServiceServer server;
    private IbkrBrokerAdapter broker;
    private IbkrProperties properties;
    private IbkrApiClient client;

    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("https://localhost:5000/v1/api");
        server = MockRestServiceServer.bindTo(builder).build();
        properties = new IbkrProperties();
        properties.setAccountId("DU_TEST");
        properties.setRequestIntervalMs(0); // Test clock bypass; production validation requires >=100ms.
        client = new IbkrApiClient(builder.build(), properties);
        broker = new IbkrBrokerAdapter(client, new IbkrSessionManager(client), properties);
    }

    @Test void authorizesConfiguredAccountAndReadsEveryPositionPage() {
        get("/portfolio/accounts", "[{\"accountId\":\"DU_OTHER\"},{\"accountId\":\"DU_TEST\",\"currency\":\"USD\"}]");
        String page = IntStream.rangeClosed(1, 100).mapToObj(id -> "{\"conid\":" + id + ",\"position\":1.25}")
                .collect(Collectors.joining(",", "[", "]"));
        get("/portfolio/DU_TEST/positions/0", page);
        get("/portfolio/DU_TEST/positions/1", "[{\"conid\":101,\"position\":-2,\"currency\":\"USD\"}]");
        var portfolio = broker.getPositions();
        assertThat(portfolio.positions()).hasSize(101);
        assertThat(portfolio.positions().get(0).quantity()).isEqualByComparingTo("1.25");
        assertThat(portfolio.positions().get(100).quantity()).isEqualByComparingTo("-2");
        assertThat(portfolio.positions().get(0).marketPrice()).isNull();
        server.verify();
    }

    @Test void rejectsAnAccountNotInTheGatewayResponseBeforeFetchingPositions() {
        get("/portfolio/accounts", "[{\"accountId\":\"DU_OTHER\"}]");
        assertCode("ACCOUNT_NOT_ALLOWED", () -> broker.getPositions());
        server.verify();
    }

    @Test void refusesToReturnAnIncompletePortfolioAtThePageLimit() {
        properties.setMaxPositionPages(1);
        get("/portfolio/accounts", "[{\"id\":\"DU_TEST\"}]");
        get("/portfolio/DU_TEST/positions/0", IntStream.rangeClosed(1, 100)
                .mapToObj(id -> "{\"conid\":" + id + ",\"position\":1}").collect(Collectors.joining(",", "[", "]")));
        assertCode("POSITION_LIMIT", () -> broker.getPositions());
    }

    @Test void retriesSnapshotInitializationAndPreservesDelayedStatus() {
        ready();
        get("/iserver/marketdata/snapshot?conids=265598&fields=31,84,86,6509", "[{\"conid\":265598}]");
        get("/iserver/marketdata/snapshot?conids=265598&fields=31,84,86,6509",
                "[{\"conid\":265598,\"31\":\"1,234.56\",\"84\":\"N/A\",\"86\":\"0\",\"6509\":\"DpB\",\"_updated\":1750000000000}]");
        var quote = broker.getQuote(265598);
        assertThat(quote.last()).isEqualByComparingTo("1234.56");
        assertThat(quote.bid()).isNull();
        assertThat(quote.ask()).isNull();
        assertThat(quote.availability()).isEqualTo("DELAYED");
        assertThat(quote.updatedAt().toEpochMilli()).isEqualTo(1750000000000L);
        server.verify();
    }

    @Test void returnsUnavailableWithoutInventingPrices() {
        properties.setSnapshotAttempts(1);
        ready();
        get("/iserver/marketdata/snapshot?conids=1&fields=31,84,86,6509", "[{\"conid\":1,\"31\":\"--\"}]");
        var quote = broker.getQuote(1);
        assertThat(quote.hasPrice()).isFalse();
        assertThat(quote.availability()).isEqualTo("UNAVAILABLE");
        assertThat(quote.updatedAt()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"connected\":true,\"authenticated\":false}",
            "{\"connected\":true,\"authenticated\":true,\"established\":false}",
            "{\"connected\":true,\"authenticated\":true,\"competing\":true}"
    }) void doesNotFetchQuotesInAnUnreadySession(String status) {
        post("/iserver/auth/status", status);
        assertCode("SESSION_NOT_READY", () -> broker.getQuote(1));
        server.verify();
    }

    @Test void preservesMultipleMatchingStockContracts() {
        ready();
        get("/iserver/secdef/search?symbol=AAPL&secType=STK", """
                [{"conid":"1","symbol":"AAPL","description":"NASDAQ","sections":[{"secType":"STK"}]},
                 {"conid":"2","symbol":"AAPL","description":"MEXI","sections":[{"secType":"STK"}]},
                 {"conid":"3","symbol":"AAPLX","sections":[{"secType":"STK"}]}]
                """);
        var instruments = broker.searchInstruments("aapl");
        assertThat(instruments).extracting(item -> item.conid()).containsExactly(1L, 2L);
        assertThat(instruments.get(0).currency()).isNull();
        assertThat(instruments.get(0).exchange()).isEqualTo("NASDAQ");
    }

    @Test void retriesOneTransientGetButDoesNotRetryRateLimits() {
        server.expect(requestTo("https://localhost:5000/v1/api/portfolio/accounts"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        get("/portfolio/accounts", "[{\"id\":\"DU_TEST\"}]");
        assertThat(broker.getAccounts()).hasSize(1);
        server.verify();
        server.reset();
        server.expect(requestTo("https://localhost:5000/v1/api/portfolio/accounts"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("private account details"));
        assertCode("RATE_LIMITED", () -> broker.getAccounts());
        server.verify();
    }

    @Test void sanitizesAuthenticationFailureAndRejectsWrongContractResponse() {
        server.expect(requestTo("https://localhost:5000/v1/api/portfolio/accounts"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("session-secret"));
        assertCode("LOGIN_REQUIRED", () -> broker.getAccounts());
        server.verify();
        server.reset();
        ready();
        get("/iserver/marketdata/snapshot?conids=1&fields=31,84,86,6509", "[{\"conid\":2,\"31\":100}]");
        assertCode("INVALID_RESPONSE", () -> broker.getQuote(1));
    }

    @Test void transportDoesNotExposeOrderPosts() {
        assertThatThrownBy(() -> client.postSession("/iserver/account/DU_TEST/orders"))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    private void ready() { post("/iserver/auth/status", "{\"connected\":true,\"authenticated\":true}"); }
    private void get(String path, String body) {
        server.expect(requestTo("https://localhost:5000/v1/api" + path)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
    private void post(String path, String body) {
        server.expect(requestTo("https://localhost:5000/v1/api" + path)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
    private void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(BrokerException.class).hasMessage(code);
    }
}

package project.ragdemo.market;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.junit.jupiter.api.Assertions.*;
class IBKRClientTest {
    @Test
    void parsesSymbolMapAndWarmsSnapshotWithoutLiveGateway() {
        var builder = RestClient.builder().baseUrl("https://gateway.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new IBKRClient(builder.build());
        server.expect(requestTo("https://gateway.test/trsrv/stocks?symbols=AAPL"))
                .andRespond(withSuccess("""
                        {"AAPL":[{"name":"Apple","assetClass":"STK","contracts":[
                        {"conid":123,"exchange":"NASDAQ","isUS":true},
                        {"conid":456,"exchange":"MEXI","isUS":false}]}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gateway.test/iserver/accounts")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gateway.test/iserver/marketdata/snapshot?conids=123&fields=31,6509"))
                .andRespond(withSuccess("""
                        [{"conid":123}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gateway.test/iserver/marketdata/snapshot?conids=123&fields=31,6509"))
                .andRespond(withSuccess("""
                        [{"conid":123,"31":"201.25","6509":"RpB","_updated":1700000000000}]
                        """, MediaType.APPLICATION_JSON));
        assertEquals("123", client.findContractsId("AAPL"));
        assertEquals("201.25", client.getCurrentPrice("123").lastPrice());
        server.verify();
    }
    @Test
    void pollsPastEmptyAndWrongContractResponsesAndPreservesProvenance() {
        var builder = RestClient.builder().baseUrl("https://gateway.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://gateway.test/iserver/accounts"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        for (String body : new String[] {"[]", "[null]", "[{\"conid\":999,\"31\":\"1\"}]",
                "[{\"conid\":123,\"31\":\"C201.25\",\"6509\":\"ZB\",\"_updated\":1700000000000}]"}) {
            server.expect(requestTo("https://gateway.test/iserver/marketdata/snapshot?conids=123&fields=31,6509"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        var quote = new IBKRClient(builder.build()).getCurrentPrice("123");
        assertEquals("C201.25", quote.lastPrice());
        assertEquals("ZB", quote.availability());
        assertEquals(1700000000000L, quote.updated());
        server.verify();
    }

    @Test
    void stopsAfterFivePolls() {
        var builder = RestClient.builder().baseUrl("https://gateway.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://gateway.test/iserver/accounts"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        for (int i = 0; i < 6; i++)
            server.expect(requestTo("https://gateway.test/iserver/marketdata/snapshot?conids=123&fields=31,6509"))
                    .andRespond(withSuccess("[{\"conid\":123}]", MediaType.APPLICATION_JSON));
        var error = assertThrows(IllegalStateException.class,
                () -> new IBKRClient(builder.build()).getCurrentPrice("123"));
        assertTrue(error.getMessage().contains("five polls"));
        server.verify();
    }

    @Test
    void interruptionStopsFurtherPolling() {
        var builder = RestClient.builder().baseUrl("https://gateway.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://gateway.test/iserver/accounts"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gateway.test/iserver/marketdata/snapshot?conids=123&fields=31,6509"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        try {
            Thread.currentThread().interrupt();
            assertThrows(IllegalStateException.class, () -> new IBKRClient(builder.build()).getCurrentPrice("123"));
            assertTrue(Thread.currentThread().isInterrupted());
            server.verify();
        } finally { Thread.interrupted(); }
    }

    @Test
    void ambiguousContractsAreRejected() {
        var builder = RestClient.builder().baseUrl("https://gateway.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://gateway.test/trsrv/stocks?symbols=AAPL"))
                .andRespond(withSuccess("""
                        {"AAPL":[{"assetClass":"STK","contracts":[
                        {"conid":1,"isUS":true},{"conid":2,"isUS":true}]}]}
                        """, MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class, () -> new IBKRClient(builder.build()).findContractsId("AAPL"));
        server.verify();
    }
}

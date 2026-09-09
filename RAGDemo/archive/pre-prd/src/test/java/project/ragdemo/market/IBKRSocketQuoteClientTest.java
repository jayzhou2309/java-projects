package project.ragdemo.market;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.TickAttrib;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IBKRSocketQuoteClientTest {
    @Test void defaultTransportUsesSocketWithoutRestCertificate() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            var properties = new IBKRProperties();
            properties.setTrustedCertificate("/nonexistent/certificate.pem");
            context.registerBean(IBKRProperties.class, () -> properties);
            context.register(project.ragdemo.config.IBKRConfig.class, IBKRClient.class, IBKRSocketQuoteClient.class);
            context.refresh();
            assertInstanceOf(IBKRSocketQuoteClient.class, context.getBean(BrokerQuoteClient.class));
            assertTrue(context.getBeansOfType(org.springframework.web.client.RestClient.class).isEmpty());
        }
    }

    @Test void explicitRestTransportRetainsCompatibility() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("test", java.util.Map.of("app.ibkr.transport", "rest")));
            var properties = new IBKRProperties();
            properties.setBaseUrl("https://localhost:5001/v1/api");
            context.registerBean(IBKRProperties.class, () -> properties);
            context.register(project.ragdemo.config.IBKRConfig.class, IBKRClient.class, IBKRSocketQuoteClient.class);
            context.refresh();
            assertInstanceOf(IBKRClient.class, context.getBean(BrokerQuoteClient.class));
        }
    }

    @Test void waitsForAvailabilityAndIgnoresBidAndOtherRequests() {
        try (var session = new IBKRSocketQuoteClient.Session(new IBKRProperties())) {
            session.conid = "265598";
            session.tickPrice(2, 1, 99, new TickAttrib());
            session.tickPrice(9, 4, 100, new TickAttrib());
            session.marketDataType(2, 3);
            assertFalse(session.quote.isDone());
            session.tickPrice(2, 68, 101.25, new TickAttrib());
            var quote = session.await(session.quote);
            assertEquals("D", quote.availability());
            assertEquals("101.25", quote.lastPrice());
            assertNotNull(quote.updated());
            assertEquals(MarketData.Status.STALE,
                    new MarketDataServiceImpl(null).normalize("AAPL", "265598", quote, java.time.Instant.now()).status());
        }
    }

    @Test void requiresActualTypeCallbackAndRejectsInvalidPrices() {
        try (var session = new IBKRSocketQuoteClient.Session(new IBKRProperties())) {
            session.tickPrice(2, 4, -1, new TickAttrib());
            session.tickPrice(2, 4, Double.NaN, new TickAttrib());
            assertNull(session.price);
            session.tickPrice(2, 4, 100, new TickAttrib());
            assertFalse(session.quote.isDone());
            session.marketDataType(2, 2);
            assertEquals("Z", session.await(session.quote).availability());
        }
    }

    @Test void ambiguousContractsFailAndOtherRequestsAreIgnored() {
        try (var session = new IBKRSocketQuoteClient.Session(new IBKRProperties())) {
            for (int id : new int[] {1, 2}) {
                Contract c = new Contract();
                c.conid(id); c.secType("STK"); c.currency("USD");
                ContractDetails d = new ContractDetails(); d.contract(c);
                session.contractDetails(1, d);
            }
            session.contractDetailsEnd(9);
            assertFalse(session.contractId.isDone());
            session.contractDetailsEnd(1);
            assertThrows(IllegalStateException.class, () -> session.await(session.contractId));
        }
    }

    @Test void entitlementErrorsSurfaceButDelayedWarningDoesNotAbort() {
        try (var session = new IBKRSocketQuoteClient.Session(new IBKRProperties())) {
            session.error(2, 0, 10167, "delayed available", "");
            assertFalse(session.quote.isDone());
            session.error(2, 0, 10089, "not subscribed", "");
            var error = assertThrows(IllegalStateException.class, () -> session.await(session.quote));
            assertTrue(error.getMessage().contains("10089"));
        }
    }

    @Test void disconnectFailsPendingRequests() {
        try (var session = new IBKRSocketQuoteClient.Session(new IBKRProperties())) {
            session.connectionClosed();
            assertTrue(session.ready.isCompletedExceptionally());
            assertTrue(session.contractId.isCompletedExceptionally());
            assertTrue(session.quote.isCompletedExceptionally());
        }
    }

    @Test void interruptedWaitPreservesCancellation() {
        try (var session = new IBKRSocketQuoteClient.Session(new IBKRProperties())) {
            Thread.currentThread().interrupt();
            assertThrows(IllegalStateException.class, () -> session.await(session.quote));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}

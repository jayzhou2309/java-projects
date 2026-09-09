package project.ragdemo.stock;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.ragdemo.ingestion.*;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@SpringBootTest(properties = {"app.auth.username=integration-reader", "app.auth.password=integration-only-password"})
@Transactional
class FoundationPersistenceIntegrationTest {
    @Autowired StockRepository stocks;
    @Autowired StockIdentifierRepository identifiers;
    @Autowired IngestionRunRepository runs;
    @Autowired JdbcTemplate jdbc;
    private final Instant start = Instant.parse("2026-01-01T00:00:00Z");

    @Test void roundTripsStockAndIngestionProvenance() {
        Stock stock = stocks.saveAndFlush(new Stock("TEST", "Synthetic fixture", "0000000001", start));
        var run = new IngestionRun("fixture", "prices", stock.getId(), start);
        run.succeed(start.plusSeconds(10), 3);
        runs.saveAndFlush(run);
        assertEquals(stock.getId(), stocks.findBySymbol("TEST").orElseThrow().getId());
        assertEquals("SUCCEEDED", jdbc.queryForObject("select status from ingestion_runs where id=?", String.class, run.getId()));
        assertEquals(3L, jdbc.queryForObject("select records_processed from ingestion_runs where id=?", Long.class, run.getId()));
    }

    @Test void identifierLookupExcludesFutureKnowledgeAndRespectsHalfOpenIntervals() {
        var stock = stocks.saveAndFlush(new Stock("TEST", "Synthetic fixture", "0000000001", start));
        identifiers.saveAndFlush(new StockIdentifier(stock.getId(), "TICKER", "OLD", "fixture",
                start, start.plusSeconds(20), start));
        identifiers.saveAndFlush(new StockIdentifier(stock.getId(), "TICKER", "FUTURE", "fixture",
                start, null, start.plusSeconds(10)));
        assertEquals(1, identifiers.findKnownAt("TICKER", "OLD", start).size());
        assertTrue(identifiers.findKnownAt("TICKER", "OLD", start.plusSeconds(20)).isEmpty());
        assertTrue(identifiers.findKnownAt("TICKER", "FUTURE", start).isEmpty());
        assertEquals(1, identifiers.findKnownAt("TICKER", "FUTURE", start.plusSeconds(10)).size());
    }

    @Test void databaseRejectsInvalidStatusEvenWhenBypassingJava() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into ingestion_runs(id,provider,dataset,status,started_at) values (?, 'fixture','test','UNKNOWN',now())",
                UUID.randomUUID()));
    }

    @Test void databaseRejectsDuplicateIdentifiers() {
        var stock = stocks.saveAndFlush(new Stock("TEST", "Synthetic fixture", "0000000001", start));
        identifiers.saveAndFlush(new StockIdentifier(stock.getId(), "TICKER", "TEST", "fixture", start, null, start));
        assertThrows(DataIntegrityViolationException.class, () -> identifiers.saveAndFlush(
                new StockIdentifier(stock.getId(), "TICKER", "TEST", "fixture", start, null, start)));
    }

    @Test void databaseRejectsNegativeCounts() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into ingestion_runs(id,provider,dataset,status,started_at,records_processed) values (?, 'fixture','test','RUNNING',now(),-1)",
                UUID.randomUUID()));
    }
    @Test void databaseRejectsIncompleteTerminalRuns() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into ingestion_runs(id,provider,dataset,status,started_at,records_processed) values (?, 'fixture','test','SUCCEEDED',now(),0)",
                UUID.randomUUID()));
    }

}

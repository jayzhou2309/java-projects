package project.ragdemo.stock;

import javax.sql.DataSource;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@SpringBootTest(properties = {"app.auth.username=integration-reader", "app.auth.password=integration-only-password"})
class MigrationUpgradeIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @Test void upgradesV5SchemaWithoutLosingExistingStocksOrFilings() {
        // Only this randomly named test schema is removed; never clean the application database.
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            var old = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .target("5").cleanDisabled(true).load();
            old.migrate();
            jdbc.update("insert into " + schema + ".stocks(symbol,company_name,cik) values ('OLD','Fixture','0000000001')");
            jdbc.update("insert into " + schema + ".filings(stock_id,filing_type,accession_no,filed_date,source_url) "
                    + "select id,'TEN_K','fixture-accession',date '2020-01-01','https://example.test/filing' from " + schema + ".stocks");
            var current = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).cleanDisabled(true).load();
            assertEquals(1, current.migrate().migrationsExecuted);
            current.validate();
            assertEquals(0, current.migrate().migrationsExecuted);
            assertEquals(1, jdbc.queryForObject("select count(*) from " + schema + ".stocks where symbol='OLD'", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from " + schema + ".filings where accession_no='fixture-accession'", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from " + schema + ".stock_identifiers", Integer.class));
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }
}

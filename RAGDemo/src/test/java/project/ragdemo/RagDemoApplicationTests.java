package project.ragdemo;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@SpringBootTest(properties = {"app.auth.username=integration-reader", "app.auth.password=integration-only-password"})
class RagDemoApplicationTests {
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;

    @Test void contextLoadsWithValidatedMigrationsAndAllRequiredExtensions() {
        flyway.validate();
        assertEquals("6", flyway.info().current().getVersion().getVersion());
        assertEquals(0, flyway.info().pending().length);
        for (String extension : new String[] {"vector", "timescaledb", "pgcrypto"}) {
            assertEquals(1, jdbc.queryForObject("select count(*) from pg_extension where extname = ?", Integer.class, extension));
        }
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema='public' and table_name='vector_store'", Integer.class),
                "Phase 1 must not create an unversioned Spring AI vector store");
    }
}

package project.ragdemo.ingestion;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IngestionRunTest {
    private final Instant start = Instant.parse("2026-09-09T00:00:00Z");

    @Test void successCompletesRunExactlyOnce() {
        var run = new IngestionRun("fixture", "filings", null, start);
        run.succeed(start.plusSeconds(1), 5);
        assertEquals(IngestionStatus.SUCCEEDED, run.getStatus());
        assertEquals(5, run.getRecordsProcessed());
        assertEquals(start.plusSeconds(1), run.getCompletedAt());
        assertThrows(IllegalStateException.class, () -> run.fail(start.plusSeconds(2), 5, "retry"));
    }

    @Test void failureRetainsBoundedSafeSummaryAndCount() {
        var run = new IngestionRun("fixture", "filings", null, start);
        run.fail(start.plusSeconds(1), 2, "PROVIDER_UNAVAILABLE");
        assertEquals(IngestionStatus.FAILED, run.getStatus());
        assertEquals("PROVIDER_UNAVAILABLE", run.getErrorSummary());
        assertEquals(2, run.getRecordsProcessed());
    }

    @Test void invalidCompletionDoesNotChangeRunningState() {
        var run = new IngestionRun("fixture", "filings", null, start);
        assertThrows(IllegalArgumentException.class, () -> run.succeed(start.minusSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> run.succeed(start, -1));
        assertThrows(IllegalArgumentException.class, () -> run.fail(start, 0, "x".repeat(1001)));
        assertEquals(IngestionStatus.RUNNING, run.getStatus());
        assertNull(run.getCompletedAt());
    }
}

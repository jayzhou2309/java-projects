package project.ragdemo.research;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import project.ragdemo.monitoring.RunMonitor;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static project.ragdemo.research.AgentResult.StageStatus.*;

class AgentRunnerTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final RunMonitor monitor = new RunMonitor();
    private final RuntimeConfig.Limits limits = new RuntimeConfig.Limits(Duration.ofMillis(200), Duration.ofSeconds(2), 2);
    private final AgentRunner runner = new AgentRunner(executor, limits, monitor);
    private final RunContext context = new RunContext(monitor.start(), limits.runTimeout());

    @AfterEach
    void shutdown() { executor.shutdownNow(); }

    @Test
    void retriesOnlyTransientFailuresWithinAttemptLimit() {
        AtomicInteger attempts = new AtomicInteger();
        var recovered = runner.run("recover", () -> {
            if (attempts.incrementAndGet() == 1) throw new TransientDataAccessResourceException("temporary");
            return "ok";
        }, context);
        assertEquals(COMPLETED, recovered.status());
        assertEquals(2, attempts.get());
        attempts.set(0);
        var failed = runner.run("exhaust", () -> {
            attempts.incrementAndGet();
            throw new TransientDataAccessResourceException("temporary");
        }, context);
        assertEquals(FAILED, failed.status());
        assertEquals(2, attempts.get());
        assertTrue(monitor.snapshot().get(0).events().stream().anyMatch(e -> e.status().equals("RETRYING")));
        assertEquals(2, context.traces().get(1).attempts());
    }

    @Test
    void deterministicFailureDoesNotRetryOrExposeError() {
        AtomicInteger attempts = new AtomicInteger();
        var result = runner.run("invalid", () -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("sensitive provider details");
        }, context);
        assertEquals(FAILED, result.status());
        assertEquals(1, attempts.get());
        assertFalse(monitor.snapshot().toString().contains("sensitive"));
    }

    @Test
    void timedOutWorkIsInterruptedAndNeverRetried() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        var result = runner.run("slow", () -> {
            attempts.incrementAndGet();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.countDown(); throw expected; }
            return "unexpected";
        }, context);
        assertEquals(TIMED_OUT, result.status());
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertEquals(1, attempts.get());
    }

    @Test
    void exhaustedRunBudgetSkipsExecutionAndSkipProducesTrace() {
        RunContext expired = new RunContext(monitor.start(), Duration.ZERO);
        AtomicInteger calls = new AtomicInteger();
        assertEquals(TIMED_OUT, runner.run("expired", calls::incrementAndGet, expired).status());
        assertEquals(0, calls.get());
        assertEquals(SKIPPED, runner.skip("optional", expired).status());
        assertEquals(2, expired.traces().size());
        assertEquals(0, expired.traces().get(0).attempts());
    }

    @Test
    void rejectedWorkReturnsFailure() {
        executor.shutdownNow();
        assertEquals(FAILED, runner.run("busy", () -> "unused", context).status());
    }
}

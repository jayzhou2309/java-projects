package project.ragdemo.monitoring;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RunMonitorTest {
    @Test
    void snapshotsAreIsolatedAndEventsStayWithTheirRun() {
        RunMonitor monitor = new RunMonitor();
        String first = monitor.start();
        var before = monitor.snapshot();
        String second = monitor.start();
        monitor.record(first, "SEC retrieval", "FAILED", "Failed");
        assertEquals(1, before.get(0).events().size());
        var after = monitor.snapshot();
        assertEquals(second, after.get(0).id());
        assertEquals(1, after.get(0).events().size());
        assertEquals("FAILED", after.get(1).events().get(1).status());
        assertThrows(UnsupportedOperationException.class, () -> after.get(0).events().clear());
    }

    @Test
    void historyAndPerRunEventsAreBounded() {
        RunMonitor monitor = new RunMonitor();
        String oldest = monitor.start();
        for (int i = 0; i < 100; i++) monitor.start();
        assertEquals(100, monitor.snapshot().size());
        assertFalse(monitor.snapshot().stream().anyMatch(run -> run.id().equals(oldest)));
        monitor.record(oldest, "Late stage", "COMPLETED", "Ignored after eviction");
        String latest = monitor.snapshot().get(0).id();
        for (int i = 0; i < 150; i++) monitor.record(latest, "Stage", "RUNNING", "Activity");
        assertEquals(100, monitor.snapshot().get(0).events().size());
    }
}

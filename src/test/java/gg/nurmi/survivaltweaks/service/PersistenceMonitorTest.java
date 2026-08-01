package gg.nurmi.survivaltweaks.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceMonitorTest {

    @Test
    void summarizesQueueProgressAndFailuresByLane() {
        Instant now = Instant.parse("2026-08-01T12:00:00Z");
        PersistenceMonitor monitor = new PersistenceMonitor(
                Clock.fixed(now, ZoneOffset.UTC)
        );

        monitor.queued("profiles", 2);
        monitor.started("profiles", 1);
        monitor.failed("profiles", 1, new IllegalStateException("database offline"));

        PersistenceMonitor.Snapshot failed = monitor.snapshot();
        assertEquals(1, failed.pending());
        assertEquals("database offline", failed.lastFailureReason());
        assertEquals(1, failed.lanes().get("profiles").consecutiveFailures());

        monitor.started("profiles", 0);
        monitor.succeeded("profiles", 0);
        PersistenceMonitor.Snapshot recovered = monitor.snapshot();
        assertEquals(0, recovered.pending());
        assertFalse(recovered.lanes().get("profiles").active());
        assertEquals(0, recovered.lanes().get("profiles").consecutiveFailures());
        assertTrue(recovered.lastSuccess().equals(now));
    }
}

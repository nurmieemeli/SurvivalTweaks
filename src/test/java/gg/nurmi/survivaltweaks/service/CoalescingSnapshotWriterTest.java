package gg.nurmi.survivaltweaks.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoalescingSnapshotWriterTest {

    @Test
    void transientFailureRetriesAfterAnEqualSubmission() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch fail = new CountDownLatch(1);
        CountDownLatch saved = new CountDownLatch(1);
        CoalescingSnapshotWriter<String> writer = new CoalescingSnapshotWriter<>(
                "snapshot-retry-test",
                "retry test state",
                value -> {
                    if (attempts.incrementAndGet() == 1) {
                        writing.countDown();
                        try {
                            assertTrue(fail.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IOException(exception);
                        }
                        throw new IOException("temporary outage");
                    }
                    saved.countDown();
                },
                Logger.getLogger(CoalescingSnapshotWriterTest.class.getName())
        );

        try {
            writer.submit(new String("snapshot"));
            assertTrue(writing.await(3, TimeUnit.SECONDS));
            writer.submit(new String("snapshot"));
        } finally {
            fail.countDown();
        }

        assertTrue(saved.await(3, TimeUnit.SECONDS));
        writer.close();
        assertEquals(2, attempts.get());
    }

    @Test
    void runtimeSaveFailureIsRecordedAndDoesNotStrandLaterWrites() throws Exception {
        var monitor = new PersistenceMonitor();
        var attempted = new CountDownLatch(1);
        var lastSaved = new java.util.concurrent.atomic.AtomicReference<String>();
        try (var writer = new CoalescingSnapshotWriter<String>("runtime-retry-test", "test",
                value -> {
                    if (value.equals("first")) {
                        attempted.countDown();
                        throw new IllegalStateException("transient failure");
                    }
                    lastSaved.set(value);
                }, Logger.getAnonymousLogger(), monitor, "test")) {
            writer.submit("first");
            assertTrue(attempted.await(3, TimeUnit.SECONDS));
            writer.submit("second");
            assertTrue(writer.flush(Duration.ofSeconds(3)));
            assertEquals("second", lastSaved.get());
            assertTrue(monitor.snapshot().lastFailure() != null);
        }
    }

    @Test
    void flushSucceedsImmediatelyWhenNothingWasEverSubmitted() {
        CoalescingSnapshotWriter<String> writer = new CoalescingSnapshotWriter<>(
                "snapshot-idle-flush-test",
                "idle flush test state",
                value -> {
                },
                Logger.getLogger(CoalescingSnapshotWriterTest.class.getName())
        );

        // An idle lane has nothing to wait for, so a flush must not depend on
        // getting a turn on the writer thread at all.
        assertTrue(writer.flush(Duration.ZERO));
        writer.close();
    }

    @Test
    void flushWaitsForTheWriteItselfRatherThanItsTurnInTheQueue() throws Exception {
        List<String> saved = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CoalescingSnapshotWriter<String> writer = new CoalescingSnapshotWriter<>(
                "snapshot-flush-test",
                "flush test state",
                value -> {
                    writing.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    saved.add(value);
                },
                Logger.getLogger(CoalescingSnapshotWriterTest.class.getName())
        );

        writer.submit("queued");
        assertTrue(writing.await(2, TimeUnit.SECONDS));
        assertFalse(writer.flush(Duration.ofMillis(200)));

        release.countDown();
        assertTrue(writer.flush(Duration.ofSeconds(5)));
        assertEquals(List.of("queued"), saved);
        writer.close();
    }

    @Test
    void flushCoversEverySubmissionThatPrecededIt() throws Exception {
        List<String> saved = Collections.synchronizedList(new ArrayList<>());
        CoalescingSnapshotWriter<String> writer = new CoalescingSnapshotWriter<>(
                "snapshot-coalesced-flush-test",
                "coalesced flush test state",
                saved::add,
                Logger.getLogger(CoalescingSnapshotWriterTest.class.getName())
        );

        for (int index = 0; index < 50; index++) {
            writer.submit("snapshot-" + index);
        }

        assertTrue(writer.flush(Duration.ofSeconds(5)));
        assertEquals("snapshot-49", saved.get(saved.size() - 1));
        writer.close();
    }

    @Test
    void shutdownFlushesTheNewestCoalescedSnapshot() throws Exception {
        List<String> saved = new ArrayList<>();
        CountDownLatch firstWrite = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CoalescingSnapshotWriter<String> writer = new CoalescingSnapshotWriter<>(
                "snapshot-writer-test",
                "test state",
                value -> {
                    synchronized (saved) {
                        saved.add(value);
                    }
                    firstWrite.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                },
                Logger.getLogger(CoalescingSnapshotWriterTest.class.getName())
        );

        writer.submit("first");
        assertTrue(firstWrite.await(2, TimeUnit.SECONDS));
        writer.submit("second");
        writer.submit("latest");
        release.countDown();
        writer.close();

        synchronized (saved) {
            assertEquals(List.of("first", "latest"), saved);
        }
    }
}

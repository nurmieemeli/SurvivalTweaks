package gg.nurmi.survivaltweaks.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoalescingSnapshotWriterTest {

    @Test
    void transientFailureRetriesWithoutAnotherSubmission() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch saved = new CountDownLatch(1);
        CoalescingSnapshotWriter<String> writer = new CoalescingSnapshotWriter<>(
                "snapshot-retry-test",
                "retry test state",
                value -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IOException("temporary outage");
                    }
                    saved.countDown();
                },
                Logger.getLogger(CoalescingSnapshotWriterTest.class.getName())
        );

        writer.submit("only-submission");

        assertTrue(saved.await(3, TimeUnit.SECONDS));
        writer.close();
        assertEquals(2, attempts.get());
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

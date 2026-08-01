package gg.nurmi.survivaltweaks.service;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

final class CoalescingSnapshotWriter<T> implements AutoCloseable {

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final long INITIAL_RETRY_MILLIS = 100;
    private static final long MAX_RETRY_MILLIS = 2_000;

    private final ThrowingConsumer<T> saver;
    private final Logger logger;
    private final String description;
    private final PersistenceMonitor monitor;
    private final String lane;
    private final AtomicReference<T> pending = new AtomicReference<>();
    private final AtomicReference<T> latestRequested = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final ExecutorService executor;
    private volatile boolean closing;

    CoalescingSnapshotWriter(
            String threadName,
            String description,
            ThrowingConsumer<T> saver,
            Logger logger
    ) {
        this(threadName, description, saver, logger, new PersistenceMonitor(), description);
    }

    CoalescingSnapshotWriter(
            String threadName,
            String description,
            ThrowingConsumer<T> saver,
            Logger logger,
            PersistenceMonitor monitor,
            String lane
    ) {
        this.saver = Objects.requireNonNull(saver, "saver");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.description = Objects.requireNonNull(description, "description");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.lane = Objects.requireNonNull(lane, "lane");
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, threadName);
            thread.setDaemon(false);
            return thread;
        });
    }

    void submit(T snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (closing) {
            throw new IllegalStateException(description + " writer is closing");
        }
        T previous = latestRequested.getAndSet(snapshot);
        if (snapshot.equals(previous)) {
            return;
        }
        pending.set(snapshot);
        monitor.queued(lane, 1);
        if (scheduled.compareAndSet(false, true)) {
            executor.execute(this::drain);
        }
    }

    @Override
    public void close() {
        if (closing) {
            return;
        }
        closing = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                logger.warning("Timed out while waiting for " + description + " to finish saving.");
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            logger.warning("Interrupted while waiting for " + description + " to finish saving.");
        }
    }

    private void drain() {
        int failures = 0;
        while (true) {
            T snapshot;
            while ((snapshot = pending.getAndSet(null)) != null) {
                monitor.started(lane, pending.get() == null ? 0 : 1);
                try {
                    saver.accept(snapshot);
                    failures = 0;
                    monitor.succeeded(lane, pending.get() == null ? 0 : 1);
                } catch (IOException exception) {
                    logger.log(Level.SEVERE, "Could not save " + description, exception);
                    if (latestRequested.get() == snapshot) {
                        pending.compareAndSet(null, snapshot);
                    }
                    monitor.failed(lane, pending.get() == null ? 0 : 1, exception);
                    if (!pauseBeforeRetry(++failures)) {
                        return;
                    }
                }
            }
            scheduled.set(false);
            monitor.queued(lane, pending.get() == null ? 0 : 1);
            if (pending.get() == null || !scheduled.compareAndSet(false, true)) {
                return;
            }
        }
    }

    private boolean pauseBeforeRetry(int failures) {
        long delay = Math.min(
                MAX_RETRY_MILLIS,
                INITIAL_RETRY_MILLIS << Math.min(4, failures - 1)
        );
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @FunctionalInterface
    interface ThrowingConsumer<T> {

        void accept(T value) throws IOException;
    }
}

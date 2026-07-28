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

    private final ThrowingConsumer<T> saver;
    private final Logger logger;
    private final String description;
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
        this.saver = Objects.requireNonNull(saver, "saver");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.description = Objects.requireNonNull(description, "description");
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
        while (true) {
            T snapshot;
            while ((snapshot = pending.getAndSet(null)) != null) {
                try {
                    saver.accept(snapshot);
                } catch (IOException exception) {
                    latestRequested.compareAndSet(snapshot, null);
                    logger.log(Level.SEVERE, "Could not save " + description, exception);
                }
            }
            scheduled.set(false);
            if (pending.get() == null || !scheduled.compareAndSet(false, true)) {
                return;
            }
        }
    }

    @FunctionalInterface
    interface ThrowingConsumer<T> {

        void accept(T value) throws IOException;
    }
}

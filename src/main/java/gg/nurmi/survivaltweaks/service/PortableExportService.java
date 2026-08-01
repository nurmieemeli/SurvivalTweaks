package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.storage.StorageBackend;
import gg.nurmi.survivaltweaks.storage.StorageManager;
import org.bukkit.configuration.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class PortableExportService implements AutoCloseable {

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(35);
    private static final String COLLECTION = "automatic";

    private final StorageManager storage;
    private final Logger logger;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean exporting = new AtomicBoolean();
    private ScheduledFuture<?> scheduled;
    private volatile Settings settings = Settings.DISABLED;
    private volatile boolean closing;

    public PortableExportService(StorageManager storage, Logger logger) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.logger = Objects.requireNonNull(logger, "logger");
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "SurvivalTweaks-portable-export");
            thread.setDaemon(false);
            return thread;
        });
    }

    public synchronized void reconfigure(Configuration config) {
        Objects.requireNonNull(config, "config");
        reconfigure(settings(config));
    }

    public static void validate(Configuration config) {
        settings(Objects.requireNonNull(config, "config"));
    }

    private static Settings settings(Configuration config) {
        return new Settings(
                config.getBoolean("storage.portable-exports.enabled", true),
                Duration.ofHours(config.getInt("storage.portable-exports.interval-hours", 24)),
                Duration.ofMinutes(config.getInt(
                        "storage.portable-exports.initial-delay-minutes",
                        5
                )),
                config.getInt("storage.portable-exports.retention", 7)
        );
    }

    synchronized void reconfigure(Settings updated) {
        if (closing) {
            return;
        }
        settings = Objects.requireNonNull(updated, "updated");
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
        if (!updated.enabled() || storage.backend() == StorageBackend.SQLITE) {
            return;
        }
        scheduled = executor.scheduleWithFixedDelay(
                this::exportSafely,
                updated.initialDelay().toMillis(),
                updated.interval().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    StorageManager.ExportResult exportNow() throws IOException {
        if (!exporting.compareAndSet(false, true)) {
            throw new IOException("A portable storage export is already running");
        }
        try {
            StorageManager.ExportResult result = storage.exportPortable(COLLECTION);
            rotate(settings.retention());
            return result;
        } finally {
            exporting.set(false);
        }
    }

    @Override
    public synchronized void close() {
        if (closing) {
            return;
        }
        closing = true;
        if (scheduled != null) {
            scheduled.cancel(true);
            scheduled = null;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(
                    SHUTDOWN_TIMEOUT.toSeconds(),
                    TimeUnit.SECONDS
            )) {
                logger.warning("Timed out while stopping automatic portable storage exports.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warning("Interrupted while stopping automatic portable storage exports.");
        }
    }

    private void exportSafely() {
        try {
            StorageManager.ExportResult result = exportNow();
            logger.info(
                    "Created verified automatic portable storage export "
                            + result.file().getFileName() + " ("
                            + result.counts().total() + " logical records)"
            );
        } catch (Exception exception) {
            if (!closing) {
                logger.log(Level.SEVERE, "Could not create automatic portable storage export", exception);
            }
        }
    }

    private void rotate(int retention) throws IOException {
        Path folder = storage.dataFolder()
                .resolve("storage-exports")
                .resolve(COLLECTION);
        if (Files.notExists(folder)) {
            return;
        }
        List<Path> exports;
        try (Stream<Path> files = Files.list(folder)) {
            exports = files
                    .filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().endsWith(".db"))
                    .sorted(Comparator
                            .comparing(this::lastModified)
                            .thenComparing(path -> path.getFileName().toString())
                            .reversed())
                    .toList();
        }
        for (int index = retention; index < exports.size(); index++) {
            Files.deleteIfExists(exports.get(index));
        }
    }

    private java.nio.file.attribute.FileTime lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException ignored) {
            return java.nio.file.attribute.FileTime.fromMillis(0);
        }
    }

    record Settings(
            boolean enabled,
            Duration interval,
            Duration initialDelay,
            int retention
    ) {

        private static final Settings DISABLED = new Settings(
                false,
                Duration.ofHours(24),
                Duration.ofMinutes(5),
                7
        );

        Settings {
            Objects.requireNonNull(interval, "interval");
            Objects.requireNonNull(initialDelay, "initialDelay");
            if (interval.compareTo(Duration.ofHours(1)) < 0
                    || interval.compareTo(Duration.ofHours(168)) > 0
                    || initialDelay.compareTo(Duration.ofMinutes(1)) < 0
                    || initialDelay.compareTo(Duration.ofMinutes(1_440)) > 0
                    || retention < 1 || retention > 100) {
                throw new IllegalArgumentException("Portable export settings are invalid");
            }
        }
    }
}

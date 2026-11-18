package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.StrictYaml;
import gg.nurmi.survivaltweaks.storage.StorageManager;
import gg.nurmi.survivaltweaks.storage.StorageSnapshot;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Stream;

public final class DiagnosticService implements AutoCloseable {

    private final JavaPlugin plugin;
    private final Path dataFolder;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService executor;
    private final PerformanceGovernor governor;
    private final TaskFailureIsolation taskFailures;
    private final StorageManager storage;
    private final BackupService backups;
    private volatile boolean closed;
    private volatile long generation;

    public DiagnosticService(
            JavaPlugin plugin,
            Clock clock,
            PerformanceGovernor governor,
            TaskFailureIsolation taskFailures,
            StorageManager storage,
            BackupService backups
    ) {
        this(
                plugin,
                clock,
                Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().name("SurvivalTweaks diagnostics").daemon(true).factory()
                ),
                governor,
                taskFailures,
                storage,
                backups
        );
    }

    DiagnosticService(
            JavaPlugin plugin,
            Clock clock,
            ExecutorService executor,
            PerformanceGovernor governor,
            TaskFailureIsolation taskFailures,
            StorageManager storage,
            BackupService backups
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dataFolder = plugin.getDataFolder().toPath();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.governor = governor;
        this.taskFailures = taskFailures;
        this.storage = Objects.requireNonNull(storage, "storage");
        this.backups = Objects.requireNonNull(backups, "backups");
    }

    public boolean run(Consumer<Report> completion) {
        if (closed || !running.compareAndSet(false, true)) {
            return false;
        }
        Snapshot snapshot;
        try {
            snapshot = snapshot();
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
        long runGeneration = ++generation;
        try {
            executor.execute(() -> inspectAsync(snapshot, completion, runGeneration));
        } catch (RejectedExecutionException exception) {
            running.set(false);
            return false;
        }
        return true;
    }

    Report inspect(Snapshot snapshot) {
        ArrayList<Issue> issues = new ArrayList<>();
        ensureActive();
        inspectDataDirectory(issues);
        ensureActive();
        inspectConfiguration(issues);
        ensureActive();
        inspectMessages(issues);
        ensureActive();
        inspectRuntimeHealth(issues);
        ensureActive();
        DataSummary data = inspectSqlStorage(snapshot, issues);
        ensureActive();
        int backups = inspectBackups(issues);
        long errors = issues.stream().filter(issue -> issue.severity() == Severity.ERROR).count();
        long warnings = issues.size() - errors;
        return new Report(
                Math.toIntExact(errors),
                Math.toIntExact(warnings),
                data.profiles(),
                data.locks(),
                data.deaths(),
                backups,
                List.copyOf(issues)
        );
    }

    private DataSummary inspectSqlStorage(Snapshot runtime, List<Issue> issues) {
        // The integrity check already reads every table, so reuse its snapshot
        // rather than exporting the whole database a second time.
        var inspection = storage.inspect();
        var verification = inspection.verification();
        if (!verification.healthy()) {
            issues.add(new Issue(
                    Severity.ERROR,
                    "SQL storage integrity check failed: "
                            + String.join("; ", verification.problems())
            ));
        }
        if (inspection.snapshot().isEmpty()) {
            return new DataSummary(0, 0, 0);
        }
        try {
            StorageSnapshot data = inspection.snapshot().get();
            data.profiles().forEach(profile -> profile.homes().forEach(home -> {
                if (!loaded(home.worldId(), home.worldName(), runtime)) {
                    issues.add(new Issue(
                            Severity.WARNING,
                            "Profile " + profile.uniqueId() + " home '" + home.name()
                                    + "' references an unloaded world."
                    ));
                }
            }));
            data.locks().forEach(lock -> lock.blocks().forEach(block -> {
                if (!runtime.worldIds().contains(block.worldId())) {
                    issues.add(new Issue(
                            Severity.WARNING,
                            "Lock " + lock.id() + " references unloaded world "
                                    + block.worldId() + "."
                    ));
                }
            }));
            data.deathMarkers().forEach(marker -> {
                if (!loaded(marker.worldId(), marker.worldName(), runtime)) {
                    issues.add(new Issue(
                            Severity.WARNING,
                            "Death marker for " + marker.playerId()
                                    + " references an unloaded world."
                    ));
                }
                if (marker.expired(runtime.now())) {
                    issues.add(new Issue(
                            Severity.WARNING,
                            "Expired death marker for " + marker.playerId()
                                    + " is still persisted."
                    ));
                }
            });
            return new DataSummary(
                    data.profiles().size(),
                    data.locks().size(),
                    data.deathMarkers().size()
            );
        } catch (Exception exception) {
            issues.add(new Issue(
                    Severity.ERROR,
                    "Could not inspect SQL storage: " + reason(exception)
            ));
            return new DataSummary(0, 0, 0);
        }
    }

    private void inspectAsync(
            Snapshot snapshot,
            Consumer<Report> completion,
            long runGeneration
    ) {
        Report report;
        try {
            report = inspect(snapshot);
        } catch (CancellationException exception) {
            finishCancelled(runGeneration);
            return;
        } catch (RuntimeException exception) {
            report = new Report(
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    List.of(new Issue(Severity.ERROR, "Diagnostic scan failed: " + reason(exception)))
            );
        }
        if (closed || runGeneration != generation) {
            finishCancelled(runGeneration);
            return;
        }

        Report completed = report;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (closed || runGeneration != generation) {
                    return;
                }
                running.set(false);
                completion.accept(completed);
            });
        } catch (RuntimeException exception) {
            running.set(false);
            if (!closed) {
                plugin.getLogger().log(Level.WARNING, "Could not deliver diagnostic results", exception);
            }
        }
    }

    private void finishCancelled(long runGeneration) {
        if (runGeneration == generation) {
            running.set(false);
        }
    }

    private void ensureActive() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Diagnostic scan interrupted");
        }
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        running.set(false);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Diagnostic worker did not stop within five seconds.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Interrupted while waiting for the diagnostic worker to stop.");
        }
    }

    private Snapshot snapshot() {
        Set<UUID> worldIds = plugin.getServer().getWorlds().stream()
                .map(org.bukkit.World::getUID)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> worldNames = plugin.getServer().getWorlds().stream()
                .map(world -> world.getName().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new Snapshot(worldIds, worldNames, clock.instant());
    }

    private void inspectDataDirectory(List<Issue> issues) {
        if (!Files.isDirectory(dataFolder)) {
            issues.add(new Issue(Severity.ERROR, "Plugin data directory does not exist."));
        } else if (!Files.isWritable(dataFolder)) {
            issues.add(new Issue(Severity.ERROR, "Plugin data directory is not writable."));
        }
    }

    private void inspectConfiguration(List<Issue> issues) {
        Path config = dataFolder.resolve("config.yml");
        if (Files.notExists(config)) {
            issues.add(new Issue(Severity.ERROR, "config.yml is missing."));
            return;
        }
        try {
            var candidate = ReloadService.parseConfiguration(plugin, StrictYaml.read(config));
            ReloadService.validateConfiguration(candidate, dataFolder);
            storage.verifyReloadConfiguration(candidate);
        } catch (Exception exception) {
            issues.add(new Issue(Severity.ERROR, "config.yml is invalid: " + reason(exception)));
        }
    }

    private void inspectMessages(List<Issue> issues) {
        Path englishFile = dataFolder.resolve("messages_en.yml");
        Path finnishFile = dataFolder.resolve("messages_fi.yml");
        if (Files.notExists(englishFile) || Files.notExists(finnishFile)) {
            issues.add(new Issue(Severity.ERROR, "One or both language catalogs are missing."));
            return;
        }
        try {
            YamlConfiguration english = StrictYaml.load(englishFile);
            YamlConfiguration finnish = StrictYaml.load(finnishFile);
            MessageService.validateCatalog(english, "messages_en.yml");
            MessageService.validateCatalog(finnish, "messages_fi.yml");
            Set<String> englishKeys = stringKeys(english);
            Set<String> finnishKeys = stringKeys(finnish);
            if (!englishKeys.equals(finnishKeys)) {
                issues.add(new Issue(
                        Severity.ERROR,
                        "Finnish and English language catalogs have different message keys."
                ));
            }
        } catch (Exception exception) {
            issues.add(new Issue(Severity.ERROR, "Language catalog validation failed: " + reason(exception)));
        }
    }

    private void inspectRuntimeHealth(List<Issue> issues) {
        if (governor != null) {
            PerformanceGovernor.Snapshot performance = governor.snapshot();
            if (performance.level() != PerformanceGovernor.Level.NORMAL) {
                issues.add(new Issue(
                        Severity.WARNING,
                        "Adaptive performance governor is "
                                + performance.level().name().toLowerCase(Locale.ROOT)
                                + " at " + String.format(Locale.ROOT, "%.1f", performance.mspt())
                                + " MSPT."
                ));
            }
        }
        if (taskFailures != null) {
            taskFailures.recent(Duration.ofMinutes(10)).stream()
                    .limit(5)
                    .forEach(failure -> issues.add(new Issue(
                            Severity.WARNING,
                            "Recurring subsystem '" + failure.subsystem() + "' failed "
                                    + failure.count() + " time(s); latest problem: "
                                    + failure.problem() + "."
                    )));
        }
    }

    private int inspectBackups(List<Issue> issues) {
        Path directory = dataFolder.resolve("backups");
        if (Files.notExists(directory)) {
            issues.add(new Issue(Severity.WARNING, "No safety backups have been created yet."));
            return 0;
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> archives = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .sorted()
                    .toList();
            int count = archives.size();
            if (count == 0) {
                issues.add(new Issue(Severity.WARNING, "No safety backups have been created yet."));
            }
            for (Path archive : archives) {
                ensureActive();
                String filename = archive.getFileName().toString();
                BackupService.Verification verification = backups.checkRestore(filename);
                if (!verification.valid()) {
                    issues.add(new Issue(
                            verification.inconclusive() ? Severity.WARNING : Severity.ERROR,
                            "Backup " + filename + (verification.inconclusive() ? " could not be verified: " : " is invalid: ")
                                    + verification.problem()
                    ));
                }
            }
            return count;
        } catch (Exception exception) {
            issues.add(new Issue(Severity.WARNING, "Could not inspect safety backups: " + reason(exception)));
            return 0;
        }
    }

    private boolean loaded(UUID worldId, String worldName, Snapshot snapshot) {
        return (worldId != null && snapshot.worldIds().contains(worldId))
                || (worldName != null
                && snapshot.worldNames().contains(worldName.toLowerCase(Locale.ROOT)));
    }

    private Set<String> stringKeys(YamlConfiguration catalog) {
        return catalog.getKeys(true).stream()
                .filter(catalog::isString)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String reason(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    public enum Severity {
        ERROR,
        WARNING
    }

    public record Issue(Severity severity, String detail) {
    }

    public record Report(
            int errors,
            int warnings,
            int profiles,
            int locks,
            int deathMarkers,
            int backups,
            List<Issue> issues
    ) {

        public boolean healthy() {
            return errors == 0 && warnings == 0;
        }
    }

    record Snapshot(
            Set<UUID> worldIds,
            Set<String> worldNames,
            Instant now
    ) {
    }

    private record DataSummary(int profiles, int locks, int deaths) {
    }
}

package gg.nurmi.survivaltweaks.storage;

import org.bukkit.configuration.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

public final class StorageManager implements AutoCloseable {

    private final Path dataFolder;
    private final Logger logger;
    private final StorageConfiguration configuration;
    private final StorageStateStore stateStore;
    private final SqlStorage storage;
    private final StorageStateStore.State state;
    private final ImportResult startupImport;

    private StorageManager(
            Path dataFolder,
            Logger logger,
            StorageConfiguration configuration,
            StorageStateStore stateStore,
            SqlStorage storage,
            StorageStateStore.State state,
            ImportResult startupImport
    ) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.configuration = configuration;
        this.stateStore = stateStore;
        this.storage = storage;
        this.state = state;
        this.startupImport = startupImport;
    }

    public static StorageManager open(
            Configuration config,
            Path dataFolder,
            Logger logger,
            Function<String, UUID> worldResolver
    ) throws IOException {
        Path normalized = Objects.requireNonNull(dataFolder, "dataFolder")
                .toAbsolutePath()
                .normalize();
        StorageConfiguration configured = StorageConfiguration.load(config, normalized);
        StorageStateStore states = new StorageStateStore(normalized);
        StorageStateStore.State previous = states.loadState().orElse(null);
        StorageStateStore.Migration migration = states.loadMigration().orElse(null);
        if (migration != null) {
            return migrate(
                    normalized,
                    logger,
                    worldResolver,
                    configured,
                    states,
                    previous,
                    migration
            );
        }

        if (previous != null && previous.backend() != configured.backend()) {
            throw new IOException(
                    "Storage is pinned to " + previous.backend().key()
                            + "; stage an explicit storage migration before selecting "
                            + configured.backend().key()
            );
        }
        if (previous != null
                && !previous.endpointFingerprint().equals(configured.endpointFingerprint())) {
            throw new IOException(
                    "The configured storage endpoint differs from the pinned endpoint; "
                            + "use the storage migration command instead of changing it directly"
            );
        }

        SqlStorage storage = new SqlStorage(configured, logger);
        try {
            UUID instanceId = previous == null
                    ? existingOrNewInstance(storage)
                    : previous.instanceId();
            storage.ensureInstanceId(instanceId);
            ImportResult imported = importLegacyIfNeeded(
                    normalized,
                    logger,
                    worldResolver,
                    storage,
                    instanceId,
                    previous == null
            );
            StorageStateStore.State active = new StorageStateStore.State(
                    configured.backend(),
                    configured.endpointFingerprint(),
                    instanceId
            );
            states.saveState(active);
            return new StorageManager(
                    normalized,
                    logger,
                    configured,
                    states,
                    storage,
                    active,
                    imported
            );
        } catch (Exception exception) {
            storage.close();
            if (exception instanceof IOException io) {
                throw io;
            }
            throw new IOException("Could not open SurvivalTweaks storage", exception);
        }
    }

    public SqlStorage store() {
        return storage;
    }

    public StorageBackend backend() {
        return state.backend();
    }

    public ImportResult startupImport() {
        return startupImport;
    }

    public SqlStorage.Status status() {
        return storage.status();
    }

    public SqlStorage.Verification verify() {
        return storage.verify();
    }

    public StorageSnapshot exportSnapshot() throws IOException {
        return storage.exportSnapshot();
    }

    public ExportResult exportPortable() throws IOException {
        StorageSnapshot snapshot = storage.exportSnapshot();
        String checksum = StorageChecksum.calculate(snapshot);
        Path exports = dataFolder.resolve("storage-exports");
        Files.createDirectories(exports);
        String timestamp = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Path target = exports.resolve("SurvivalTweaks-storage-" + timestamp + ".db");
        int suffix = 1;
        while (Files.exists(target)) {
            target = exports.resolve(
                    "SurvivalTweaks-storage-" + timestamp + "-" + suffix++ + ".db"
            );
        }
        StorageConfiguration portableConfiguration = new StorageConfiguration(
                StorageBackend.SQLITE,
                target,
                "",
                5432,
                "",
                "",
                "",
                false,
                1,
                5_000
        );
        try (SqlStorage portable = new SqlStorage(portableConfiguration, logger)) {
            portable.replaceAll(snapshot, state.instanceId());
            portable.checkpoint();
            StorageSnapshot verification = portable.exportSnapshot();
            if (!snapshot.counts().equals(verification.counts())
                    || !checksum.equals(StorageChecksum.calculate(verification))) {
                throw new IOException("Portable storage export verification failed");
            }
        }
        return new ExportResult(target, snapshot.counts(), checksum);
    }

    public TestResult testBackend(StorageBackend target) throws IOException {
        StorageConfiguration tested = configuration.forBackend(target);
        long started = System.nanoTime();
        try (SqlStorage candidate = new SqlStorage(tested, logger)) {
            SqlStorage.Verification verification = candidate.verify();
            if (!verification.healthy()) {
                throw new IOException(String.join("; ", verification.problems()));
            }
            return new TestResult(
                    target,
                    candidate.isEmpty(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis()
            );
        }
    }

    public MigrationPlan stageMigration(StorageBackend target)
            throws IOException {
        Objects.requireNonNull(target, "target");
        if (target == backend()) {
            throw new IOException("Storage already uses " + target.key());
        }
        StorageConfiguration targetConfiguration = configuration.forBackend(target);
        try (SqlStorage destination = new SqlStorage(targetConfiguration, logger)) {
            if (!destination.isEmpty()) {
                throw new IOException(
                        "The destination database is not empty; refusing to overwrite it"
                );
            }
            SqlStorage.Verification verification = destination.verify();
            if (!verification.healthy()) {
                throw new IOException(
                        "Destination verification failed: "
                                + String.join("; ", verification.problems())
                );
            }
        }
        StorageStateStore.Migration migration = new StorageStateStore.Migration(
                UUID.randomUUID(),
                backend(),
                target,
                targetConfiguration.endpointFingerprint()
        );
        stateStore.stage(migration);
        return new MigrationPlan(
                migration.id(),
                migration.source(),
                migration.target()
        );
    }

    public Path dataFolder() {
        return dataFolder;
    }

    public SqlStorage.SnapshotLease acquireSnapshotLease() throws IOException {
        return storage.acquireSnapshotLease();
    }

    @Override
    public void close() {
        try {
            storage.checkpoint();
        } catch (IOException exception) {
            logger.warning("Could not checkpoint storage during shutdown: " + exception.getMessage());
        }
        storage.close();
    }

    private static StorageManager migrate(
            Path dataFolder,
            Logger logger,
            Function<String, UUID> worldResolver,
            StorageConfiguration configured,
            StorageStateStore states,
            StorageStateStore.State previous,
            StorageStateStore.Migration migration
    ) throws IOException {
        if (previous == null || previous.backend() != migration.source()) {
            throw new IOException("Staged storage migration does not match active storage state");
        }
        if (configured.backend() != migration.target()) {
            throw new IOException(
                    "storage.backend must be " + migration.target().key()
                            + " to complete the staged migration"
            );
        }
        StorageConfiguration sourceConfiguration =
                configured.forBackend(migration.source());
        StorageConfiguration targetConfiguration =
                configured.forBackend(migration.target());
        if (!sourceConfiguration.endpointFingerprint().equals(previous.endpointFingerprint())) {
            throw new IOException("Source storage endpoint no longer matches the pinned endpoint");
        }
        if (!targetConfiguration.endpointFingerprint().equals(migration.targetFingerprint())) {
            throw new IOException("Target storage configuration changed after migration was staged");
        }

        SqlStorage source = new SqlStorage(sourceConfiguration, logger);
        SqlStorage target = new SqlStorage(targetConfiguration, logger);
        boolean keepTarget = false;
        boolean targetWasEmpty = false;
        try {
            source.ensureInstanceId(previous.instanceId());
            StorageSnapshot snapshot = source.exportSnapshot();
            String sourceChecksum = StorageChecksum.calculate(snapshot);
            if (target.isEmpty()) {
                targetWasEmpty = true;
                target.replaceAll(snapshot, previous.instanceId());
            } else if (!target.instanceId().equals(previous.instanceId())) {
                throw new IOException(
                        "Migration destination contains unrelated data; "
                                + "source remains authoritative"
                );
            }
            StorageSnapshot imported = target.exportSnapshot();
            String targetChecksum = StorageChecksum.calculate(imported);
            if (!snapshot.counts().equals(imported.counts())
                    || !sourceChecksum.equals(targetChecksum)) {
                throw new IOException(
                        "Migration verification failed; source remains authoritative"
                );
            }
            SqlStorage.Verification verification = target.verify();
            if (!verification.healthy()) {
                throw new IOException(
                        "Migration integrity check failed: "
                                + String.join("; ", verification.problems())
                );
            }
            StorageStateStore.State active = new StorageStateStore.State(
                    migration.target(),
                    migration.targetFingerprint(),
                    previous.instanceId()
            );
            states.saveState(active);
            states.completeMigration();
            keepTarget = true;
            logger.info(
                    "Completed storage migration " + migration.id()
                            + " from " + migration.source().key()
                            + " to " + migration.target().key()
                            + " (" + snapshot.counts().total() + " logical records)"
            );
            return new StorageManager(
                    dataFolder,
                    logger,
                    targetConfiguration,
                    states,
                    target,
                    active,
                    new ImportResult(false, snapshot.counts(), targetChecksum, Instant.now())
            );
        } finally {
            source.close();
            if (!keepTarget) {
                if (targetWasEmpty) {
                    try {
                        target.clearData(previous.instanceId());
                    } catch (IOException cleanupFailure) {
                        logger.severe(
                                "Could not clean a failed migration destination: "
                                        + cleanupFailure.getMessage()
                        );
                    }
                }
                target.close();
            }
        }
    }

    private static UUID existingOrNewInstance(SqlStorage storage) {
        try {
            return storage.instanceId();
        } catch (IOException ignored) {
            return UUID.randomUUID();
        }
    }

    private static ImportResult importLegacyIfNeeded(
            Path dataFolder,
            Logger logger,
            Function<String, UUID> worldResolver,
            SqlStorage storage,
            UUID instanceId,
            boolean recoverUnpinned
    ) throws IOException {
        LegacyYamlImporter importer =
                new LegacyYamlImporter(dataFolder, logger, worldResolver);
        boolean hasLegacy = importer.hasData();
        if (!hasLegacy || (!storage.isEmpty() && !recoverUnpinned)) {
            return new ImportResult(
                    false,
                    new StorageSnapshot.Counts(0, 0, 0, 0, 0, 0, 0, 0),
                    "",
                    Instant.now()
            );
        }
        StorageSnapshot legacy = importer.read();
        String expected = StorageChecksum.calculate(legacy);
        if (!storage.isEmpty()) {
            StorageSnapshot existing = storage.exportSnapshot();
            String actual = StorageChecksum.calculate(existing);
            if (!legacy.counts().equals(existing.counts()) || !expected.equals(actual)) {
                throw new IOException(
                        "The unpinned SQL database contains data that does not match "
                                + "the preserved YAML files; refusing to choose either copy"
                );
            }
            logger.info(
                    "Recovered a verified legacy YAML import into " + storage.backend().key()
                            + " after an interrupted first startup ("
                            + existing.counts().total() + " logical records)"
            );
            return new ImportResult(true, existing.counts(), actual, Instant.now());
        }
        storage.replaceAll(legacy, instanceId);
        StorageSnapshot imported = storage.exportSnapshot();
        String actual = StorageChecksum.calculate(imported);
        if (!legacy.counts().equals(imported.counts()) || !expected.equals(actual)) {
            try {
                storage.clearData(instanceId);
            } catch (IOException cleanupFailure) {
                logger.severe(
                        "Could not clean a rejected legacy import: "
                                + cleanupFailure.getMessage()
                );
            }
            throw new IOException(
                    "Legacy YAML import verification failed; original files were preserved"
            );
        }
        logger.info(
                "Imported legacy YAML storage into " + storage.backend().key()
                        + " (" + imported.counts().total() + " logical records); "
                        + "original YAML files were preserved"
        );
        return new ImportResult(true, imported.counts(), actual, Instant.now());
    }

    public record ImportResult(
            boolean imported,
            StorageSnapshot.Counts counts,
            String checksum,
            Instant completedAt
    ) {
    }

    public record ExportResult(
            Path file,
            StorageSnapshot.Counts counts,
            String checksum
    ) {
    }

    public record TestResult(StorageBackend backend, boolean empty, long latencyMillis) {
    }

    public record MigrationPlan(
            UUID id,
            StorageBackend source,
            StorageBackend target
    ) {
    }
}

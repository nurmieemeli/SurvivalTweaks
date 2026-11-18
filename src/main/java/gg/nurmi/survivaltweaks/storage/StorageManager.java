package gg.nurmi.survivaltweaks.storage;

import org.bukkit.configuration.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

public final class StorageManager implements AutoCloseable {

    private static final Duration PERSISTENCE_FLUSH_TIMEOUT = Duration.ofSeconds(30);

    private final Path dataFolder;
    private final Logger logger;
    private final StorageConfiguration configuration;
    private final StorageStateStore stateStore;
    private final SqlStorage storage;
    private final StorageStateStore.State state;
    private final boolean importedLegacyAtStartup;
    private volatile PersistenceBarrier persistenceBarrier = timeout -> {
    };

    private StorageManager(
            Path dataFolder,
            Logger logger,
            StorageConfiguration configuration,
            StorageStateStore stateStore,
            SqlStorage storage,
            StorageStateStore.State state,
            boolean importedLegacyAtStartup
    ) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.configuration = configuration;
        this.stateStore = stateStore;
        this.storage = storage;
        this.state = state;
        this.importedLegacyAtStartup = importedLegacyAtStartup;
    }

    public static StorageManager open(
            Configuration config,
            Path dataFolder,
            Logger logger,
            Function<String, UUID> worldResolver
    ) throws IOException {
        return open(config, dataFolder, logger, worldResolver, false);
    }

    /** Opens files installed by a verified, explicitly staged restore. */
    public static StorageManager openRestored(Configuration config, Path dataFolder, Logger logger,
                                              Function<String, UUID> worldResolver) throws IOException {
        return open(config, dataFolder, logger, worldResolver, true);
    }

    private static StorageManager open(
            Configuration config,
            Path dataFolder,
            Logger logger,
            Function<String, UUID> worldResolver,
            boolean restoring
    ) throws IOException {
        Path normalized = Objects.requireNonNull(dataFolder, "dataFolder")
                .toAbsolutePath()
                .normalize();
        StorageConfiguration configured = StorageConfiguration.load(config, normalized);
        StorageStateStore states = new StorageStateStore(normalized);
        StorageStateStore.State previous = states.loadState().orElse(null);
        StorageStateStore.Migration migration = states.loadMigration().orElse(null);
        StartupAction action = startupAction(configured, previous, migration,
                restoring && Files.isRegularFile(configured.sqliteFile()));
        if (action == StartupAction.RECOVER_COMPLETED_MIGRATION) {
            return recoverCompletedMigration(normalized, logger, configured, states, previous, migration);
        }
        if (action == StartupAction.MIGRATE) {
            return migrate(normalized, logger, configured, states, previous, migration);
        }
        if (action == StartupAction.DISCARD_STAGED_MIGRATION) {
            states.completeMigration();
            logger.warning("Discarded an incomplete storage migration request. The source remains active.");
        }

        if (previous != null) {
            requirePinnedSqlite(configured, previous.instanceId());
        }
        SqlStorage storage = new SqlStorage(configured, logger);
        try {
            storage.acquireInstanceLease();
            UUID instanceId;
            if (previous == null) {
                instanceId = storage.ensureInstanceId(existingOrNewInstance(storage));
            } else {
                instanceId = requireInstance(storage.instanceId(), previous.instanceId());
            }
            boolean imported = importLegacyIfNeeded(
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

    /** Verifies the local database in an extracted archive without opening a live endpoint. */
    public static StorageConfiguration verifyRestore(Configuration config, Path staged, Path restoreFolder,
                                                     Logger logger, Function<String, UUID> worldResolver) throws IOException {
        StorageConfiguration configured = StorageConfiguration.load(config, staged);
        StorageStateStore states = new StorageStateStore(staged);
        StorageStateStore.State state = states.loadState().orElse(null);
        StorageStateStore.Migration migration = states.loadMigration().orElse(null);
        Path database = configured.sqliteFile();
        try (var files = Files.list(staged)) {
            if (files.anyMatch(file -> StorageConfiguration.validSqliteFilename(file.getFileName().toString())
                    && !file.equals(database))) {
                throw new IOException("Archive contains a database other than the one named in config.yml");
            }
        }
        boolean pinnedSqlite = state != null && state.backend() == StorageBackend.SQLITE;
        boolean hasDatabase = Files.isRegularFile(database);
        // Fingerprints describe the installed endpoint. Read database contents only from the archive.
        StartupAction action = startupAction(StorageConfiguration.load(config, restoreFolder), state, migration, hasDatabase);
        if (action == StartupAction.MIGRATE || action == StartupAction.DISCARD_STAGED_MIGRATION) {
            throw new IOException("Archive contains a pending storage migration");
        }
        if (pinnedSqlite && !hasDatabase) {
            throw new IOException("Archive is missing its pinned SQLite database " + database.getFileName());
        }
        StorageSnapshot databaseSnapshot = hasDatabase
                ? SqlStorage.verifySqliteRestore(configured.forBackend(StorageBackend.SQLITE),
                        pinnedSqlite ? state.instanceId() : null, logger)
                : null;
        if (state == null) {
            LegacyYamlImporter importer = new LegacyYamlImporter(staged, logger, worldResolver);
            StorageSnapshot legacy = importer.hasData() ? importer.read() : null;
            boolean hasLegacyData = legacy != null && !legacy.empty();
            if (!hasDatabase && !hasLegacyData) {
                throw new IOException("Archive contains no SQLite database or usable legacy player data");
            }
            if (hasLegacyData && configured.backend() == StorageBackend.SQLITE
                    && databaseSnapshot != null && !databaseSnapshot.empty()) {
                requireMatchingLegacy(legacy, databaseSnapshot);
            }
        }
        return configured;
    }

    public SqlStorage store() {
        return storage;
    }

    public StorageBackend backend() {
        return state.backend();
    }

    /** Resolves the local storage pin without opening SQLite or altering its sidecars. */
    public static Optional<Path> findPinnedSqliteFile(Path dataFolder) throws IOException {
        Path directory = dataFolder.toAbsolutePath().normalize();
        StorageStateStore.State pinned = new StorageStateStore(directory).loadState().orElse(null);
        if (pinned == null || pinned.backend() != StorageBackend.SQLITE) {
            return Optional.empty();
        }
        try (var files = Files.list(directory)) {
            return files.filter(file -> StorageConfiguration.validSqliteFilename(file.getFileName().toString()))
                    .filter(file -> StorageConfiguration.matchesSqliteEndpoint(file, pinned.endpointFingerprint()))
                    .findFirst();
        }
    }

    public void verifyReloadConfiguration(Configuration config) {
        StorageConfiguration requested = StorageConfiguration.load(config, dataFolder);
        if (backend() == StorageBackend.SQLITE
                && !requested.forBackend(StorageBackend.SQLITE).endpointFingerprint().equals(state.endpointFingerprint())) {
            throw new IllegalArgumentException("Keep the pinned SQLite filename while reloading; use a backup restore to switch SQLite files");
        }
    }

    boolean importedLegacyAtStartup() {
        return importedLegacyAtStartup;
    }

    public SqlStorage.Status status() {
        return storage.status();
    }

    public SqlStorage.Verification verify() {
        return storage.verify();
    }

    /**
     * Verifies the database and returns the snapshot the check already read.
     * Queued writes are drained first so callers see the same data a snapshot
     * export would, and a failed drain becomes a reported problem rather than
     * a silently stale reading.
     */
    public SqlStorage.Inspection inspect() {
        String drainFailure = "";
        try {
            flushPendingWrites();
        } catch (IOException exception) {
            drainFailure = "Could not drain pending writes before inspecting storage: "
                    + Objects.requireNonNullElse(exception.getMessage(), "unknown failure");
        }
        SqlStorage.Inspection inspection = storage.inspect();
        if (drainFailure.isEmpty()) {
            return inspection;
        }
        java.util.List<String> problems = new java.util.ArrayList<>();
        problems.add(drainFailure);
        problems.addAll(inspection.verification().problems());
        return new SqlStorage.Inspection(
                new SqlStorage.Verification(false, java.util.List.copyOf(problems)),
                inspection.snapshot()
        );
    }

    public SqlStorage.MaintenancePreview previewMaintenance(int mailPurgeInactiveDays) throws IOException {
        return storage.maintenancePreview(Instant.now(), mailPurgeInactiveDays);
    }

    public synchronized MaintenanceResult maintain(int mailPurgeInactiveDays) throws IOException {
        return maintain(Instant.now(), mailPurgeInactiveDays);
    }

    public synchronized MaintenanceResult maintain(
            Instant now,
            int mailPurgeInactiveDays
    ) throws IOException {
        return maintain(now, mailPurgeInactiveDays, () -> {});
    }

    public synchronized MaintenanceResult maintain(
            Instant now, int mailPurgeInactiveDays, MaintenancePreparation preparation
    ) throws IOException {
        Objects.requireNonNull(now, "now");
        ExportResult safetyExport = exportPortable("maintenance");
        preparation.prepare();
        flushPendingWrites();
        SqlStorage.MaintenanceResult result = storage.maintain(now, mailPurgeInactiveDays);
        return new MaintenanceResult(result.preview(), result.removed(), safetyExport, now);
    }

    public StorageSnapshot exportSnapshot() throws IOException {
        flushPendingWrites();
        return storage.exportSnapshot();
    }

    public synchronized ExportResult exportPortable() throws IOException {
        return exportPortableTo(dataFolder.resolve("storage-exports"));
    }

    public synchronized ExportResult exportPortable(String collection) throws IOException {
        if (collection == null || !collection.matches("[a-z0-9-]{1,32}")) {
            throw new IllegalArgumentException("Portable export collection name is invalid");
        }
        return exportPortableTo(dataFolder.resolve("storage-exports").resolve(collection));
    }

    private ExportResult exportPortableTo(Path exports) throws IOException {
        flushPendingWrites();
        StorageSnapshot snapshot = storage.exportSnapshot();
        String checksum = StorageChecksum.calculate(snapshot);
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
        Path temporary = Files.createTempFile(exports, ".storage-export-", ".tmp");
        StorageConfiguration portableConfiguration = new StorageConfiguration(
                StorageBackend.SQLITE,
                temporary,
                "",
                5432,
                "",
                "survivaltweaks",
                "",
                "",
                false,
                "disable",
                "verify-identity",
                1,
                5_000,
                30,
                30
        );
        try {
            try (SqlStorage portable = new SqlStorage(portableConfiguration, logger)) {
                portable.replaceAll(snapshot, state.instanceId());
                portable.checkpoint();
                StorageSnapshot verification = portable.exportSnapshot();
                if (!snapshot.counts().equals(verification.counts())
                        || !checksum.equals(StorageChecksum.calculate(verification))) {
                    throw new IOException("Portable storage export verification failed");
                }
            }
            publishAtomically(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(Path.of(temporary + "-wal"));
            Files.deleteIfExists(Path.of(temporary + "-shm"));
        }
        return new ExportResult(target, snapshot.counts(), checksum);
    }

    public TestResult testBackend(StorageConfiguration tested) throws IOException {
        long started = System.nanoTime();
        try (SqlStorage candidate = new SqlStorage(tested, logger)) {
            SqlStorage.Verification verification = candidate.verifyUninitialized();
            if (!verification.healthy()) {
                throw new IOException(String.join("; ", verification.problems()));
            }
            return new TestResult(
                    tested.backend(),
                    candidate.isEmpty(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis()
            );
        }
    }

    public boolean hasPendingMigration() {
        return stateStore.hasPendingMigration();
    }

    public void cancelStagedMigration(MigrationPlan plan) throws IOException {
        if (stateStore.loadMigration().filter(staged -> staged.id().equals(plan.id())).isPresent()) {
            stateStore.completeMigration();
        }
    }

    public MigrationPlan stageMigration(StorageConfiguration configured, StorageBackend target)
            throws IOException {
        if (target == backend()) {
            throw new IOException("Storage already uses " + target.key());
        }
        if (!endpointMatches(configured.forBackend(backend()), state.endpointFingerprint())) {
            throw new IOException("The configured migration source differs from the active database");
        }
        StorageConfiguration targetConfiguration = configured.forBackend(target);
        try (SqlStorage destination = new SqlStorage(targetConfiguration, logger)) {
            destination.acquireInstanceLease();
            if (!destination.isEmpty()) {
                throw new IOException(
                        "The destination database is not empty; refusing to overwrite it"
                );
            }
            SqlStorage.Verification verification = destination.verifyUninitialized();
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
        flushPendingWrites();
        if (configuration.backend() != StorageBackend.SQLITE) {
            return () -> {
            };
        }
        return storage.acquireSnapshotLease();
    }

    public void persistenceBarrier(PersistenceBarrier updatedBarrier) {
        persistenceBarrier = Objects.requireNonNull(updatedBarrier, "updatedBarrier");
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

    private static StartupAction startupAction(
            StorageConfiguration configured,
            StorageStateStore.State previous,
            StorageStateStore.Migration migration,
            boolean restoringSqliteDatabase
    ) throws IOException {
        if (migration != null) {
            if (previous != null && previous.backend() == migration.target()
                    && configured.backend() == migration.target()
                    && endpointMatches(configured, previous.endpointFingerprint())
                    && endpointMatches(configured, migration.targetFingerprint())) {
                return StartupAction.RECOVER_COMPLETED_MIGRATION;
            }
            if (previous != null && previous.backend() == migration.source()
                    && configured.backend() == migration.source()
                    && endpointMatches(configured, previous.endpointFingerprint())) {
                return StartupAction.DISCARD_STAGED_MIGRATION;
            }
            validateMigration(configured, previous, migration);
            return StartupAction.MIGRATE;
        }
        if (previous != null && previous.backend() != configured.backend()) {
            throw new IOException("Storage is pinned to " + previous.backend().key()
                    + "; stage an explicit storage migration before selecting " + configured.backend().key());
        }
        if (previous != null && !endpointMatches(configured, previous.endpointFingerprint())) {
            if (configured.backend() == StorageBackend.SQLITE) {
                // Old backups pinned an absolute path that could belong to another data directory.
                if (restoringSqliteDatabase
                        && !previous.endpointFingerprint().startsWith(StorageConfiguration.SQLITE_FILE_FINGERPRINT_PREFIX)) {
                    return StartupAction.OPEN;
                }
                throw new IOException("The configured SQLite file differs from the pinned endpoint; "
                        + "use a verified backup restore to switch SQLite files");
            }
            throw new IOException("The configured storage endpoint differs from the pinned endpoint; "
                    + "use the storage migration command instead of changing it directly");
        }
        return StartupAction.OPEN;
    }

    private enum StartupAction {
        OPEN, DISCARD_STAGED_MIGRATION, MIGRATE, RECOVER_COMPLETED_MIGRATION
    }

    private static void validateMigration(StorageConfiguration configured, StorageStateStore.State previous,
                                          StorageStateStore.Migration migration) throws IOException {
        if (previous == null || previous.backend() != migration.source()) {
            throw new IOException("Staged storage migration does not match active storage state");
        }
        if (configured.backend() != migration.target()) {
            throw new IOException(
                    "storage.backend must be " + migration.target().key()
                            + " to complete the staged migration"
            );
        }
        if (!endpointMatches(configured.forBackend(migration.source()), previous.endpointFingerprint())) {
            throw new IOException("Source storage endpoint no longer matches the pinned endpoint");
        }
        if (!endpointMatches(configured.forBackend(migration.target()), migration.targetFingerprint())) {
            throw new IOException("Target storage configuration changed after migration was staged");
        }
    }

    private static StorageManager migrate(Path dataFolder, Logger logger, StorageConfiguration configured,
                                          StorageStateStore states, StorageStateStore.State previous,
                                          StorageStateStore.Migration migration) throws IOException {
        StorageConfiguration sourceConfiguration = configured.forBackend(migration.source());
        StorageConfiguration targetConfiguration = configured.forBackend(migration.target());
        requirePinnedSqlite(sourceConfiguration, previous.instanceId());
        SqlStorage target = new SqlStorage(targetConfiguration, logger);
        boolean keepTarget = false;
        boolean targetWasEmpty = false;
        try (SqlStorage source = new SqlStorage(sourceConfiguration, logger)) {
            source.acquireInstanceLease();
            requireInstance(source.instanceId(), previous.instanceId());
            target.acquireInstanceLease();
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
            SqlStorage.Inspection destination = target.inspect();
            StorageSnapshot imported = destination.snapshot().orElse(null);
            if (imported != null && (!snapshot.counts().equals(imported.counts())
                    || !sourceChecksum.equals(StorageChecksum.calculate(imported)))) {
                throw new IOException(
                        "Migration verification failed; source remains authoritative"
                );
            }
            if (imported == null || !destination.verification().healthy()) {
                throw new IOException(
                        "Migration integrity check failed: "
                                + String.join("; ", destination.verification().problems())
                );
            }
            StorageStateStore.State active = new StorageStateStore.State(
                    migration.target(),
                    targetConfiguration.endpointFingerprint(),
                    previous.instanceId()
            );
            states.saveState(active);
            // Once the active state is switched, the verified target is
            // authoritative and must never enter failed-copy cleanup.
            keepTarget = true;
            try {
                states.completeMigration();
            } catch (IOException cleanupFailure) {
                logger.warning(
                        "Storage migration completed, but its staging marker could not be removed; "
                                + "startup will retry cleanup: " + cleanupFailure.getMessage()
                );
            }
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
                    false
            );
        } finally {
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

    private void flushPendingWrites() throws IOException {
        persistenceBarrier.flush(PERSISTENCE_FLUSH_TIMEOUT);
    }

    private void publishAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void requirePinnedSqlite(StorageConfiguration configuration, UUID expected) throws IOException {
        if (configuration.backend() == StorageBackend.SQLITE) {
            Path file = configuration.sqliteFile();
            if (!Files.isRegularFile(file)) {
                throw new IOException("The pinned SQLite database is missing: " + file);
            }
            requireInstance(SqlStorage.sqliteInstanceId(file), expected);
        }
    }

    private static UUID requireInstance(UUID actual, UUID expected) throws IOException {
        if (!actual.equals(expected)) {
            throw new IOException("The configured database belongs to a different SurvivalTweaks instance");
        }
        return actual;
    }

    private static UUID existingOrNewInstance(SqlStorage storage) {
        try {
            return storage.instanceId();
        } catch (IOException ignored) {
            return UUID.randomUUID();
        }
    }

    private static StorageManager recoverCompletedMigration(
            Path dataFolder,
            Logger logger,
            StorageConfiguration configuration,
            StorageStateStore states,
            StorageStateStore.State previous,
            StorageStateStore.Migration migration
    ) throws IOException {
        requirePinnedSqlite(configuration, previous.instanceId());
        SqlStorage target = new SqlStorage(configuration, logger);
        try {
            target.acquireInstanceLease();
            requireInstance(target.instanceId(), previous.instanceId());
            SqlStorage.Verification verification = target.verify();
            if (!verification.healthy()) {
                throw new IOException(
                        "Interrupted migration target failed verification: "
                                + String.join("; ", verification.problems())
                );
            }
            StorageStateStore.State active = new StorageStateStore.State(
                    migration.target(),
                    configuration.endpointFingerprint(),
                    previous.instanceId()
            );
            states.saveState(active);
            try {
                states.completeMigration();
            } catch (IOException cleanupFailure) {
                logger.warning(
                        "Recovered storage migration, but its staging marker could not be removed; "
                                + "startup will retry cleanup: " + cleanupFailure.getMessage()
                );
            }
            logger.info(
                    "Recovered completed storage migration " + migration.id()
                            + " after an interrupted state-file cleanup"
            );
            return new StorageManager(
                    dataFolder,
                    logger,
                    configuration,
                    states,
                    target,
                    active,
                    false
            );
        } catch (Exception exception) {
            target.close();
            if (exception instanceof IOException io) {
                throw io;
            }
            throw new IOException("Could not recover interrupted storage migration", exception);
        }
    }

    private static boolean endpointMatches(
            StorageConfiguration configuration,
            String fingerprint
    ) {
        if (configuration.backend() == StorageBackend.SQLITE) {
            return StorageConfiguration.matchesSqliteEndpoint(configuration.sqliteFile(), fingerprint);
        }
        boolean supportsLegacy = configuration.backend() == StorageBackend.POSTGRESQL && configuration.postgresqlSchema().equals("public");
        return configuration.endpointFingerprint().equals(fingerprint)
                || (supportsLegacy && configuration.legacyEndpointFingerprint().equals(fingerprint));
    }

    private static boolean importLegacyIfNeeded(
            Path dataFolder,
            Logger logger,
            Function<String, UUID> worldResolver,
            SqlStorage storage,
            UUID instanceId,
            boolean recoverUnpinned
    ) throws IOException {
        LegacyYamlImporter importer =
                new LegacyYamlImporter(dataFolder, logger, worldResolver);
        StorageSnapshot legacy = recoverUnpinned && importer.hasData() ? importer.read() : null;
        if (legacy == null || legacy.empty()) {
            return false;
        }
        if (!storage.isEmpty()) {
            StorageSnapshot existing = storage.exportSnapshot();
            requireMatchingLegacy(legacy, existing);
            logger.info(
                    "Recovered a verified legacy YAML import into " + storage.backend().key()
                            + " after an interrupted first startup ("
                            + existing.counts().total() + " logical records)"
            );
            return true;
        }
        String expected = StorageChecksum.calculate(legacy);
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
        return true;
    }

    private static void requireMatchingLegacy(StorageSnapshot legacy, StorageSnapshot existing) throws IOException {
        String actual = StorageChecksum.calculate(existing);
        if (!legacy.counts().equals(existing.counts()) || !StorageChecksum.calculate(legacy).equals(actual)) {
            throw new IOException("The unpinned SQL database contains data that does not match "
                    + "the preserved YAML files; refusing to choose either copy");
        }
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

    public record MaintenanceResult(
            SqlStorage.MaintenancePreview preview,
            SqlStorage.MaintenancePreview removed,
            ExportResult safetyExport,
            Instant completedAt
    ) {
    }

    @FunctionalInterface
    public interface PersistenceBarrier {

        void flush(Duration timeout) throws IOException;
    }

    @FunctionalInterface
    public interface MaintenancePreparation {
        void prepare() throws IOException;
    }
}

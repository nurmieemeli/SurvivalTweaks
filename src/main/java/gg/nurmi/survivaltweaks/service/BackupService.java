package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.ConfigMigrationService;
import gg.nurmi.survivaltweaks.config.StrictYaml;
import gg.nurmi.survivaltweaks.storage.ProfileStore;
import gg.nurmi.survivaltweaks.storage.SqlStorage;
import gg.nurmi.survivaltweaks.storage.StorageBackend;
import gg.nurmi.survivaltweaks.storage.StorageConfiguration;
import gg.nurmi.survivaltweaks.storage.StorageManager;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class BackupService {

    public static final int DEFAULT_RETENTION = 10;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss-SSS")
            .withLocale(Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    static final List<String> ROOT_FILES = List.of(
            "config.yml",
            "messages_en.yml",
            "messages_fi.yml",
            "locked-containers.yml",
            "death-markers.yml",
            "new-player-spawns.yml",
            "storage-state.yml",
            "storage-migration.yml",
            ProfileRepository.PENDING_LANGUAGES_FILENAME
    );
    private static final String PENDING_RESTORE_FILE = ".restore-pending.zip";
    private static final String PREPARED_RESTORE_PREFIX = ".restore-pending-";
    private static final String RESTORE_ROLLBACK = ".restore-rollback";
    private static final String ABSENT_PATHS = ".absent-paths";
    private static final String MANAGED_PATHS = ".managed-paths";
    private static final String USERDATA = "userdata";
    private static final String USERDATA_PREFIX = USERDATA + "/";
    private static final int MAX_ARCHIVE_ENTRIES = 100_000;
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L;

    private final Path dataFolder;
    private final Path backupFolder;
    private final Clock clock;
    private final Logger logger;
    private final int retention;
    private final Function<String, UUID> legacyWorldResolver;
    private final Object restorePublication = new Object();
    private final Set<Path> preparedRestores = new HashSet<>();
    private boolean restorePreparationsClosed;
    private SnapshotLeaseFactory snapshotLeaseFactory = () -> () -> {
    };
    private String databaseFilename = "survivaltweaks.db";
    private long generation;

    public BackupService(Path dataFolder, Clock clock, Logger logger) {
        this(dataFolder, clock, logger, DEFAULT_RETENTION);
    }

    public BackupService(Path dataFolder, Clock clock, Logger logger, Function<String, UUID> legacyWorldResolver) {
        this(dataFolder, clock, logger, DEFAULT_RETENTION, legacyWorldResolver);
    }

    BackupService(Path dataFolder, Clock clock, Logger logger, int retention) {
        this(dataFolder, clock, logger, retention, name -> null);
    }

    private BackupService(Path dataFolder, Clock clock, Logger logger, int retention,
                          Function<String, UUID> legacyWorldResolver) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder").toAbsolutePath().normalize();
        this.backupFolder = this.dataFolder.resolve("backups");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.legacyWorldResolver = Objects.requireNonNull(legacyWorldResolver, "legacyWorldResolver");
        if (retention < 1 || retention > 100) {
            throw new IllegalArgumentException("retention must be between 1 and 100");
        }
        this.retention = retention;
    }

    public synchronized Optional<Path> create(String reason) throws IOException {
        String normalizedReason = normalizeReason(reason);
        Path target;
        try (SnapshotLease ignored = snapshotLeaseFactory.acquire()) {
            List<Path> sources = sources();
            if (sources.isEmpty()) {
                return Optional.empty();
            }

            Files.createDirectories(backupFolder);
            target = availableTarget(normalizedReason);
            Path temporary = Files.createTempFile(backupFolder, ".backup-", ".tmp");
            try {
                writeArchive(temporary, sources);
                movePath(temporary, target);
                Files.setLastModifiedTime(
                        target,
                        FileTime.fromMillis(clock.instant().toEpochMilli() + generation++)
                );
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        rotate();
        logger.info("Created SurvivalTweaks safety backup " + target.getFileName());
        return Optional.of(target);
    }

    private void createPreRestoreBackup(List<String> managedPaths) throws IOException {
        Set<Path> sqliteFiles = new LinkedHashSet<>();
        sqliteFiles.add(dataFolder.resolve(databaseFilename));
        databaseSidecars(databaseFilename).stream().map(dataFolder::resolve).forEach(sqliteFiles::add);
        try {
            StorageManager.findPinnedSqliteFile(dataFolder).ifPresent(database -> {
                sqliteFiles.add(database);
                databaseSidecars(database.getFileName().toString()).stream().map(dataFolder::resolve)
                        .forEach(sqliteFiles::add);
            });
        } catch (IOException exception) {
            if (archiveAccessFailed(exception)) {
                throw exception;
            }
            logger.warning("Could not read the live storage pin for the pre-restore backup: " + reason(exception));
        }
        managedPaths.stream()
                .filter(path -> StorageConfiguration.validSqliteFilename(path) || isDatabaseSidecar(path))
                .map(dataFolder::resolve)
                .forEach(sqliteFiles::add);
        List<Path> sources = new ArrayList<>(sources());
        List<Path> additional = sqliteFiles.stream()
                .filter(path -> !sources.contains(path) && !Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
                .toList();
        if (!additional.isEmpty()) {
            for (Path path : additional) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Could not preserve pre-restore SQLite file " + path);
                }
            }
            // A restorable ZIP can hold one SQLite database. Opening SQLite can change its sidecars.
            sources.addAll(additional);
            copyPreRestoreFiles(sources, "to preserve the SQLite databases and sidecars together");
            return;
        }
        try {
            create("pre-restore");
        } catch (ArchiveLimitException exception) {
            try (SnapshotLease ignored = snapshotLeaseFactory.acquire()) {
                copyPreRestoreFiles(sources(), "because they exceed the ZIP limit");
            }
        }
    }

    private void copyPreRestoreFiles(List<Path> sources, String reason) throws IOException {
        if (sources.isEmpty()) {
            return;
        }
        Files.createDirectories(backupFolder);
        Path staged = Files.createTempDirectory(backupFolder, ".pre-restore-");
        Path target = backupFolder.resolve(staged.getFileName().toString().substring(1));
        try {
            for (Path source : sources) {
                ensureNotInterrupted();
                Path copy = staged.resolve(dataFolder.relativize(source));
                Files.createDirectories(copy.getParent());
                Files.copy(source, copy);
            }
            for (Path source : sources) {
                ensureNotInterrupted();
                Path relative = dataFolder.relativize(source);
                if (Files.mismatch(source, staged.resolve(relative)) != -1) {
                    throw new IOException("Could not verify pre-restore copy of " + relative);
                }
            }
            movePath(staged, target);
        } finally {
            cleanupTemporary(staged);
        }
        logger.warning("Saved verified pre-restore files in " + target + " " + reason
                + ". Retain this folder until the restore is verified.");
    }

    void createReloadBackup() throws IOException {
        try {
            create("reload");
        } catch (ArchiveLimitException exception) {
            logger.warning("Skipped reload backup: " + exception.getMessage());
        }
    }

    public void createStartupBackup() throws IOException {
        try {
            create("startup");
        } catch (SqlStorage.CheckpointUnavailableException | ArchiveLimitException exception) {
            logger.warning("Skipped startup backup: " + exception.getMessage());
        } catch (FileSystemException exception) {
            Path recovery = dataFolder.resolve(ProfileRepository.PENDING_LANGUAGES_FILENAME);
            if (!recovery.toString().equals(exception.getFile())) {
                throw exception;
            }
            logger.warning("Skipped startup backup because the language recovery file was unavailable: "
                    + exception.getMessage());
        }
    }

    public synchronized SnapshotLease acquireStartupSnapshotLease() throws IOException {
        SqlStorage.checkpointBeforeStartupBackup(dataFolder.resolve(databaseFilename));
        return () -> {
        };
    }

    public synchronized void snapshotLeaseFactory(SnapshotLeaseFactory updatedFactory) {
        snapshotLeaseFactory = Objects.requireNonNull(updatedFactory, "updatedFactory");
    }

    public synchronized void databaseFilename(String updatedFilename) {
        String normalized = Objects.requireNonNull(updatedFilename, "updatedFilename").strip();
        if (!StorageConfiguration.validSqliteFilename(normalized)) {
            throw new IllegalArgumentException("SQLite backup filename is invalid");
        }
        databaseFilename = normalized;
    }

    public synchronized List<ArchiveInfo> archives() throws IOException {
        if (Files.notExists(backupFolder)) {
            return List.of();
        }
        List<ArchiveInfo> archives = new ArrayList<>();
        try (Stream<Path> files = Files.list(backupFolder)) {
            for (Path path : files.filter(BackupService::isRegularZip).toList()) {
                archives.add(new ArchiveInfo(path.getFileName().toString(), Files.size(path),
                        Files.getLastModifiedTime(path).toInstant()));
            }
        }
        archives.sort(Comparator.comparing(ArchiveInfo::modified).thenComparing(ArchiveInfo::filename).reversed());
        return List.copyOf(archives);
    }

    public synchronized Verification verify(String filename) {
        return verify(filename, true);
    }

    public synchronized Verification checkRestore(String filename) {
        return verify(filename, false);
    }

    private Verification verify(String filename, boolean includeDigest) {
        try {
            return verifyRestoreArchive(resolveArchive(filename), includeDigest);
        } catch (Exception exception) {
            return Verification.invalid(reason(exception));
        }
    }

    public boolean hasPendingRestore() {
        return Files.isRegularFile(pendingRestorePath());
    }

    public synchronized PreparedRestore prepareRestore(String filename) throws IOException {
        if (Files.exists(pendingRestorePath())) {
            throw new IOException("A backup restore is already pending");
        }
        Path archive = resolveArchive(filename);
        Path temporary = Files.createTempFile(dataFolder, PREPARED_RESTORE_PREFIX, ".tmp");
        try {
            Files.copy(archive, temporary, StandardCopyOption.REPLACE_EXISTING);
            Verification verification = verifyRestoreArchive(temporary, true);
            if (!verification.valid()) {
                throw new IOException("Backup verification failed: " + verification.problem());
            }
            synchronized (restorePublication) {
                if (restorePreparationsClosed) {
                    throw new IOException("Backup restore preparation was cancelled during shutdown");
                }
                preparedRestores.add(temporary);
            }
            return new PreparedRestore(archive.getFileName().toString(), temporary, verification);
        } catch (IOException | RuntimeException exception) {
            discardPreparation(temporary);
            throw exception;
        }
    }

    public void closeRestorePreparations() {
        List<Path> abandoned;
        synchronized (restorePublication) {
            restorePreparationsClosed = true;
            abandoned = List.copyOf(preparedRestores);
            preparedRestores.clear();
        }
        abandoned.forEach(this::discardPreparation);
    }

    public void discardAbandonedRestoreWork() {
        discardAbandonedRestoreWork(dataFolder, ".restore-*", "\\.restore-pending-[0-9]+\\.tmp",
                "\\.restore-(verify|stage|prepare|cleanup)-[0-9]+");
        discardAbandonedRestoreWork(backupFolder, "{.backup-*,.pre-restore-*}", "\\.backup-[0-9]+\\.tmp",
                "\\.pre-restore-[0-9]+");
    }

    private void discardAbandonedRestoreWork(Path folder, String glob, String filePattern, String directoryPattern) {
        if (Files.notExists(folder)) {
            return;
        }
        try (var files = Files.newDirectoryStream(folder, glob)) {
            if (!folder.toRealPath().equals(dataFolder.toRealPath().resolve(dataFolder.relativize(folder)))) {
                return;
            }
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.matches(filePattern)
                        && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    discardPreparation(file);
                } else if (name.matches(directoryPattern)
                        && Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    cleanupTemporary(file);
                }
            }
        } catch (IOException | java.nio.file.DirectoryIteratorException exception) {
            logger.warning("Could not inspect abandoned restore work: " + reason(exception));
        }
    }

    private void discardPreparation(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            logger.warning("Could not remove temporary backup file " + file.getFileName() + ": " + reason(exception));
        }
    }

    public synchronized RestoreResult applyPendingRestore() throws IOException {
        recoverInterruptedRestore();
        Path pending = pendingRestorePath();
        if (Files.notExists(pending)) {
            return RestoreResult.NONE;
        }
        Path staging;
        try {
            staging = Files.createTempDirectory(dataFolder, ".restore-stage-");
        } catch (IOException exception) {
            return deferPendingRestore(reason(exception));
        }
        Path rollback = dataFolder.resolve(RESTORE_ROLLBACK);
        try {
            List<String> databases = new ArrayList<>();
            Verification verification = scanArchive(pending, staging, false, databases::add);
            if (verification.inconclusive()) {
                return deferPendingRestore(verification.problem());
            }
            if (!verification.valid()) {
                return rejectPendingRestore(pending, verification.problem());
            }
            List<String> managedPaths = managedPaths(databases);
            StorageConfiguration configuration;
            try {
                configuration = validateStagedRestore(staging);
            } catch (IOException exception) {
                if (archiveAccessFailed(exception)) {
                    return deferPendingRestore(reason(exception));
                }
                return rejectPendingRestore(pending, reason(exception));
            }
            // A legacy SQLite restore must import into a clean database, while retaining
            // the original database and its sidecars until startup succeeds.
            if (configuration.backend() == StorageBackend.SQLITE) {
                Path databasePath = dataFolder.resolve(configuration.sqliteFile().getFileName());
                if (managedPaths.stream().map(dataFolder::resolve).noneMatch(databasePath::equals)) {
                    String database = databasePath.getFileName().toString();
                    managedPaths.add(database);
                    managedPaths.addAll(databaseSidecars(database));
                }
            }
            managedPaths.addAll(restoreProfilePaths(staging));
            createPreRestoreBackup(managedPaths);
            List<String> absent = managedPaths.stream()
                    .filter(path -> Files.notExists(dataFolder.resolve(path), LinkOption.NOFOLLOW_LINKS))
                    .toList();
            Path prepared = Files.createTempDirectory(dataFolder, ".restore-prepare-");
            try {
                Files.write(prepared.resolve(ABSENT_PATHS), absent);
                Files.write(prepared.resolve(MANAGED_PATHS), managedPaths);
                movePath(prepared, rollback);
            } finally {
                cleanupTemporary(prepared);
            }
            for (String managed : managedPaths) {
                Path current = dataFolder.resolve(managed);
                Path previous = rollback.resolve(managed);
                if (!absent.contains(managed)) {
                    Files.createDirectories(previous.getParent());
                    movePath(current, previous);
                }
                Path restored = staging.resolve(managed);
                if (Files.exists(restored)) {
                    Files.createDirectories(current.getParent());
                    movePath(restored, current);
                }
            }
        } catch (Exception restoreFailure) {
            try {
                recoverInterruptedRestore();
            } catch (IOException rollbackFailure) {
                restoreFailure.addSuppressed(rollbackFailure);
                throw new IOException("Could not roll back a pending backup restore", restoreFailure);
            }
            if (restoreFailure instanceof IOException ioException) {
                if (archiveAccessFailed(ioException)) {
                    return deferPendingRestore(reason(ioException));
                }
                throw ioException;
            }
            throw new IOException("Could not apply pending backup restore", restoreFailure);
        } finally {
            cleanupTemporary(staging);
        }

        return RestoreResult.PREPARED;
    }

    public synchronized void commitRestore() throws IOException {
        // The pending archive is the commit marker. On the next startup its absence
        // means any remaining rollback directory can be discarded.
        Files.delete(pendingRestorePath());
        cleanupTemporary(dataFolder.resolve(RESTORE_ROLLBACK));
        logger.warning("Applied staged backup restore and opened restored storage.");
    }

    public synchronized void rollbackFailedRestore(Exception failure) throws IOException {
        recoverInterruptedRestore();
        rejectPendingRestore(pendingRestorePath(), reason(failure));
    }

    private RestoreResult deferPendingRestore(String problem) {
        logger.warning("Deferred pending restore: " + problem
                + ". Startup will use the existing data and retry the restore on the next restart.");
        return RestoreResult.DEFERRED;
    }

    private RestoreResult rejectPendingRestore(Path pending, String problem) throws IOException {
        Path rejected = Files.createTempFile(dataFolder, ".restore-rejected-", ".zip");
        try {
            movePath(pending, rejected);
        } catch (IOException exception) {
            cleanupTemporary(rejected);
            throw exception;
        }
        logger.warning("Rejected pending restore: " + problem + ". Preserved the archive as "
                + rejected.getFileName() + "; startup will use the existing data.");
        return RestoreResult.REJECTED;
    }

    private Verification verifyRestoreArchive(Path archive, boolean includeDigest) throws IOException {
        Path staging = Files.createTempDirectory(dataFolder, ".restore-verify-");
        try {
            Verification verification = scanArchive(archive, staging, includeDigest, ignored -> {});
            if (!verification.valid()) {
                return verification;
            }
            try {
                validateStagedRestore(staging);
                return verification;
            } catch (IOException exception) {
                return archiveAccessFailed(exception) ? Verification.unreadable(exception)
                        : Verification.invalid(reason(exception));
            }
        } finally {
            cleanupTemporary(staging);
        }
    }

    private static boolean archiveAccessFailed(IOException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof FileSystemException || cause instanceof FileNotFoundException) {
                return true;
            }
        }
        return false;
    }

    private StorageConfiguration validateStagedRestore(Path staging) throws IOException {
        Path configPath = staging.resolve("config.yml");
        try (var bundled = new InputStreamReader(Objects.requireNonNull(
                BackupService.class.getResourceAsStream("/config.yml"), "Missing bundled config.yml"),
                StandardCharsets.UTF_8)) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(bundled);
            YamlConfiguration config = defaults;
            if (Files.isRegularFile(configPath)) {
                config = StrictYaml.load(configPath);
                config.setDefaults(defaults);
            }
            ConfigMigrationService.migrateInMemory(config);
            PortableExportService.validate(config);
            MessageService.validateArchivedCatalogs(staging, config);
            return StorageManager.verifyRestore(config, staging, dataFolder, logger, legacyWorldResolver);
        } catch (InvalidConfigurationException | IllegalArgumentException | IllegalStateException exception) {
            throw new IOException("Invalid archived configuration: " + reason(exception), exception);
        }
    }

    public synchronized void recoverInterruptedRestore() throws IOException {
        Path rollback = dataFolder.resolve(RESTORE_ROLLBACK);
        if (Files.notExists(rollback)) {
            return;
        }
        Path pending = pendingRestorePath();
        if (!Files.notExists(pending)) {
            Set<String> absent = Set.copyOf(Files.readAllLines(rollback.resolve(ABSENT_PATHS)));
            Path manifest = rollback.resolve(MANAGED_PATHS);
            List<String> paths;
            if (Files.exists(manifest)) {
                paths = Files.readAllLines(manifest);
            } else {
                paths = managedPaths(databaseEntries(pending));
                // Restores staged by older builds backed up the directory as a whole.
                paths.add(USERDATA);
            }
            if (paths.stream().anyMatch(BackupService::isManagedProfilePath)) {
                requireProfileDirectory(dataFolder.resolve(USERDATA));
            }
            for (String managed : paths) {
                if (!ROOT_FILES.contains(managed) && !managed.equals(USERDATA) && !isManagedProfilePath(managed)
                        && !StorageConfiguration.validSqliteFilename(managed)
                        && !isDatabaseSidecar(managed)) {
                    throw new IOException("Invalid managed path in restore rollback: " + managed);
                }
                Path previous = rollback.resolve(managed);
                Path current = dataFolder.resolve(managed);
                if (!Files.notExists(previous, LinkOption.NOFOLLOW_LINKS)) {
                    removeRestorePath(managed, current);
                    Files.createDirectories(current.getParent());
                    movePath(previous, current);
                } else if (absent.contains(managed)) {
                    removeRestorePath(managed, current);
                }
            }
            logger.warning("Rolled back an interrupted backup restore. The staged archive is still pending.");
        }
        discardRollback(rollback);
    }

    private void removeRestorePath(String managed, Path current) throws IOException {
        if (isManagedProfilePath(managed)) {
            Files.deleteIfExists(current);
        } else {
            deleteRecursively(current);
        }
    }

    private void discardRollback(Path rollback) throws IOException {
        Path cleanup = Files.createTempDirectory(dataFolder, ".restore-cleanup-");
        try {
            movePath(rollback, cleanup.resolve("rollback"));
        } finally {
            cleanupTemporary(cleanup);
        }
    }

    private static List<String> managedPaths(List<String> databases) {
        List<String> paths = new ArrayList<>(ROOT_FILES);
        for (String database : databases) {
            paths.add(database);
            paths.addAll(databaseSidecars(database));
        }
        return paths;
    }

    private List<String> restoreProfilePaths(Path staging) throws IOException {
        Path installed = dataFolder.resolve(USERDATA);
        requireProfileDirectory(installed);
        Set<Path> profiles = new LinkedHashSet<>();
        for (Path directory : List.of(staging.resolve(USERDATA), installed)) {
            if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (Stream<Path> files = Files.list(directory)) {
                files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .map(Path::getFileName)
                        .filter(name -> ProfileStore.isProfileFilename(name.toString()))
                        .map(installed::resolve)
                        .forEach(profiles::add);
            }
        }
        for (Path profile : profiles) {
            if (!Files.notExists(profile, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(profile, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileSystemException(profile.toString(), null, "Legacy profile path is not a regular file");
            }
        }
        return profiles.stream().map(path -> USERDATA_PREFIX + path.getFileName()).toList();
    }

    private static void requireProfileDirectory(Path directory) throws IOException {
        if (!Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.toRealPath().equals(directory.getParent().toRealPath().resolve(directory.getFileName())))) {
            throw new FileSystemException(directory.toString(), null, "Legacy profile directory is not a local directory");
        }
    }

    private static boolean isManagedProfilePath(String path) {
        return path.startsWith(USERDATA_PREFIX) && ProfileStore.isProfileFilename(path.substring(USERDATA_PREFIX.length()));
    }

    private static Verification scanArchive(Path archive, Path staging, boolean includeDigest,
                                            Consumer<String> databaseEntry) {
        if (archive == null) {
            return Verification.invalid("Archive is missing or is not a regular file");
        }
        try {
            if (!Files.readAttributes(archive, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                return Verification.invalid("Archive is missing or is not a regular file");
            }
        } catch (NoSuchFileException exception) {
            return Verification.invalid("Archive is missing or is not a regular file");
        } catch (IOException exception) {
            return Verification.unreadable(exception);
        }

        int entries = 0;
        long totalBytes = 0;
        Set<String> names = new HashSet<>();
        byte[] buffer = new byte[16_384];
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                ensureNotInterrupted();
                ZipEntry entry = iterator.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                entries++;
                if (entries > MAX_ARCHIVE_ENTRIES) {
                    return Verification.invalid("Archive contains too many files");
                }
                String name = entry.getName();
                if (!validManagedEntry(name)) {
                    return Verification.invalid("Archive contains unsupported path '" + name + "'");
                }
                if (!names.add(name.toLowerCase(Locale.ROOT))) {
                    return Verification.invalid("Archive contains duplicate path '" + name + "'");
                }
                CRC32 crc = new CRC32();
                long entryBytes = 0;
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream output = openRestoreEntry(staging, name)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        ensureNotInterrupted();
                        if (read == 0) {
                            continue;
                        }
                        totalBytes += read;
                        entryBytes += read;
                        if (exceedsYamlLimit(name, entryBytes)) {
                            return Verification.invalid(yamlLimitProblem(name));
                        }
                        crc.update(buffer, 0, read);
                        if (totalBytes > MAX_ARCHIVE_BYTES) {
                            return Verification.invalid("Archive expands beyond the 512 MiB safety limit");
                        }
                        output.write(buffer, 0, read);
                    }
                }
                if (entryBytes != entry.getSize() || crc.getValue() != entry.getCrc()) {
                    return Verification.invalid("Archive entry '" + name + "' failed its size or CRC check");
                }
                if (StorageConfiguration.validSqliteFilename(name)) {
                    databaseEntry.accept(name);
                }
            }
        } catch (ZipException exception) {
            return Verification.invalid(reason(exception));
        } catch (IOException exception) {
            return Verification.unreadable(exception);
        }
        if (entries == 0) {
            return Verification.invalid("Archive does not contain plugin data");
        }
        try {
            return new Verification(
                    true,
                    entries,
                    totalBytes,
                    includeDigest ? sha256(archive) : "",
                    ""
            );
        } catch (IOException exception) {
            return Verification.unreadable(exception);
        }
    }

    private List<Path> sources() throws IOException {
        ArrayList<Path> sources = new ArrayList<>();
        for (String filename : ROOT_FILES) {
            Path candidate = dataFolder.resolve(filename);
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                sources.add(candidate);
            }
        }
        Path database = dataFolder.resolve(databaseFilename);
        if (Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
            sources.add(database);
        }
        Path userdata = dataFolder.resolve(USERDATA);
        if (Files.isDirectory(userdata, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> files = Files.list(userdata)) {
                files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.getFileName().toString().endsWith(".yml"))
                        .forEach(sources::add);
            }
        }
        sources.sort(Comparator.comparing(path ->
                dataFolder.relativize(path).toString().replace('\\', '/')));
        return List.copyOf(sources);
    }

    private void writeArchive(Path archive, List<Path> sources) throws IOException {
        if (sources.size() > MAX_ARCHIVE_ENTRIES) {
            throw new ArchiveLimitException("Archive contains too many files");
        }
        long totalBytes = 0;
        Set<String> names = new HashSet<>();
        for (Path source : sources) {
            String name = dataFolder.relativize(source).toString().replace('\\', '/');
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new IOException("Backup contains duplicate or case-colliding entry '" + name + "'");
            }
            long entryBytes = Files.size(source);
            checkArchiveEntrySize(name, entryBytes);
            totalBytes += entryBytes;
            if (totalBytes > MAX_ARCHIVE_BYTES) {
                throw new ArchiveLimitException("Archive expands beyond the 512 MiB safety limit");
            }
        }
        totalBytes = 0;
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            byte[] buffer = new byte[16_384];
            for (Path source : sources) {
                String entryName = dataFolder.relativize(source).toString().replace('\\', '/');
                if (!validManagedEntry(entryName)) {
                    throw new IOException("Archive contains unsupported path '" + entryName + "'");
                }
                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(Files.getLastModifiedTime(source).toMillis());
                zip.putNextEntry(entry);
                try (InputStream input = Files.newInputStream(source)) {
                    int read;
                    long entryBytes = 0;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) {
                            entryBytes += read;
                            checkArchiveEntrySize(entryName, entryBytes);
                            totalBytes += read;
                            if (totalBytes > MAX_ARCHIVE_BYTES) {
                                throw new ArchiveLimitException("Archive expands beyond the 512 MiB safety limit");
                            }
                            zip.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static boolean exceedsYamlLimit(String name, long bytes) {
        return name.endsWith(".yml") && bytes > StrictYaml.MAX_FILE_BYTES;
    }

    private static String yamlLimitProblem(String name) {
        return "YAML file exceeds the 16 MiB safety limit: " + name;
    }

    private static void checkArchiveEntrySize(String name, long bytes) throws ArchiveLimitException {
        if (exceedsYamlLimit(name, bytes)) {
            throw new ArchiveLimitException(yamlLimitProblem(name));
        }
    }

    private Path availableTarget(String reason) {
        String base = TIMESTAMP.format(clock.instant()) + "-" + reason;
        Path target = backupFolder.resolve(base + ".zip");
        int suffix = 2;
        while (Files.exists(target)) {
            target = backupFolder.resolve(base + "-" + suffix + ".zip");
            suffix++;
        }
        return target;
    }

    private void rotate() throws IOException {
        List<Path> archives;
        try (Stream<Path> files = Files.list(backupFolder)) {
            archives = new ArrayList<>(files.filter(BackupService::isRegularZip).toList());
        }
        if (archives.size() <= retention) {
            return;
        }
        Map<Path, FileTime> modified = new HashMap<>();
        archives.forEach(path -> modified.put(path, lastModified(path)));
        archives.sort(Comparator.<Path, FileTime>comparing(modified::get)
                .thenComparing(path -> path.getFileName().toString())
                .reversed());
        int keepNewest = retention;
        Path restorePoint = null;
        for (int index = 0; index < archives.size(); index++) {
            Path archive = archives.get(index);
            Verification verification;
            try {
                verification = verifyRestoreArchive(archive, false);
            } catch (IOException exception) {
                verification = Verification.unreadable(exception);
            }
            if (verification.inconclusive()) {
                logger.warning("Deferred backup rotation while verifying " + archive.getFileName() + ": "
                        + verification.problem());
                return;
            }
            if (verification.valid()) {
                if (index >= retention) {
                    restorePoint = archive;
                    keepNewest--;
                }
                break;
            }
        }
        for (Path archive : archives.subList(keepNewest, archives.size())) {
            if (!archive.equals(restorePoint)) {
                Files.deleteIfExists(archive);
            }
        }
    }

    private static boolean isRegularZip(Path path) {
        return path.getFileName().toString().endsWith(".zip") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException exception) {
            return FileTime.fromMillis(0);
        }
    }

    private Path resolveArchive(String filename) throws IOException {
        if (filename == null || filename.isBlank()) {
            throw new IOException("Backup filename is required");
        }
        Path name;
        try {
            name = Path.of(filename);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid backup filename", exception);
        }
        if (name.getNameCount() != 1
                || !name.equals(name.getFileName())
                || !filename.endsWith(".zip")) {
            throw new IOException("Backup filename must name one ZIP in the backups directory");
        }
        Path resolved = backupFolder.resolve(name).toAbsolutePath().normalize();
        if (!resolved.startsWith(backupFolder.toAbsolutePath().normalize())
                || !isRegularZip(resolved)) {
            throw new IOException("Backup does not exist: " + filename);
        }
        return resolved;
    }

    private Path pendingRestorePath() {
        return dataFolder.resolve(PENDING_RESTORE_FILE);
    }

    private static OutputStream openRestoreEntry(Path staging, String name) throws IOException {
        Path target = staging.resolve(name).normalize();
        if (!target.startsWith(staging)) {
            throw new ZipException("Archive entry escapes the restore directory");
        }
        Files.createDirectories(target.getParent());
        return Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private void movePath(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void deleteRecursively(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        if (normalized.equals(dataFolder) || !normalized.startsWith(dataFolder)) {
            throw new IOException("Refused to delete path outside the plugin data directory");
        }
        if (Files.notExists(normalized)) {
            return;
        }
        Path realDataFolder = dataFolder.toRealPath();
        Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!directory.toRealPath().equals(realDataFolder.resolve(dataFolder.relativize(directory)))) {
                    throw new IOException("Refused to follow a linked restore directory: " + directory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void cleanupTemporary(Path target) {
        try {
            deleteRecursively(target);
        } catch (IOException exception) {
            logger.warning("Could not remove temporary restore directory "
                    + target.getFileName() + ": " + reason(exception));
        }
    }

    private static boolean validManagedEntry(String name) {
        if (name == null
                || name.isBlank()
                || name.startsWith("/")
                || name.contains("\\")
                || name.contains(":")
                || name.contains("//")) {
            return false;
        }
        if (ROOT_FILES.contains(name)) {
            return true;
        }
        if (StorageConfiguration.validSqliteFilename(name)) {
            return true;
        }
        if (!name.startsWith(USERDATA_PREFIX) || !name.endsWith(".yml")) {
            return false;
        }
        String filename = name.substring(USERDATA_PREFIX.length());
        return !filename.isBlank()
                && !filename.contains("/")
                && !filename.equals(".")
                && !filename.equals("..");
    }

    /** The write-ahead log and rollback files SQLite keeps beside a database. */
    static List<String> databaseSidecars(String database) {
        return List.of(database + "-wal", database + "-shm", database + "-journal");
    }

    private static boolean isDatabaseSidecar(String path) {
        int suffix = path.lastIndexOf('-');
        if (suffix < 0) {
            return false;
        }
        String database = path.substring(0, suffix);
        return StorageConfiguration.validSqliteFilename(database) && databaseSidecars(database).contains(path);
    }

    private static List<String> databaseEntries(Path archive) throws IOException {
        ArrayList<String> databases = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && StorageConfiguration.validSqliteFilename(name)) {
                    databases.add(name);
                }
            }
        }
        return List.copyOf(databases);
    }

    private static String sha256(Path archive) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        byte[] buffer = new byte[16_384];
        try (InputStream input = Files.newInputStream(archive)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                ensureNotInterrupted();
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private static void ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Backup verification interrupted");
        }
    }

    private static String reason(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private String normalizeReason(String reason) {
        String normalized = Objects.requireNonNull(reason, "reason")
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("reason must contain letters or numbers");
        }
        return normalized;
    }

    public record ArchiveInfo(String filename, long size, Instant modified) {
    }

    public final class PreparedRestore implements AutoCloseable {
        private final String filename;
        private final Path temporary;
        private final Verification verification;

        private PreparedRestore(String filename, Path temporary, Verification verification) {
            this.filename = filename;
            this.temporary = temporary;
            this.verification = verification;
        }

        public Verification publish() throws IOException {
            synchronized (restorePublication) {
                if (!preparedRestores.contains(temporary)) {
                    throw new IOException("Backup restore preparation was cancelled");
                }
                if (Files.exists(pendingRestorePath())) {
                    throw new IOException("A backup restore is already pending");
                }
                movePath(temporary, pendingRestorePath());
                preparedRestores.remove(temporary);
            }
            logger.warning("Staged backup restore from " + filename
                    + "; it will be applied before data loading on the next startup.");
            return verification;
        }

        @Override
        public void close() {
            synchronized (restorePublication) {
                preparedRestores.remove(temporary);
            }
            discardPreparation(temporary);
        }
    }

    public record Verification(
            boolean valid,
            int entries,
            long uncompressedBytes,
            String sha256,
            String problem,
            boolean inconclusive
    ) {

        public Verification(boolean valid, int entries, long uncompressedBytes, String sha256, String problem) {
            this(valid, entries, uncompressedBytes, sha256, problem, false);
        }

        private static Verification invalid(String problem) {
            return new Verification(false, 0, 0, "", problem);
        }

        private static Verification unreadable(IOException exception) {
            return new Verification(false, 0, 0, "", "Could not read or inspect archive: " + reason(exception), true);
        }
    }

    public enum RestoreResult {
        NONE,
        PREPARED,
        DEFERRED,
        REJECTED
    }

    @FunctionalInterface
    public interface SnapshotLeaseFactory {

        SnapshotLease acquire() throws IOException;
    }

    @FunctionalInterface
    public interface SnapshotLease extends AutoCloseable {

        @Override
        void close();
    }

    private static final class ArchiveLimitException extends IOException {
        private ArchiveLimitException(String message) {
            super(message);
        }
    }

}

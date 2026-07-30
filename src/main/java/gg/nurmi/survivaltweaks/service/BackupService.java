package gg.nurmi.survivaltweaks.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
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
            "storage-migration.yml"
    );
    private static final String PENDING_RESTORE_FILE = ".restore-pending.zip";
    private static final int MAX_ARCHIVE_ENTRIES = 100_000;
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L;

    private final Path dataFolder;
    private final Path backupFolder;
    private final Clock clock;
    private final Logger logger;
    private final int retention;
    private SnapshotLeaseFactory snapshotLeaseFactory = () -> () -> {
    };
    private String databaseFilename = "survivaltweaks.db";
    private long generation;

    public BackupService(Path dataFolder, Clock clock, Logger logger) {
        this(dataFolder, clock, logger, DEFAULT_RETENTION);
    }

    BackupService(Path dataFolder, Clock clock, Logger logger, int retention) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder").toAbsolutePath().normalize();
        this.backupFolder = this.dataFolder.resolve("backups");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        if (retention < 1 || retention > 100) {
            throw new IllegalArgumentException("retention must be between 1 and 100");
        }
        this.retention = retention;
    }

    public synchronized Optional<Path> create(String reason) throws IOException {
        String normalizedReason = normalizeReason(reason);
        try (SnapshotLease ignored = snapshotLeaseFactory.acquire()) {
            List<Path> sources = sources();
            if (sources.isEmpty()) {
                return Optional.empty();
            }

            Files.createDirectories(backupFolder);
            Path target = availableTarget(normalizedReason);
            Path temporary = Files.createTempFile(backupFolder, ".backup-", ".tmp");
            try {
                writeArchive(temporary, sources);
                replaceAtomically(temporary, target);
                Files.setLastModifiedTime(
                        target,
                        FileTime.fromMillis(clock.instant().toEpochMilli() + generation++)
                );
            } finally {
                Files.deleteIfExists(temporary);
            }
            rotate();
            logger.info("Created SurvivalTweaks safety backup " + target.getFileName());
            return Optional.of(target);
        }
    }

    public synchronized void snapshotLeaseFactory(SnapshotLeaseFactory updatedFactory) {
        snapshotLeaseFactory = Objects.requireNonNull(updatedFactory, "updatedFactory");
    }

    public synchronized void databaseFilename(String updatedFilename) {
        String normalized = Objects.requireNonNull(updatedFilename, "updatedFilename").strip();
        if (!validDatabaseFilename(normalized)) {
            throw new IllegalArgumentException("SQLite backup filename is invalid");
        }
        databaseFilename = normalized;
    }

    public synchronized List<ArchiveInfo> archives() throws IOException {
        if (Files.notExists(backupFolder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(backupFolder)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .map(path -> {
                        try {
                            return new ArchiveInfo(
                                    path.getFileName().toString(),
                                    Files.size(path),
                                    Files.getLastModifiedTime(path).toInstant()
                            );
                        } catch (IOException exception) {
                            throw new ArchiveInspectionException(exception);
                        }
                    })
                    .sorted(Comparator
                            .comparing(ArchiveInfo::modified)
                            .thenComparing(ArchiveInfo::filename)
                            .reversed())
                    .toList();
        } catch (ArchiveInspectionException exception) {
            throw exception.ioException();
        }
    }

    public synchronized Verification verify(String filename) {
        try {
            return verifyArchive(resolveArchive(filename));
        } catch (Exception exception) {
            return Verification.invalid(reason(exception));
        }
    }

    public synchronized boolean hasPendingRestore() {
        return Files.isRegularFile(pendingRestorePath());
    }

    public synchronized Verification stageRestore(String filename) throws IOException {
        if (Files.exists(pendingRestorePath())) {
            throw new IOException("A backup restore is already pending");
        }
        Path archive = resolveArchive(filename);
        Verification verification = verifyArchive(archive);
        if (!verification.valid()) {
            throw new IOException("Backup verification failed: " + verification.problem());
        }

        Path temporary = Files.createTempFile(dataFolder, ".restore-pending-", ".tmp");
        try {
            Files.copy(archive, temporary, StandardCopyOption.REPLACE_EXISTING);
            replaceAtomically(temporary, pendingRestorePath());
        } finally {
            Files.deleteIfExists(temporary);
        }
        logger.warning("Staged backup restore from " + archive.getFileName()
                + "; it will be applied before data loading on the next startup.");
        return verification;
    }

    public synchronized RestoreResult applyPendingRestore() throws IOException {
        Path pending = pendingRestorePath();
        if (Files.notExists(pending)) {
            return RestoreResult.NONE;
        }
        Verification verification = verifyArchive(pending);
        if (!verification.valid()) {
            throw new IOException("Pending backup verification failed: " + verification.problem());
        }

        Path staging = Files.createTempDirectory(dataFolder, ".restore-stage-");
        Path rollback = Files.createTempDirectory(dataFolder, ".restore-rollback-");
        List<String> managedPaths = new ArrayList<>(ROOT_FILES);
        managedPaths.addAll(databaseEntries(pending));
        managedPaths.add("userdata");
        List<String> touchedPaths = new ArrayList<>();
        boolean preserveRollback = false;
        try {
            extractManagedArchive(pending, staging);
            for (String managed : managedPaths) {
                Path current = dataFolder.resolve(managed);
                Path previous = rollback.resolve(managed);
                if (Files.exists(current)) {
                    Files.createDirectories(previous.getParent());
                    movePath(current, previous);
                    touchedPaths.add(managed);
                }
                Path restored = staging.resolve(managed);
                if (Files.exists(restored)) {
                    Files.createDirectories(current.getParent());
                    movePath(restored, current);
                    if (!touchedPaths.contains(managed)) {
                        touchedPaths.add(managed);
                    }
                }
            }
        } catch (Exception restoreFailure) {
            IOException rollbackFailure = rollback(touchedPaths, rollback);
            if (rollbackFailure != null) {
                preserveRollback = true;
                restoreFailure.addSuppressed(rollbackFailure);
            }
            if (restoreFailure instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Could not apply pending backup restore", restoreFailure);
        } finally {
            cleanupTemporary(staging);
            if (preserveRollback) {
                logger.severe("Preserved incomplete restore rollback data at " + rollback);
            } else {
                cleanupTemporary(rollback);
            }
        }

        Files.delete(pending);
        logger.warning("Applied staged backup restore containing "
                + verification.entries() + " files; normal startup will now continue.");
        return RestoreResult.APPLIED;
    }

    static Verification verifyArchive(Path archive) {
        if (archive == null || !Files.isRegularFile(archive) || Files.isSymbolicLink(archive)) {
            return Verification.invalid("Archive is missing or is not a regular file");
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
                if (!names.add(name)) {
                    return Verification.invalid("Archive contains duplicate path '" + name + "'");
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        ensureNotInterrupted();
                        if (read == 0) {
                            continue;
                        }
                        totalBytes += read;
                        if (totalBytes > MAX_ARCHIVE_BYTES) {
                            return Verification.invalid("Archive expands beyond the 512 MiB safety limit");
                        }
                    }
                }
            }
        } catch (CancellationException exception) {
            throw exception;
        } catch (Exception exception) {
            return Verification.invalid(reason(exception));
        }
        if (entries == 0) {
            return Verification.invalid("Archive does not contain plugin data");
        }
        try {
            return new Verification(
                    true,
                    entries,
                    totalBytes,
                    sha256(archive),
                    ""
            );
        } catch (IOException exception) {
            return Verification.invalid(reason(exception));
        }
    }

    private List<Path> sources() throws IOException {
        ArrayList<Path> sources = new ArrayList<>();
        for (String filename : ROOT_FILES) {
            Path candidate = dataFolder.resolve(filename);
            if (Files.isRegularFile(candidate) && !Files.isSymbolicLink(candidate)) {
                sources.add(candidate);
            }
        }
        Path database = dataFolder.resolve(databaseFilename);
        if (Files.isRegularFile(database) && !Files.isSymbolicLink(database)) {
            sources.add(database);
        }
        Path userdata = dataFolder.resolve("userdata");
        if (Files.isDirectory(userdata) && !Files.isSymbolicLink(userdata)) {
            try (Stream<Path> files = Files.list(userdata)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> !Files.isSymbolicLink(path))
                        .filter(path -> path.getFileName().toString().endsWith(".yml"))
                        .forEach(sources::add);
            }
        }
        sources.sort(Comparator.comparing(path ->
                dataFolder.relativize(path).toString().replace('\\', '/')));
        return List.copyOf(sources);
    }

    private void writeArchive(Path archive, List<Path> sources) throws IOException {
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            byte[] buffer = new byte[16_384];
            for (Path source : sources) {
                String entryName = dataFolder.relativize(source).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(Files.getLastModifiedTime(source).toMillis());
                zip.putNextEntry(entry);
                try (InputStream input = Files.newInputStream(source)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) {
                            zip.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
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
            archives = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator
                            .comparing(this::lastModified)
                            .thenComparing(path -> path.getFileName().toString())
                            .reversed())
                    .toList();
        }
        for (int index = retention; index < archives.size(); index++) {
            Files.deleteIfExists(archives.get(index));
        }
    }

    private FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException exception) {
            return FileTime.fromMillis(0);
        }
    }

    private void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
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
                || !Files.isRegularFile(resolved)
                || Files.isSymbolicLink(resolved)) {
            throw new IOException("Backup does not exist: " + filename);
        }
        return resolved;
    }

    private Path pendingRestorePath() {
        return dataFolder.resolve(PENDING_RESTORE_FILE);
    }

    private void extractManagedArchive(Path archive, Path staging) throws IOException {
        byte[] buffer = new byte[16_384];
        long totalBytes = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!validManagedEntry(name)) {
                    throw new IOException("Archive contains unsupported path '" + name + "'");
                }
                Path target = staging.resolve(name).normalize();
                if (!target.startsWith(staging)) {
                    throw new IOException("Archive entry escapes the restore directory");
                }
                Files.createDirectories(target.getParent());
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(target)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        totalBytes += read;
                        if (totalBytes > MAX_ARCHIVE_BYTES) {
                            throw new IOException("Archive expands beyond the 512 MiB safety limit");
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private IOException rollback(List<String> managedPaths, Path rollback) {
        IOException failure = null;
        for (String managed : managedPaths) {
            Path current = dataFolder.resolve(managed);
            Path previous = rollback.resolve(managed);
            try {
                deleteRecursively(current);
                if (Files.exists(previous)) {
                    Files.createDirectories(current.getParent());
                    movePath(previous, current);
                }
            } catch (IOException exception) {
                if (failure == null) {
                    failure = new IOException("Could not roll back a failed restore");
                }
                failure.addSuppressed(exception);
            }
        }
        return failure;
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
        try (Stream<Path> paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
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
        if (validDatabaseFilename(name)) {
            return true;
        }
        if (!name.startsWith("userdata/") || !name.endsWith(".yml")) {
            return false;
        }
        String filename = name.substring("userdata/".length());
        return !filename.isBlank()
                && !filename.contains("/")
                && !filename.equals(".")
                && !filename.equals("..");
    }

    private static boolean validDatabaseFilename(String name) {
        return name != null
                && name.matches("[A-Za-z0-9._-]{1,128}\\.db")
                && !name.startsWith(".");
    }

    private static List<String> databaseEntries(Path archive) throws IOException {
        ArrayList<String> databases = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (validDatabaseFilename(name) && !databases.contains(name)) {
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

    public record Verification(
            boolean valid,
            int entries,
            long uncompressedBytes,
            String sha256,
            String problem
    ) {

        private static Verification invalid(String problem) {
            return new Verification(false, 0, 0, "", problem);
        }
    }

    public enum RestoreResult {
        NONE,
        APPLIED
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

    private static final class ArchiveInspectionException extends RuntimeException {

        private final IOException ioException;

        private ArchiveInspectionException(IOException ioException) {
            super(ioException);
            this.ioException = ioException;
        }

        private IOException ioException() {
            return ioException;
        }
    }
}

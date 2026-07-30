package gg.nurmi.survivaltweaks.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupServiceTest {

    @TempDir
    Path dataFolder;

    @Test
    void archivesOperationalDataWithoutRecursingIntoBackups() throws IOException {
        Files.writeString(dataFolder.resolve("config.yml"), "home:\n  max-amount: 3\n");
        Files.writeString(dataFolder.resolve("locked-containers.yml"), "locks: []\n");
        Files.writeString(dataFolder.resolve("new-player-spawns.yml"), "assignments: []\n");
        Files.createDirectories(dataFolder.resolve("userdata"));
        Files.writeString(dataFolder.resolve("userdata/player.yml"), "schema-version: 4\n");
        Files.createDirectories(dataFolder.resolve("backups"));
        Files.writeString(dataFolder.resolve("backups/ignored.zip"), "old");
        BackupService backups = service(10);

        Path archive = backups.create("startup").orElseThrow();
        BackupService.Verification verification = backups.verify(archive.getFileName().toString());

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Set<String> entries = zip.stream()
                    .map(java.util.zip.ZipEntry::getName)
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(
                    Set.of(
                            "config.yml",
                            "locked-containers.yml",
                            "new-player-spawns.yml",
                            "userdata/player.yml"
                    ),
                    entries
            );
            assertTrue(entries.stream().noneMatch(entry -> entry.startsWith("backups/")));
        }
        assertTrue(verification.valid());
        assertEquals(4, verification.entries());
        assertEquals(64, verification.sha256().length());
        assertEquals(2, backups.archives().size());
    }

    @Test
    void retainsOnlyTheNewestConfiguredNumberOfArchives() throws IOException {
        Files.writeString(dataFolder.resolve("config.yml"), "test: true\n");
        BackupService backups = service(2);

        Path first = backups.create("startup").orElseThrow();
        Path second = backups.create("reload").orElseThrow();
        Path third = backups.create("manual").orElseThrow();

        assertFalse(Files.exists(first));
        assertTrue(Files.exists(second));
        assertTrue(Files.exists(third));
        try (var files = Files.list(dataFolder.resolve("backups"))) {
            assertEquals(2L, files.filter(path -> path.toString().endsWith(".zip")).count());
        }
    }

    @Test
    void stagedRestoreReplacesTheCompleteManagedSnapshot() throws IOException {
        Files.writeString(dataFolder.resolve("config.yml"), "snapshot: true\n");
        Files.writeString(dataFolder.resolve("locked-containers.yml"), "locks: [snapshot]\n");
        Files.createDirectories(dataFolder.resolve("userdata"));
        Files.writeString(dataFolder.resolve("userdata/player.yml"), "snapshot: true\n");
        BackupService backups = service(10);
        Path archive = backups.create("manual").orElseThrow();

        Files.writeString(dataFolder.resolve("config.yml"), "current: true\n");
        Files.writeString(dataFolder.resolve("death-markers.yml"), "current: true\n");
        Files.writeString(dataFolder.resolve("userdata/player.yml"), "current: true\n");
        Files.writeString(dataFolder.resolve("userdata/extra.yml"), "current: true\n");

        BackupService.Verification staged = backups.stageRestore(archive.getFileName().toString());
        assertTrue(staged.valid());
        assertTrue(backups.hasPendingRestore());

        Files.writeString(dataFolder.resolve("config.yml"), "saved-during-shutdown: true\n");
        assertEquals(BackupService.RestoreResult.APPLIED, backups.applyPendingRestore());

        assertEquals("snapshot: true\n", Files.readString(dataFolder.resolve("config.yml")));
        assertEquals("locks: [snapshot]\n", Files.readString(dataFolder.resolve("locked-containers.yml")));
        assertEquals("snapshot: true\n", Files.readString(dataFolder.resolve("userdata/player.yml")));
        assertFalse(Files.exists(dataFolder.resolve("userdata/extra.yml")));
        assertFalse(Files.exists(dataFolder.resolve("death-markers.yml")));
        assertFalse(backups.hasPendingRestore());
    }

    @Test
    void snapshotsAndRestoresACustomSqliteDatabaseUnderALease() throws IOException {
        Files.writeString(dataFolder.resolve("config.yml"), "storage: sqlite\n");
        Files.writeString(dataFolder.resolve("players.db"), "snapshot");
        BackupService backups = service(10);
        backups.databaseFilename("players.db");
        AtomicBoolean acquired = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        backups.snapshotLeaseFactory(() -> {
            acquired.set(true);
            return () -> closed.set(true);
        });

        Path archive = backups.create("manual").orElseThrow();
        assertTrue(acquired.get());
        assertTrue(closed.get());
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertTrue(zip.stream().map(ZipEntry::getName).anyMatch("players.db"::equals));
        }

        Files.writeString(dataFolder.resolve("players.db"), "newer");
        backups.stageRestore(archive.getFileName().toString());
        backups.applyPendingRestore();
        assertEquals("snapshot", Files.readString(dataFolder.resolve("players.db")));
    }

    @Test
    void verificationRejectsTraversalAndCorruptArchives() throws IOException {
        Path backupFolder = Files.createDirectories(dataFolder.resolve("backups"));
        Path traversal = backupFolder.resolve("traversal.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(traversal))) {
            zip.putNextEntry(new ZipEntry("../outside.yml"));
            zip.write("unsafe".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.writeString(backupFolder.resolve("corrupt.zip"), "not a ZIP archive");
        BackupService backups = service(10);

        assertFalse(backups.verify("traversal.zip").valid());
        assertFalse(backups.verify("corrupt.zip").valid());
        assertFalse(backups.verify("../traversal.zip").valid());
    }

    private BackupService service(int retention) {
        return new BackupService(
                dataFolder,
                Clock.fixed(Instant.parse("2026-07-28T08:00:00Z"), ZoneOffset.UTC),
                Logger.getAnonymousLogger(),
                retention
        );
    }
}

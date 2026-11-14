package gg.nurmi.survivaltweaks.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.zip.CRC32;
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
        Files.writeString(dataFolder.resolve(ProfileRepository.PENDING_LANGUAGES_FILENAME), "pending");
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
                            "userdata/player.yml",
                            ProfileRepository.PENDING_LANGUAGES_FILENAME
                    ),
                    entries
            );
            assertTrue(entries.stream().noneMatch(entry -> entry.startsWith("backups/")));
        }
        assertTrue(verification.valid());
        assertEquals(5, verification.entries());
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
        Path pendingLanguages = dataFolder.resolve(ProfileRepository.PENDING_LANGUAGES_FILENAME);
        Files.writeString(pendingLanguages, "newer language change");
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
        assertFalse(Files.exists(pendingLanguages));
        assertFalse(backups.hasPendingRestore());
    }

    @Test
    void rollsBackPartialRestoreBeforeTakingAnotherSafetyBackup() throws IOException {
        Path config = dataFolder.resolve("config.yml");
        Path locks = dataFolder.resolve("locked-containers.yml");
        Files.writeString(config, "snapshot-config");
        Files.writeString(locks, "snapshot-locks");
        BackupService backups = service(10);
        Path archive = backups.create("manual").orElseThrow();
        backups.stageRestore(archive.getFileName().toString());

        Path rollback = Files.createDirectory(dataFolder.resolve(".restore-rollback"));
        Files.writeString(rollback.resolve(".absent-paths"), "locked-containers.yml\n");
        Files.writeString(rollback.resolve("config.yml"), "original-config");
        Files.writeString(dataFolder.resolve("messages_en.yml"), "original-messages");

        backups.recoverInterruptedRestore();
        assertEquals("original-config", Files.readString(config));
        assertEquals("original-messages", Files.readString(dataFolder.resolve("messages_en.yml")));
        assertFalse(Files.exists(locks));
        assertFalse(Files.exists(rollback));
        assertTrue(backups.hasPendingRestore());

        Path safety = backups.create("pre-restore").orElseThrow();
        try (ZipFile zip = new ZipFile(safety.toFile())) {
            assertEquals("original-config", new String(zip.getInputStream(zip.getEntry("config.yml")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(BackupService.RestoreResult.APPLIED, backups.applyPendingRestore());
        assertEquals("snapshot-config", Files.readString(config));
        assertEquals("snapshot-locks", Files.readString(locks));
    }

    @Test
    void rejectsAnArchiveWithCorruptedEntryData() throws IOException {
        Path archive = dataFolder.resolve("corrupt.zip");
        byte[] payload = {1, 2, 3};
        CRC32 crc = new CRC32();
        crc.update(payload);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            ZipEntry entry = new ZipEntry("config.yml");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(payload.length);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(payload);
        }
        assertTrue(BackupService.verifyArchive(archive).valid());

        byte[] bytes = Files.readAllBytes(archive);
        int payloadStart = 30 + "config.yml".length();
        assertEquals(payload[0], bytes[payloadStart]);
        bytes[payloadStart]++;
        Files.write(archive, bytes);
        assertFalse(BackupService.verifyArchive(archive).valid());
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
    void restoreClearsSqliteSidecarsLeftBehindByAnUncleanShutdown() throws IOException {
        Files.writeString(dataFolder.resolve("config.yml"), "storage: sqlite\n");
        Files.writeString(dataFolder.resolve("survivaltweaks.db"), "snapshot");
        BackupService backups = service(10);

        Path archive = backups.create("manual").orElseThrow();

        // A crash leaves the write-ahead log next to a database that has since
        // moved on from the backup.
        Files.writeString(dataFolder.resolve("survivaltweaks.db"), "newer");
        Files.writeString(dataFolder.resolve("survivaltweaks.db-wal"), "frames for the newer database");
        Files.writeString(dataFolder.resolve("survivaltweaks.db-shm"), "shared memory index");

        backups.stageRestore(archive.getFileName().toString());
        assertEquals(BackupService.RestoreResult.APPLIED, backups.applyPendingRestore());

        assertEquals("snapshot", Files.readString(dataFolder.resolve("survivaltweaks.db")));
        assertFalse(Files.exists(dataFolder.resolve("survivaltweaks.db-wal")));
        assertFalse(Files.exists(dataFolder.resolve("survivaltweaks.db-shm")));
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

    @Test
    void rejectsCaseCollisionsBeforeStagingARestore() throws IOException {
        Path folder = Files.createDirectories(dataFolder.resolve("backups"));
        try (var zip = new ZipOutputStream(Files.newOutputStream(folder.resolve("collision.zip")))) {
            for (String name : List.of("userdata/Player.yml", "userdata/player.yml")) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(1);
                zip.closeEntry();
            }
        }
        BackupService backup = service(10);
        assertFalse(backup.verify("collision.zip").valid());
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> backup.stageRestore("collision.zip"));
        assertFalse(backup.hasPendingRestore());
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

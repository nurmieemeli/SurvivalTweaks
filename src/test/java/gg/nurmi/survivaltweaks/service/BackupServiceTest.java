package gg.nurmi.survivaltweaks.service;

import com.sun.nio.file.ExtendedOpenOption;
import gg.nurmi.survivaltweaks.config.StrictYaml;
import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.ProfileSnapshot;
import gg.nurmi.survivaltweaks.storage.StorageManager;
import gg.nurmi.survivaltweaks.storage.StorageConfiguration;
import gg.nurmi.survivaltweaks.storage.StorageBackend;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupServiceTest {

    private static final String LEGACY_LOCK = """
            locks:
              - id: 00000000-0000-0000-0000-000000000001
                owner: 00000000-0000-0000-0000-000000000002
                blocks: [{world: 00000000-0000-0000-0000-000000000003, x: 1, y: 64, z: 1}]
            """;

    @TempDir
    Path dataFolder;

    @Test
    void archivesOperationalDataWithoutRecursingIntoBackups() throws IOException {
        Files.writeString(dataFolder.resolve("config.yml"), "home:\n  max-amount: 3\n");
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        Files.writeString(dataFolder.resolve("new-player-spawns.yml"), "assignments: []\n");
        Files.writeString(dataFolder.resolve(ProfileRepository.PENDING_LANGUAGES_FILENAME), "pending");
        Files.createDirectories(dataFolder.resolve("userdata"));
        Files.writeString(dataFolder.resolve("userdata/player.yml"), "schema-version: 4\n");
        Files.createDirectories(dataFolder.resolve("backups"));
        Files.writeString(dataFolder.resolve("backups/ignored.zip"), "old");
        BackupService backups = service(10);

        Path archive = backups.create("startup").orElseThrow();
        BackupService.Verification verification = backups.checkRestore(archive.getFileName().toString());

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
        assertTrue(verification.sha256().isEmpty());
        assertEquals(2, backups.archives().size());
    }

    @Test
    void reportsMatchingHashesForVerificationAndStaging() throws IOException {
        Files.writeString(dataFolder.resolve("config.yml"), "config-version: 7\n");
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        BackupService backups = service(10);
        String name = backups.create("manual").orElseThrow().getFileName().toString();

        var check = backups.checkRestore(name);
        assertTrue(check.valid(), check.problem());
        assertTrue(check.sha256().isEmpty());
        var verification = backups.verify(name);
        assertTrue(verification.valid(), verification.problem());
        assertTrue(verification.sha256().matches("[0-9A-F]{64}"));
        try (var cancelled = backups.prepareRestore(name)) {
            assertFalse(backups.hasPendingRestore());
        }
        try (var files = Files.list(dataFolder)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith(".restore-pending-")));
        }
        assertEquals(verification.sha256(), stageRestore(backups, name).sha256());
    }

    @Test
    void closesUndeliveredPreparationsAndCleansOnlyAbandonedTemporaryFilesAtStartup() throws IOException {
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        BackupService backups = service(10);
        String name = backups.create("legacy").orElseThrow().getFileName().toString();
        try (var prepared = backups.prepareRestore(name)) {
            backups.closeRestorePreparations();
            assertThrows(IOException.class, prepared::publish);
        }
        try (var files = Files.list(dataFolder)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith(".restore-pending-")));
        }
        assertFalse(backups.hasPendingRestore());

        BackupService restarted = service(10);
        stageRestore(restarted, name);
        Path abandoned = Files.writeString(dataFolder.resolve(".restore-pending-123.tmp"), "temporary ZIP");
        Path notes = Files.writeString(dataFolder.resolve(".restore-pending-notes.tmp"), "operator notes");
        List<Path> scratch = new ArrayList<>();
        for (String prefix : List.of("verify", "stage", "prepare", "cleanup")) {
            Path directory = dataFolder.resolve(".restore-" + prefix + "-123");
            Files.createDirectories(directory.resolve("userdata"));
            Files.writeString(directory.resolve("userdata/extracted.yml"), "extracted data");
            scratch.add(directory);
        }
        Path abandonedCopy = Files.createDirectory(dataFolder.resolve("backups/.pre-restore-123"));
        Files.writeString(abandonedCopy.resolve("survivaltweaks.db"), "partial copy");
        scratch.add(abandonedCopy);
        Path abandonedZip = Files.copy(dataFolder.resolve("backups").resolve(name),
                dataFolder.resolve("backups/.backup-123.tmp"));
        Path zipNotes = Files.writeString(dataFolder.resolve("backups/.backup-notes.tmp"), "operator notes");
        Path publishedCopy = Files.createDirectory(dataFolder.resolve("backups/pre-restore-123"));
        Path operatorCopy = Files.createDirectory(dataFolder.resolve("backups/.pre-restore-notes"));
        Path rollback = Files.createDirectory(dataFolder.resolve(".restore-rollback"));
        Path operator = Files.createDirectory(dataFolder.resolve(".restore-verify-notes"));
        restarted.discardAbandonedRestoreWork();
        assertFalse(Files.exists(abandoned));
        assertFalse(Files.exists(abandonedZip));
        assertEquals("operator notes", Files.readString(zipNotes));
        assertTrue(scratch.stream().noneMatch(Files::exists));
        assertTrue(restarted.hasPendingRestore());
        assertTrue(Files.isDirectory(rollback));
        assertTrue(Files.isDirectory(operator));
        assertTrue(Files.isDirectory(publishedCopy));
        assertTrue(Files.isDirectory(operatorCopy));
        assertEquals("operator notes", Files.readString(notes));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void symlinkedZipDoesNotTakeARetentionSlot() throws IOException {
        Files.writeString(dataFolder.resolve("config.yml"), "config-version: 7\n");
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        BackupService backups = service(2);
        Path oldest = backups.create("first").orElseThrow();
        Path previous = backups.create("second").orElseThrow();
        Path outside = Files.copy(previous, dataFolder.resolve("operator.zip"));
        Files.setLastModifiedTime(outside, FileTime.from(Instant.parse("2026-07-29T08:00:00Z")));
        Path link = Files.createSymbolicLink(previous.resolveSibling("linked.zip"), outside);

        Path latest = backups.create("third").orElseThrow();

        assertEquals(List.of(latest.getFileName().toString(), previous.getFileName().toString()),
                backups.archives().stream().map(BackupService.ArchiveInfo::filename).toList());
        assertFalse(Files.exists(oldest));
        assertTrue(Files.isSymbolicLink(link));
        assertArrayEquals(Files.readAllBytes(previous), Files.readAllBytes(outside));
        assertFalse(backups.checkRestore(link.getFileName().toString()).valid());
        assertTrue(backups.checkRestore(previous.getFileName().toString()).valid());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void unreadableRestorePointSurvivesRotationAndCanBeStagedAfterTheLockIsReleased() throws IOException {
        Path config = dataFolder.resolve("config.yml");
        Files.writeString(config, "config-version: 7\n");
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        BackupService backups = service(2);
        Path good = backups.create("manual").orElseThrow();
        byte[] original = Files.readAllBytes(good);
        Files.writeString(config, "config-version: 99999\n");
        backups.createStartupBackup();

        try (var ignored = Files.newByteChannel(good, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ)) {
            assertTrue(backups.verify(good.getFileName().toString()).inconclusive());
            assertTrue(backups.checkRestore(good.getFileName().toString()).inconclusive());
            backups.createStartupBackup();
            assertTrue(Files.exists(good));
            assertEquals(3, backups.archives().size());
        }

        assertArrayEquals(original, Files.readAllBytes(good));
        backups.createStartupBackup();
        assertEquals(2, backups.archives().size());
        assertTrue(stageRestore(backups, good.getFileName().toString()).valid());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void lockedPendingRestoreLeavesLiveFilesInPlaceAndRetriesAfterRelease() throws IOException {
        Path config = dataFolder.resolve("config.yml");
        Path locks = dataFolder.resolve("locked-containers.yml");
        Files.writeString(config, "config-version: 7\narchived: true\n");
        Files.writeString(locks, LEGACY_LOCK);
        BackupService backups = service(10);
        var archive = backups.create("manual").orElseThrow();
        stageRestore(backups, archive.getFileName().toString());
        Path pending = dataFolder.resolve(".restore-pending.zip");
        byte[] original = Files.readAllBytes(pending);
        Files.writeString(config, "config-version: 7\ncurrent: true\n");
        Files.delete(locks);

        try (var ignored = Files.newByteChannel(pending, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ)) {
            assertEquals(BackupService.RestoreResult.DEFERRED, backups.applyPendingRestore());
            assertTrue(backups.hasPendingRestore());
            assertFalse(Files.exists(dataFolder.resolve(".restore-rollback")));
            assertFalse(Files.exists(locks));
            assertTrue(Files.readString(config).contains("current: true"));
        }

        assertArrayEquals(original, Files.readAllBytes(pending));
        var restarted = service(10);
        assertEquals(BackupService.RestoreResult.PREPARED, restarted.applyPendingRestore());
        restarted.commitRestore();
        assertFalse(restarted.hasPendingRestore());
        assertEquals(LEGACY_LOCK, Files.readString(locks));
        assertTrue(Files.readString(config).contains("archived: true"));
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

    @ParameterizedTest
    @ValueSource(strings = {"", "schema-version: 6\n", "unrelated: true\n"})
    void emptyLegacyProfileCannotReplaceAnExistingSqliteDatabase(String profileYaml) throws IOException {
        createSqlite("survivaltweaks.db");
        Path configPath = dataFolder.resolve("config.yml");
        var config = YamlConfiguration.loadConfiguration(configPath.toFile());
        UUID playerId = UUID.randomUUID();
        Home originalHome = new Home("audit_home", UUID.randomUUID(), "world", 6, 65, 7, 0, 0);
        UUID originalId;
        try (var storage = StorageManager.open(config, dataFolder, Logger.getAnonymousLogger(), name -> null)) {
            originalId = storage.store().instanceId();
            storage.store().save(new ProfileSnapshot(playerId, List.of(originalHome)));
        }
        Path archives = Files.createDirectories(dataFolder.resolve("backups"));
        Path archive = archives.resolve("empty-legacy-profile.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("config.yml"));
            Files.copy(configPath, zip);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("userdata/" + playerId + ".yml"));
            zip.write(profileYaml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        BackupService backups = service(10);
        assertFalse(backups.verify(archive.getFileName().toString()).valid());
        assertThrows(IOException.class, () -> stageRestore(backups, archive.getFileName().toString()));
        Files.copy(archive, dataFolder.resolve(".restore-pending.zip"));
        assertEquals(BackupService.RestoreResult.REJECTED, backups.applyPendingRestore());
        try (var storage = StorageManager.open(config, dataFolder, Logger.getAnonymousLogger(), name -> null)) {
            assertEquals(originalId, storage.store().instanceId());
            assertEquals(List.of(originalHome), storage.store().load(playerId).homes());
        }
    }

    @Test
    void unsupportedStartupSnapshotsKeepTheNewestRestorableArchive() throws IOException {
        Path config = dataFolder.resolve("config.yml");
        Files.writeString(config, "storage: {backend: sqlite}\n");
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        BackupService backups = service(2);
        Path older = backups.create("manual").orElseThrow();
        Path restorePoint = backups.create("manual").orElseThrow();

        Files.writeString(config, "config-version: 99999\n");
        backups.createStartupBackup();
        backups.createStartupBackup();

        assertFalse(Files.exists(older));
        assertEquals(2, backups.archives().size());
        assertTrue(stageRestore(backups, restorePoint.getFileName().toString()).valid());
        assertEquals(BackupService.RestoreResult.PREPARED, backups.applyPendingRestore());
        backups.commitRestore();
        assertEquals(LEGACY_LOCK, Files.readString(dataFolder.resolve("locked-containers.yml")));
        assertEquals("storage: {backend: sqlite}\n", Files.readString(config));
    }

    @Test
    void migrationEndpointMismatchCannotBeStagedOrDisplaceARestorePoint() throws IOException {
        createSqlite("survivaltweaks.db");
        BackupService backups = service(2);
        Path good = backups.create("manual").orElseThrow();
        var changed = new YamlConfiguration();
        changed.set("storage.backend", "postgresql");
        changed.save(dataFolder.resolve("config.yml").toFile());
        Files.writeString(dataFolder.resolve("storage-migration.yml"), """
                id: 00000000-0000-0000-0000-000000000001
                source: sqlite
                target: postgresql
                target-fingerprint: wrong-endpoint
                """);

        backups.createStartupBackup();
        Path bad = backups.create("startup").orElseThrow();
        assertTrue(Files.exists(good));
        assertEquals(2, backups.archives().size());
        var invalid = backups.verify(bad.getFileName().toString());
        assertFalse(invalid.valid());
        assertTrue(invalid.problem().contains("Target storage configuration changed after migration was staged"));
        assertThrows(IOException.class, () -> stageRestore(backups, bad.getFileName().toString()));
        assertFalse(backups.hasPendingRestore());
        assertTrue(stageRestore(backups, good.getFileName().toString()).valid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgresql", "sqlite"})
    void pendingMigrationCannotBeStagedOrDisplaceARestorePoint(String selectedBackend) throws IOException {
        createSqlite("survivaltweaks.db");
        BackupService backups = service(2);
        Path good = backups.create("manual").orElseThrow();
        var config = new YamlConfiguration();
        config.set("config-version", 7);
        config.set("storage.backend", selectedBackend);
        config.save(dataFolder.resolve("config.yml").toFile());
        String endpoint = StorageConfiguration.load(config, dataFolder).forBackend(StorageBackend.POSTGRESQL)
                .endpointFingerprint();
        Files.writeString(dataFolder.resolve("storage-migration.yml"), """
                id: 00000000-0000-0000-0000-000000000001
                source: sqlite
                target: postgresql
                target-fingerprint: %s
                """.formatted(endpoint));

        backups.createStartupBackup();
        Path bad = backups.create("startup").orElseThrow();

        assertEquals(2, backups.archives().size());
        assertTrue(Files.exists(good));
        var invalid = backups.verify(bad.getFileName().toString());
        assertFalse(invalid.valid());
        assertTrue(invalid.problem().contains("pending storage migration"));
        assertThrows(IOException.class, () -> stageRestore(backups, bad.getFileName().toString()));
        assertFalse(backups.hasPendingRestore());
        Files.copy(bad, dataFolder.resolve(".restore-pending.zip"));
        assertEquals(BackupService.RestoreResult.REJECTED, backups.applyPendingRestore());
        assertTrue(stageRestore(backups, good.getFileName().toString()).valid());
        assertEquals(BackupService.RestoreResult.PREPARED, backups.applyPendingRestore());
        backups.commitRestore();
    }

    @Test
    void archiveVerificationUsesTheSameLegacyPostgresSchemaAsStartup() throws IOException {
        var oldConfig = new YamlConfiguration();
        oldConfig.set("config-version", 2);
        oldConfig.set("storage.backend", "postgresql");
        oldConfig.save(dataFolder.resolve("config.yml").toFile());
        oldConfig.set("storage.remote.postgresql-schema", "public");
        var settings = StorageConfiguration.load(oldConfig, dataFolder);
        Files.writeString(dataFolder.resolve("storage-state.yml"), """
                version: 1
                backend: postgresql
                endpoint-fingerprint: %s
                instance-id: 00000000-0000-0000-0000-000000000001
                """.formatted(settings.legacyEndpointFingerprint()));
        BackupService backups = service(2);
        Path archive = backups.create("manual").orElseThrow();

        assertTrue(backups.verify(archive.getFileName().toString()).valid());
        assertTrue(stageRestore(backups, archive.getFileName().toString()).valid());
        assertFalse(Files.exists(dataFolder.resolve("config-migration-report.txt")));
    }

    @Test
    void stagedRestoreReplacesTheCompleteManagedSnapshot() throws IOException {
        Path profile = dataFolder.resolve("userdata/" + UUID.randomUUID() + ".yml");
        Path extraProfile = dataFolder.resolve("userdata/" + UUID.randomUUID() + ".yml");
        Path notes = dataFolder.resolve("userdata/notes.yml");
        Path nested = dataFolder.resolve("userdata/manual/profile.yml.bak");
        Files.writeString(dataFolder.resolve("config.yml"), "snapshot: true\n");
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        Files.createDirectories(nested.getParent());
        Files.writeString(profile, "snapshot: true\n");
        Files.writeString(notes, "archived notes\n");
        BackupService backups = service(10);
        Path archive = backups.create("manual").orElseThrow();

        Files.writeString(dataFolder.resolve("config.yml"), "current: true\n");
        Files.writeString(dataFolder.resolve("death-markers.yml"), "current: true\n");
        Path pendingLanguages = dataFolder.resolve(ProfileRepository.PENDING_LANGUAGES_FILENAME);
        Files.writeString(pendingLanguages, "newer language change");
        Files.writeString(profile, "current: true\n");
        Files.writeString(extraProfile, "current: true\n");
        Files.writeString(notes, "current notes\n");
        Files.writeString(nested, "nested backup\n");

        BackupService.Verification staged = stageRestore(backups, archive.getFileName().toString());
        assertTrue(staged.valid());
        assertTrue(backups.hasPendingRestore());

        Files.writeString(dataFolder.resolve("config.yml"), "saved-during-shutdown: true\n");
        assertEquals(BackupService.RestoreResult.PREPARED, backups.applyPendingRestore());
        assertTrue(backups.hasPendingRestore());
        backups.commitRestore();

        assertEquals("snapshot: true\n", Files.readString(dataFolder.resolve("config.yml")));
        assertEquals(LEGACY_LOCK, Files.readString(dataFolder.resolve("locked-containers.yml")));
        assertEquals("snapshot: true\n", Files.readString(profile));
        assertFalse(Files.exists(extraProfile));
        assertEquals("current notes\n", Files.readString(notes));
        assertEquals("nested backup\n", Files.readString(nested));
        assertFalse(Files.exists(dataFolder.resolve("death-markers.yml")));
        assertFalse(Files.exists(pendingLanguages));
        assertFalse(backups.hasPendingRestore());
    }

    @Test
    void rollsBackPartialRestoreBeforeTakingAnotherSafetyBackup() throws IOException {
        Path config = dataFolder.resolve("config.yml");
        Path locks = dataFolder.resolve("locked-containers.yml");
        Files.writeString(config, "snapshot: true\n");
        Files.writeString(locks, LEGACY_LOCK);
        BackupService backups = service(10);
        Path archive = backups.create("manual").orElseThrow();
        stageRestore(backups, archive.getFileName().toString());

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
        assertEquals(BackupService.RestoreResult.PREPARED, backups.applyPendingRestore());
        backups.commitRestore();
        assertEquals("snapshot: true\n", Files.readString(config));
        assertEquals(LEGACY_LOCK, Files.readString(locks));
    }

    @ParameterizedTest
    @ValueSource(strings = {"players.db", "current.db"})
    void preservesTheActiveAndRestoreTargetSqliteFiles(String activeDatabase) throws IOException {
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        BackupService backups = service(10);
        backups.databaseFilename("players.db");
        Files.writeString(dataFolder.resolve("config.yml"), "storage: {sqlite: {file: players.db}}\n");
        Path archive = backups.create("manual").orElseThrow();
        stageRestore(backups, archive.getFileName().toString());
        backups.databaseFilename(activeDatabase);
        Files.writeString(dataFolder.resolve("config.yml"), "storage: {sqlite: {file: " + activeDatabase + "}}\n");
        backups.snapshotLeaseFactory(backups::acquireStartupSnapshotLease);

        List<String> target = List.of("players.db", "players.db-wal", "players.db-shm", "players.db-journal");
        List<String> files = new ArrayList<>(target);
        if (!activeDatabase.equals("players.db")) {
            files.add(activeDatabase);
            files.addAll(BackupService.databaseSidecars(activeDatabase));
        }
        for (String file : files) {
            Files.writeString(dataFolder.resolve(file), "damaged contents of " + file);
        }

        assertEquals(BackupService.RestoreResult.PREPARED, backups.applyPendingRestore());
        backups.commitRestore();
        Path safety;
        try (var backupsFolder = Files.list(archive.getParent())) {
            safety = backupsFolder.filter(Files::isDirectory).findFirst().orElseThrow();
        }
        for (String file : files) {
            assertEquals("damaged contents of " + file, Files.readString(safety.resolve(file)));
            assertEquals(!target.contains(file), Files.exists(dataFolder.resolve(file)));
        }
        assertEquals(LEGACY_LOCK, Files.readString(dataFolder.resolve("locked-containers.yml")));
    }

    @Test
    void preservesPinnedSqliteAndSidecarsWhenLiveConfigIsUnreadable() throws IOException {
        Path config = dataFolder.resolve("config.yml");
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        BackupService backups = service(10);
        Path archive = backups.create("legacy").orElseThrow();
        stageRestore(backups, archive.getFileName().toString());

        String filename = "custom.db";
        createSqlite(filename);
        Files.writeString(config, "storage: [\n");
        List<String> files = new ArrayList<>(BackupService.databaseSidecars(filename));
        for (String sidecar : files) {
            Files.writeString(dataFolder.resolve(sidecar), "damaged contents of " + sidecar);
        }
        files.add(filename);

        assertEquals(BackupService.RestoreResult.PREPARED, backups.applyPendingRestore());
        backups.commitRestore();
        Path safety;
        try (var entries = Files.list(archive.getParent())) {
            safety = entries.filter(Files::isDirectory).findFirst().orElseThrow();
        }
        for (String file : files) {
            assertEquals(-1L, Files.mismatch(dataFolder.resolve(file), safety.resolve(file)));
        }
    }

    @Test
    void rejectsAnArchiveWithCorruptedEntryData() throws IOException {
        Path archive = Files.createDirectories(dataFolder.resolve("backups")).resolve("corrupt.zip");
        byte[] payload = "config-version: 7\n".getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(payload);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            ZipEntry entry = new ZipEntry("config.yml");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(payload.length);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(payload);
            zip.putNextEntry(new ZipEntry("locked-containers.yml"));
            zip.write(LEGACY_LOCK.getBytes(StandardCharsets.UTF_8));
        }
        BackupService backups = service(10);
        assertTrue(backups.checkRestore("corrupt.zip").valid());

        byte[] bytes = Files.readAllBytes(archive);
        int payloadStart = 30 + "config.yml".length();
        assertEquals(payload[0], bytes[payloadStart]);
        bytes[payloadStart]++;
        Files.write(archive, bytes);
        var invalid = backups.checkRestore("corrupt.zip");
        assertFalse(invalid.valid());
        assertTrue(invalid.problem().contains("failed its size or CRC check"), invalid.problem());
        Path pending = Files.copy(archive, dataFolder.resolve(".restore-pending.zip"));
        Files.writeString(dataFolder.resolve("config.yml"), "original: true\n");
        assertEquals(BackupService.RestoreResult.REJECTED, backups.applyPendingRestore());
        assertFalse(Files.exists(pending));
        assertEquals("original: true\n", Files.readString(dataFolder.resolve("config.yml")));
    }

    @Test
    void snapshotsAndRestoresACustomSqliteDatabaseUnderALease() throws IOException {
        createSqlite("players.db");
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

        Path database = dataFolder.resolve("players.db");
        byte[] snapshot = Files.readAllBytes(database);
        Files.writeString(database, "newer");
        stageRestore(backups, archive.getFileName().toString());
        backups.applyPendingRestore();
        backups.commitRestore();
        assertArrayEquals(snapshot, Files.readAllBytes(database));
    }

    @Test
    void restoreClearsSqliteSidecarsLeftBehindByAnUncleanShutdown() throws IOException {
        createSqlite("survivaltweaks.db");
        BackupService backups = service(10);

        Path archive = backups.create("manual").orElseThrow();
        Path database = dataFolder.resolve("survivaltweaks.db");
        byte[] snapshot = Files.readAllBytes(database);

        // A crash leaves the write-ahead log next to a database that has since
        // moved on from the backup.
        Files.writeString(dataFolder.resolve("survivaltweaks.db"), "newer");
        Files.writeString(dataFolder.resolve("survivaltweaks.db-wal"), "frames for the newer database");
        Files.writeString(dataFolder.resolve("survivaltweaks.db-shm"), "shared memory index");

        stageRestore(backups, archive.getFileName().toString());
        assertEquals(BackupService.RestoreResult.PREPARED, backups.applyPendingRestore());
        backups.commitRestore();

        assertArrayEquals(snapshot, Files.readAllBytes(database));
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
        assertThrows(IOException.class, () -> stageRestore(backup, "collision.zip"));
        assertFalse(backup.hasPendingRestore());
    }

    @Test
    void rejectsValidZipWithAnUnusableDatabaseBeforeItCanReplaceLiveData() throws IOException {
        createSqlite("survivaltweaks.db");
        Path database = dataFolder.resolve("survivaltweaks.db");
        byte[] original = Files.readAllBytes(database);
        Path folder = Files.createDirectories(dataFolder.resolve("backups"));
        Path archive = folder.resolve("invalid-db.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (String name : List.of("config.yml", "storage-state.yml")) {
                zip.putNextEntry(new ZipEntry(name));
                Files.copy(dataFolder.resolve(name), zip);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(database.getFileName().toString()));
            zip.write("This is not SQLite".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        BackupService backups = service(10);
        assertFalse(backups.verify("invalid-db.zip").valid());
        assertThrows(IOException.class, () -> stageRestore(backups, "invalid-db.zip"));
        assertFalse(backups.hasPendingRestore());

        // Simulate a pending archive left by a version that only checked the ZIP.
        Files.copy(archive, dataFolder.resolve(".restore-pending.zip"));
        assertEquals(BackupService.RestoreResult.REJECTED, backups.applyPendingRestore());
        assertFalse(backups.hasPendingRestore());
        assertArrayEquals(original, Files.readAllBytes(database));
        try (var files = Files.list(dataFolder)) {
            assertTrue(files.anyMatch(file -> file.getFileName().toString().startsWith(".restore-rejected-")));
        }
    }

    @Test
    void rejectsArchivedConfigThatWouldFailStartupAfterStorageOpens() throws IOException {
        createSqlite("survivaltweaks.db");
        Path database = dataFolder.resolve("survivaltweaks.db");
        byte[] original = Files.readAllBytes(database);
        Path archive = Files.createDirectories(dataFolder.resolve("backups")).resolve("bad-settings.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("config.yml"));
            zip.write("storage:\n  portable-exports:\n    retention: 0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        BackupService backups = service(10);
        assertFalse(backups.verify("bad-settings.zip").valid());
        assertThrows(IOException.class, () -> stageRestore(backups, "bad-settings.zip"));
        assertFalse(backups.hasPendingRestore());

        Files.copy(archive, dataFolder.resolve(".restore-pending.zip"));
        assertEquals(BackupService.RestoreResult.REJECTED, backups.applyPendingRestore());
        assertFalse(backups.hasPendingRestore());
        assertArrayEquals(original, Files.readAllBytes(database));
    }

    @Test
    void rejectsMalformedLegacyDataEvenWithoutAnArchivedDatabase() throws IOException {
        createSqlite("survivaltweaks.db");
        Path database = dataFolder.resolve("survivaltweaks.db");
        byte[] original = Files.readAllBytes(database);
        Path archive = Files.createDirectories(dataFolder.resolve("backups")).resolve("legacy.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("locked-containers.yml"));
            zip.write("locks: [unterminated\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        BackupService backups = service(10);
        assertFalse(backups.verify("legacy.zip").valid());
        assertThrows(IOException.class, () -> stageRestore(backups, "legacy.zip"));
        Files.copy(archive, dataFolder.resolve(".restore-pending.zip"));
        assertEquals(BackupService.RestoreResult.REJECTED, backups.applyPendingRestore());
        assertArrayEquals(original, Files.readAllBytes(database));
    }

    @ParameterizedTest
    @ValueSource(strings = {"messages_en.yml", "messages_fi.yml"})
    void malformedArchivedCatalogCannotReplaceLiveMessages(String catalogName) throws IOException {
        Path catalog = dataFolder.resolve(catalogName);
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        Files.writeString(catalog, "lock: [unterminated\n");
        BackupService backups = service(10);
        Path archive = backups.create("legacy").orElseThrow();
        Files.writeString(catalog, "custom: live value\n");

        var invalid = backups.verify(archive.getFileName().toString());
        assertFalse(invalid.valid());
        assertTrue(invalid.problem().contains("Invalid archived language catalog " + catalogName));
        assertThrows(IOException.class, () -> stageRestore(backups, archive.getFileName().toString()));
        assertFalse(backups.hasPendingRestore());
        Files.copy(archive, dataFolder.resolve(".restore-pending.zip"));
        assertEquals(BackupService.RestoreResult.REJECTED, backups.applyPendingRestore());
        assertEquals("custom: live value\n", Files.readString(catalog));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin: wrong type\n", "admin:\n  backup:\n    valid: [wrong type]\n"})
    void wrongTypeArchivedCatalogCannotBeStaged(String contents) throws IOException {
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        Files.writeString(dataFolder.resolve("messages_en.yml"), contents);
        BackupService backups = service(10);
        String filename = backups.create("legacy").orElseThrow().getFileName().toString();

        var invalid = backups.verify(filename);
        assertFalse(invalid.valid());
        assertTrue(invalid.problem().contains("Invalid archived language catalog messages_en.yml: admin"));
        assertThrows(IOException.class, () -> stageRestore(backups, filename));
        assertFalse(backups.hasPendingRestore());
    }

    @Test
    void archivedLegacyMessagesCannotReplaceABundledSectionWithText() throws IOException {
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        Files.writeString(dataFolder.resolve("config.yml"), "messages:\n  admin: wrong type\n");
        Files.writeString(dataFolder.resolve("messages_fi.yml"), "lock:\n  created: Valid text\n");
        BackupService backups = service(10);
        String filename = backups.create("legacy").orElseThrow().getFileName().toString();

        var invalid = backups.verify(filename);
        assertFalse(invalid.valid());
        assertTrue(invalid.problem().contains("Invalid archived language catalog messages_fi.yml: admin must be a section"));
        assertThrows(IOException.class, () -> stageRestore(backups, filename));
        assertFalse(backups.hasPendingRestore());
    }

    @Test
    void overlappingLegacyLocksCannotBeStaged() throws IOException {
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK + """
                  - id: 00000000-0000-0000-0000-000000000004
                    owner: 00000000-0000-0000-0000-000000000002
                    blocks: [{world: 00000000-0000-0000-0000-000000000003, x: 1, y: 64, z: 1}]
                """);
        BackupService backups = service(10);
        String filename = backups.create("legacy").orElseThrow().getFileName().toString();

        var invalid = backups.verify(filename);
        assertFalse(invalid.valid());
        assertTrue(invalid.problem().contains("Legacy container locks share block"));
        assertThrows(IOException.class, () -> stageRestore(backups, filename));
        assertFalse(backups.hasPendingRestore());
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "public-profile", "mail"})
    void wronglyTypedLegacyProfileValuesCannotBeStaged(String key) throws IOException {
        String profile = key.equals("x")
                ? "home:\n  mine:\n    world: world\n    x: '12.5'\n    y: 65\n    z: 14.5\n"
                : "preferences:\n  " + key + ": 'false'\n";
        Path profiles = Files.createDirectory(dataFolder.resolve("userdata"));
        Files.writeString(profiles.resolve(UUID.randomUUID() + ".yml"), profile);
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        BackupService backups = service(10);
        String filename = backups.create("legacy").orElseThrow().getFileName().toString();

        var invalid = backups.verify(filename);
        assertFalse(invalid.valid());
        assertTrue(invalid.problem().contains(key + " must be"));
        assertThrows(IOException.class, () -> stageRestore(backups, filename));
        assertFalse(backups.hasPendingRestore());
    }

    @Test
    void duplicateDeathMarkersCannotBeStaged() throws IOException {
        String marker = """
                  - player: 00000000-0000-0000-0000-000000000002
                    world-uuid: 00000000-0000-0000-0000-000000000003
                    world: world
                    x: 12
                    y: 65
                    z: 14
                    created-at: "2026-11-15T18:00:00Z"
                    expires-at: "2030-11-15T18:00:00Z"
                """;
        Files.writeString(dataFolder.resolve("death-markers.yml"), "markers:\n" + marker + marker.replace("x: 12", "x: 13"));
        BackupService backups = service(10);
        String filename = backups.create("legacy").orElseThrow().getFileName().toString();

        var invalid = backups.verify(filename);
        assertFalse(invalid.valid());
        assertTrue(invalid.problem().contains("Legacy death markers repeat player"));
        assertThrows(IOException.class, () -> stageRestore(backups, filename));
        assertFalse(backups.hasPendingRestore());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void caseCollidingHomeNamesCannotBeStaged(boolean sectionFormat) throws IOException {
        String coordinates = "{world: world, x: 12, y: 65, z: 14, yaw: 0, pitch: 0}";
        String homes = sectionFormat
                ? "home:\n  Mine: " + coordinates + "\n  mine: " + coordinates + "\n"
                : "homes:\n  - " + coordinates.replace("{", "{name: Mine, ")
                        + "\n  - " + coordinates.replace("{", "{name: mine, ") + "\n";
        Path directory = Files.createDirectory(dataFolder.resolve("userdata"));
        Files.writeString(directory.resolve(UUID.randomUUID() + ".yml"), homes);
        assertDuplicateLegacyArchiveRejected("Duplicate home name 'mine'");
    }

    @Test
    void duplicateNotificationIdsCannotBeStaged() throws IOException {
        String notification = """
                  - id: 11111111-2222-3333-8444-555555555555
                    type: MAIL
                    created-at: "2026-11-15T18:00:00Z"
                    detail: The mail body
                """;
        Path directory = Files.createDirectory(dataFolder.resolve("userdata"));
        Files.writeString(directory.resolve(UUID.randomUUID() + ".yml"), "notifications:\n"
                + notification + notification.replace("The mail body", "Another mail body"));
        assertDuplicateLegacyArchiveRejected("Duplicate notification ID");
    }

    @Test
    void duplicateSpawnAssignmentsCannotBeStaged() throws IOException {
        String assignment = """
                  - player: 00000000-0000-0000-0000-000000000002
                    completed: true
                    location: {world-uuid: 00000000-0000-0000-0000-000000000003, world: world, x: 12, y: 65, z: 14, yaw: 0}
                """;
        Files.writeString(dataFolder.resolve("new-player-spawns.yml"), "assignments:\n"
                + assignment + assignment.replace("x: 12", "x: 22"));
        assertDuplicateLegacyArchiveRejected("Duplicate spawn assignment for player");
    }

    @Test
    void duplicateYamlMappingKeysCannotBeStaged() throws IOException {
        Path directory = Files.createDirectory(dataFolder.resolve("userdata"));
        Files.writeString(directory.resolve(UUID.randomUUID() + ".yml"), """
                home:
                  Mine: {world: world, x: 12, y: 65, z: 14}
                  Mine: {world: world, x: 55, y: 65, z: 14}
                """);
        assertDuplicateLegacyArchiveRejected("Duplicate YAML key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"config.yml", "messages_en.yml", "messages_fi.yml"})
    void duplicateYamlMappingKeysInArchivedSettingsCannotBeStaged(String filename) throws IOException {
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        String content = filename.equals("config.yml") ? "home:\n  max-amount: 1\n  max-amount: 2\n"
                : "admin:\n  backup:\n    valid: First\n    valid: Second\n";
        Files.writeString(dataFolder.resolve(filename), content);
        assertDuplicateLegacyArchiveRejected("Duplicate YAML key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"config.yml", "messages_en.yml", "messages_fi.yml"})
    void dottedYamlKeysCannotOverwriteNestedArchiveSettings(String filename) throws IOException {
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        String content = filename.equals("config.yml") ? "home:\n  max-amount: 1\nhome.max-amount: 2\n"
                : "admin:\n  backup:\n    valid: First\nadmin.backup.valid: Second\n";
        Files.writeString(dataFolder.resolve(filename), content);
        assertDuplicateLegacyArchiveRejected("Conflicting YAML keys");
    }

    @Test
    void yamlAliasesCannotExpandBeyondTheVerificationLimit() throws IOException {
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        StringBuilder config = new StringBuilder("a0: &a0 {leaf: value}\n");
        for (int level = 1; level < 24; level++) {
            config.append("a%d: &a%d {left: *a%d, right: *a%d}\n".formatted(level, level, level - 1, level - 1));
        }
        Files.writeString(dataFolder.resolve("config.yml"), config);
        assertDuplicateLegacyArchiveRejected("YAML alias expansion exceeds the safety limit");
    }

    @Test
    void oversizedYamlCollectionCannotReachTheComposer() throws IOException {
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        Files.writeString(dataFolder.resolve("config.yml"), "unused: [" + "x,".repeat(250_000) + "x]\n");
        assertDuplicateLegacyArchiveRejected("YAML document exceeds the parser event limit");
    }

    @Test
    void oversizedYamlFileCannotBeArchivedOrStaged() throws IOException {
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        Path oversized = Files.write(dataFolder.resolve("config.yml"), new byte[StrictYaml.MAX_FILE_BYTES + 1]);
        BackupService backups = service(10);
        IOException failure = assertThrows(IOException.class, () -> backups.create("manual"));
        assertTrue(failure.getMessage().contains("YAML file exceeds the 16 MiB safety limit"));
        assertTrue(backups.archives().isEmpty());

        Path archive = dataFolder.resolve("backups/oversized.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("config.yml"));
            Files.copy(oversized, zip);
        }
        var invalid = backups.checkRestore("oversized.zip");
        assertFalse(invalid.valid());
        assertTrue(invalid.problem().contains("YAML file exceeds the 16 MiB safety limit"), invalid.problem());
        assertThrows(IOException.class, () -> backups.prepareRestore("oversized.zip"));
        assertFalse(backups.hasPendingRestore());
    }

    private void assertDuplicateLegacyArchiveRejected(String problem) throws IOException {
        BackupService backups = service(10);
        String filename = backups.create("legacy").orElseThrow().getFileName().toString();

        var invalid = backups.verify(filename);
        assertFalse(invalid.valid());
        assertTrue(invalid.problem().contains(problem), invalid.problem());
        assertThrows(IOException.class, () -> stageRestore(backups, filename));
        assertFalse(backups.hasPendingRestore());
    }

    @Test
    void configOnlyRestoreCannotReplaceTheOriginalDatabase() throws IOException {
        BackupService backups = service(10);
        Files.writeString(dataFolder.resolve("config.yml"), "example: true\n");
        Path archive = backups.create("config-only").orElseThrow();
        createSqlite("survivaltweaks.db");
        Path database = dataFolder.resolve("survivaltweaks.db");
        byte[] original = Files.readAllBytes(database);

        String filename = archive.getFileName().toString();
        assertFalse(backups.verify(filename).valid());
        assertThrows(IOException.class, () -> stageRestore(backups, filename));
        Files.copy(archive, dataFolder.resolve(".restore-pending.zip"));
        assertEquals(BackupService.RestoreResult.REJECTED, backups.applyPendingRestore());
        assertArrayEquals(original, Files.readAllBytes(database));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void differentlyCasedDatabaseEntryRestoresOnceAndRollsBackOnFailure() throws IOException {
        createSqlite("survivaltweaks.db");
        Path database = dataFolder.resolve("survivaltweaks.db");
        byte[] original = Files.readAllBytes(database);
        Path archive = Files.createDirectories(dataFolder.resolve("backups")).resolve("case-alias.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("SurvivalTweaks.db"));
            zip.write(original);
        }
        BackupService backups = service(10);

        assertTrue(stageRestore(backups, archive.getFileName().toString()).valid());
        assertEquals(BackupService.RestoreResult.PREPARED, backups.applyPendingRestore());
        assertArrayEquals(original, Files.readAllBytes(database));
        Files.writeString(database, "partially initialized restore");
        backups.rollbackFailedRestore(new IOException("Could not open restored storage"));

        assertArrayEquals(original, Files.readAllBytes(database));
        assertFalse(backups.hasPendingRestore());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void lockedLiveDatabaseRollsBackMovedFilesAndLeavesRestorePending() throws IOException {
        createSqlite("survivaltweaks.db");
        Path config = dataFolder.resolve("config.yml");
        Path locks = dataFolder.resolve("locked-containers.yml");
        Files.writeString(locks, LEGACY_LOCK);
        BackupService backups = service(10);
        Path archive = backups.create("good").orElseThrow();
        Files.writeString(config, "original: true\n");
        Files.writeString(locks, "locks: []\n");
        Path database = dataFolder.resolve("survivaltweaks.db");
        byte[] original = Files.readAllBytes(database);
        stageRestore(backups, archive.getFileName().toString());

        try (var ignored = Files.newByteChannel(database, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
            assertEquals(BackupService.RestoreResult.DEFERRED, backups.applyPendingRestore());
            assertTrue(backups.hasPendingRestore());
            assertArrayEquals(original, Files.readAllBytes(database));
            assertEquals("original: true\n", Files.readString(config));
            assertEquals("locks: []\n", Files.readString(locks));
        }

        assertEquals(BackupService.RestoreResult.PREPARED, backups.applyPendingRestore());
    }

    @Test
    void unpinnedArchiveMustAgreeWithItsLegacyDataBeforeStaging() throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        Path locks = dataFolder.resolve("locked-containers.yml");
        Files.writeString(locks, LEGACY_LOCK);
        try (StorageManager ignored = StorageManager.open(config, dataFolder, Logger.getAnonymousLogger(), name -> null)) {
            // Import the lock so both copies initially match.
        }
        Files.delete(dataFolder.resolve("storage-state.yml"));
        BackupService backups = service(10);
        Path matching = backups.create("matching").orElseThrow();
        assertTrue(backups.verify(matching.getFileName().toString()).valid());

        Files.writeString(locks, LEGACY_LOCK.replace("x: 1", "x: 3"));
        Path conflicting = backups.create("conflicting").orElseThrow();
        String filename = conflicting.getFileName().toString();
        BackupService.Verification verification = backups.verify(filename);
        assertFalse(verification.valid());
        assertTrue(verification.problem().contains("does not match the preserved YAML files"));
        assertThrows(IOException.class, () -> stageRestore(backups, filename));
        assertFalse(backups.hasPendingRestore());
    }

    @Test
    void restoresOriginalDatabaseIfOpeningRestoredStorageFails() throws IOException {
        Files.writeString(dataFolder.resolve("config.yml"), "snapshot: true\n");
        Files.writeString(dataFolder.resolve("locked-containers.yml"), LEGACY_LOCK);
        Path userdata = Files.createDirectory(dataFolder.resolve("userdata"));
        Path incomingProfile = userdata.resolve(UUID.randomUUID() + ".yml");
        Path originalProfile = userdata.resolve(UUID.randomUUID() + ".yml");
        Path unrelated = userdata.resolve("operator-notes.txt");
        Files.writeString(incomingProfile, "archived: true\n");
        BackupService backups = service(10);
        String archive = backups.create("legacy").orElseThrow().getFileName().toString();
        Files.delete(incomingProfile);
        Files.writeString(originalProfile, "original: true\n");
        Files.writeString(unrelated, "keep these notes\n");
        createSqlite("survivaltweaks.db");
        Path database = dataFolder.resolve("survivaltweaks.db");
        byte[] original = Files.readAllBytes(database);

        stageRestore(backups, archive);
        assertEquals(BackupService.RestoreResult.PREPARED, backups.applyPendingRestore());
        assertTrue(backups.hasPendingRestore());
        assertFalse(Files.exists(database));
        assertTrue(Files.exists(incomingProfile));
        assertFalse(Files.exists(originalProfile));
        Files.writeString(database, "partially initialized restore");
        backups.rollbackFailedRestore(new IOException("Could not open restored storage"));

        assertFalse(backups.hasPendingRestore());
        assertArrayEquals(original, Files.readAllBytes(database));
        assertFalse(Files.exists(incomingProfile));
        assertEquals("original: true\n", Files.readString(originalProfile));
        assertEquals("keep these notes\n", Files.readString(unrelated));
        var config = YamlConfiguration.loadConfiguration(dataFolder.resolve("config.yml").toFile());
        try (var storage = StorageManager.open(config, dataFolder, Logger.getAnonymousLogger(), name -> null)) {
            assertTrue(storage.verify().healthy());
        }
    }

    private void createSqlite(String filename) throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.sqlite.file", filename);
        config.save(dataFolder.resolve("config.yml").toFile());
        try (StorageManager ignored = StorageManager.open(config, dataFolder, Logger.getAnonymousLogger(), name -> null)) {
            // Create and pin a real database as startup would.
        }
    }

    private BackupService.Verification stageRestore(BackupService backups, String filename) throws IOException {
        try (var prepared = backups.prepareRestore(filename)) {
            return prepared.publish();
        }
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

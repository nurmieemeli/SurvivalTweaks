package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.storage.LegacyYamlFixtures.ProfileFiles;
import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.ProfileSnapshot;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageManagerTest {

    @TempDir
    Path directory;

    private final Logger logger = Logger.getLogger(StorageManagerTest.class.getName());

    @Test
    void snapshotsAndBackupsWaitForPersistenceBarrier() throws IOException {
        AtomicInteger flushes = new AtomicInteger();
        try (StorageManager manager = StorageManager.open(
                sqliteConfig("barrier.db"),
                directory,
                logger,
                ignored -> UUID.randomUUID()
        )) {
            manager.persistenceBarrier(timeout -> flushes.incrementAndGet());

            manager.exportSnapshot();
            manager.exportPortable("barrier-test");
            try (SqlStorage.SnapshotLease ignored = manager.acquireSnapshotLease()) {
                assertEquals(3, flushes.get());
            }
        }
    }

    @Test
    void failedPersistenceBarrierAbortsPortableExport() throws IOException {
        try (StorageManager manager = StorageManager.open(
                sqliteConfig("barrier-failure.db"),
                directory,
                logger,
                ignored -> UUID.randomUUID()
        )) {
            manager.persistenceBarrier(timeout -> {
                throw new IOException("pending writes could not be flushed");
            });

            IOException failure = assertThrows(
                    IOException.class,
                    () -> manager.exportPortable("barrier-failure")
            );

            assertTrue(failure.getMessage().contains("pending writes"));
            assertFalse(Files.exists(
                    directory.resolve("storage-exports").resolve("barrier-failure")
            ));
        }
    }

    @Test
    void pinnedEmptyDatabaseDoesNotImportLegacyHomesOnRestart() throws IOException {
        var config = sqliteConfig("empty.db");
        try (var initial = StorageManager.open(config, directory, logger, ignored -> null)) {
            assertTrue(initial.exportSnapshot().empty());
        }
        UUID playerId = UUID.randomUUID();
        new ProfileFiles(directory.resolve("userdata"), logger).save(new ProfileSnapshot(playerId,
                List.of(new Home("old-home", UUID.randomUUID(), "world", 1, 64, 1, 0, 0))));

        try (var reopened = StorageManager.open(config, directory, logger, ignored -> null)) {
            assertFalse(reopened.importedLegacyAtStartup());
            assertTrue(reopened.exportSnapshot().empty());
        }
    }

    @Test
    void importsLegacyYamlOnceAndPinsTheDatabaseEndpoint() throws IOException {
        UUID playerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        ProfileFiles yaml = new ProfileFiles(directory.resolve("userdata"), logger);
        yaml.save(new ProfileSnapshot(
                playerId,
                List.of(new Home("base", worldId, "world", 1, 64, 2, 0, 0))
        ));

        YamlConfiguration config = sqliteConfig("survivaltweaks.db");
        try (StorageManager manager =
                     StorageManager.open(config, directory, logger, ignored -> worldId)) {
            assertTrue(manager.importedLegacyAtStartup());
            assertEquals(1, manager.exportSnapshot().profiles().size());
            assertTrue(manager.verify().healthy());
        }
        assertTrue(Files.isRegularFile(directory.resolve("storage-state.yml")));
        assertTrue(Files.isRegularFile(directory.resolve("userdata").resolve(playerId + ".yml")));

        try (StorageManager reopened =
                     StorageManager.open(config, directory, logger, ignored -> worldId)) {
            assertFalse(reopened.importedLegacyAtStartup());
            assertEquals(1, reopened.exportSnapshot().profiles().size());
        }

        YamlConfiguration drifted = sqliteConfig("different.db");
        IOException failure = assertThrows(
                IOException.class,
                () -> StorageManager.open(drifted, directory, logger, ignored -> worldId)
        );
        assertTrue(failure.getMessage().contains("pinned endpoint"));
    }

    @Test
    void recoversAVerifiedImportInterruptedBeforeItsStateWasPinned() throws IOException {
        UUID playerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        Instant precise = Instant.parse("2026-07-30T12:00:00.123456789Z");
        ProfileSnapshot legacy = new ProfileSnapshot(
                playerId,
                List.of(new Home("base", worldId, "world", 1, 64, 2, 0, 0)),
                gg.nurmi.survivaltweaks.object.PlayerPreferences.DEFAULTS,
                Set.of(),
                List.of(),
                "Emeli",
                precise,
                10,
                Set.of(),
                Set.of()
        );
        new ProfileFiles(directory.resolve("userdata"), logger).save(legacy);
        StorageConfiguration configuration =
                StorageConfiguration.load(sqliteConfig("survivaltweaks.db"), directory);
        try (SqlStorage interrupted = new SqlStorage(configuration, logger)) {
            interrupted.replaceAll(
                    new StorageSnapshot(
                            List.of(legacy),
                            List.of(),
                            List.of(),
                            gg.nurmi.survivaltweaks.object.NewPlayerSpawnState.EMPTY
                    ),
                    UUID.randomUUID()
            );
        }

        try (StorageManager manager = StorageManager.open(
                sqliteConfig("survivaltweaks.db"),
                directory,
                logger,
                ignored -> worldId
        )) {
            assertTrue(manager.importedLegacyAtStartup());
            assertEquals(1, manager.exportSnapshot().profiles().size());
        }
        assertTrue(Files.isRegularFile(directory.resolve("storage-state.yml")));
    }

    @Test
    void emptyLegacyFilesDoNotOverrideAnExistingUnpinnedDatabase() throws IOException {
        UUID playerId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        Home home = new Home("base", UUID.randomUUID(), "world", 1, 64, 2, 0, 0);
        YamlConfiguration config = sqliteConfig("survivaltweaks.db");
        try (SqlStorage existing = new SqlStorage(StorageConfiguration.load(config, directory), logger)) {
            existing.ensureInstanceId(instanceId);
            existing.save(new ProfileSnapshot(playerId, List.of(home)));
        }
        Path userdata = Files.createDirectories(directory.resolve("userdata"));
        Files.createFile(userdata.resolve(playerId + ".yml"));

        try (StorageManager manager = StorageManager.open(config, directory, logger, name -> null)) {
            assertFalse(manager.importedLegacyAtStartup());
            assertEquals(instanceId, manager.store().instanceId());
            assertEquals(List.of(home), manager.store().load(playerId).homes());
        }
    }

    @Test
    void portableExportCanBeReopenedAndRestoredWithoutLogicalChanges() throws IOException {
        UUID playerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        ProfileSnapshot profile = new ProfileSnapshot(
                playerId,
                List.of(new Home("base", worldId, "world", 1, 64, 2, 0, 0))
        );
        YamlConfiguration sourceConfig = sqliteConfig("source.db");
        StorageSnapshot expected;
        StorageManager.ExportResult exported;
        try (StorageManager source = StorageManager.open(
                sourceConfig, directory, logger, ignored -> worldId
        )) {
            source.store().save(profile);
            expected = source.exportSnapshot();
            exported = source.exportPortable("automatic");
        }

        assertTrue(Files.isRegularFile(exported.file()));
        try (var files = Files.list(exported.file().getParent())) {
            assertTrue(files.noneMatch(path ->
                    path.getFileName().toString().startsWith(".storage-export-")));
        }
        StorageConfiguration portableConfiguration = new StorageConfiguration(
                StorageBackend.SQLITE, exported.file(), "", 5432, "", "survivaltweaks",
                "", "", false, "disable", "verify-identity", 1, 5_000, 30, 30
        );
        StorageSnapshot reopened;
        UUID instanceId;
        try (SqlStorage portable = new SqlStorage(portableConfiguration, logger)) {
            reopened = portable.exportSnapshot();
            instanceId = portable.instanceId();
        }
        assertEquals(expected.counts(), reopened.counts());
        assertEquals(exported.checksum(), StorageChecksum.calculate(reopened));

        try (SqlStorage restored = new SqlStorage(
                new StorageConfiguration(
                        StorageBackend.SQLITE, directory.resolve("restored.db"), "", 5432,
                        "", "survivaltweaks", "", "", false, "disable", "verify-identity", 1, 5_000, 30, 30
                ),
                logger
        )) {
            restored.replaceAll(reopened, instanceId);
            assertEquals(exported.checksum(), StorageChecksum.calculate(restored.exportSnapshot()));
            assertTrue(restored.verify().healthy());
        }
    }

    @Test
    void completesMigrationCleanupAfterStateWasAlreadySwitched() throws IOException {
        YamlConfiguration config = sqliteConfig("survivaltweaks.db");
        StorageConfiguration configuration = StorageConfiguration.load(config, directory);
        UUID instanceId = UUID.randomUUID();
        try (SqlStorage target = new SqlStorage(configuration, logger)) {
            target.ensureInstanceId(instanceId);
        }
        StorageStateStore states = new StorageStateStore(directory);
        StorageStateStore.Migration migration = new StorageStateStore.Migration(
                UUID.randomUUID(),
                StorageBackend.POSTGRESQL,
                StorageBackend.SQLITE,
                configuration.endpointFingerprint()
        );
        states.saveState(new StorageStateStore.State(
                StorageBackend.SQLITE,
                configuration.endpointFingerprint(),
                instanceId
        ));
        states.stage(migration);

        try (StorageManager manager = StorageManager.open(
                config,
                directory,
                logger,
                ignored -> UUID.randomUUID()
        )) {
            assertEquals(StorageBackend.SQLITE, manager.backend());
            assertTrue(manager.verify().healthy());
        }
        assertFalse(Files.exists(directory.resolve("storage-migration.yml")));
    }

    @Test
    void malformedLegacyEntriesAbortImportWithoutDeletingOriginals() throws IOException {
        Path userdata = directory.resolve("userdata");
        Files.createDirectories(userdata);
        UUID playerId = UUID.randomUUID();
        Files.writeString(userdata.resolve(playerId + ".yml"), """
                schema-version: 6
                homes:
                  - name: broken
                    world: world
                    x: not-a-number
                    y: 64
                    z: 0
                    yaw: 0
                    pitch: 0
                """);

        IOException failure = assertThrows(
                IOException.class,
                () -> StorageManager.open(
                        sqliteConfig("survivaltweaks.db"),
                        directory,
                        logger,
                        ignored -> UUID.randomUUID()
                )
        );

        assertTrue(failure.getMessage().contains("malformed"));
        assertTrue(Files.exists(userdata.resolve(playerId + ".yml")));
    }

    @Test
    void refusesToCreateAReplacementForAPinnedSqliteFile() throws IOException {
        YamlConfiguration config = sqliteConfig("players.db");
        Path database = directory.resolve("players.db");
        try (StorageManager ignored = StorageManager.open(config, directory, logger, name -> null)) {
            assertTrue(Files.isRegularFile(database));
        }
        Files.move(database, directory.resolve("original.db"));
        IOException failure = assertThrows(IOException.class,
                () -> StorageManager.open(config, directory, logger, name -> null));
        assertTrue(failure.getMessage().contains("pinned SQLite database is missing"));
        assertFalse(Files.exists(database));

        Files.createFile(database);
        assertThrows(IOException.class, () -> StorageManager.open(config, directory, logger, name -> null));
        assertEquals(0, Files.size(database));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAStaleSqliteCopyWithTheSameInstanceId(boolean legacyPin) throws IOException {
        YamlConfiguration original = sqliteConfig("players.db");
        YamlConfiguration stale = sqliteConfig("older-copy.db");
        StorageConfiguration configuration = StorageConfiguration.load(original, directory);
        UUID instance;
        try (StorageManager storage = StorageManager.open(original, directory, logger, name -> null)) {
            instance = storage.store().instanceId();
            assertThrows(IllegalArgumentException.class, () -> storage.verifyReloadConfiguration(stale));
        }
        Files.copy(directory.resolve("players.db"), directory.resolve("older-copy.db"));
        StorageStateStore states = new StorageStateStore(directory);
        if (legacyPin) {
            states.saveState(new StorageStateStore.State(
                    StorageBackend.SQLITE, configuration.legacyEndpointFingerprint(), instance));
        }
        assertEquals(configuration.sqliteFile(), StorageManager.findPinnedSqliteFile(directory).orElseThrow());

        IOException failure = assertThrows(IOException.class,
                () -> StorageManager.open(stale, directory, logger, name -> null));
        assertTrue(failure.getMessage().contains("pinned endpoint"), failure.getMessage());
        try (StorageManager storage = StorageManager.open(original, directory, logger, name -> null)) {
            assertEquals(instance, storage.store().instanceId());
            assertEquals(configuration.endpointFingerprint(), states.loadState().orElseThrow().endpointFingerprint());
        }
    }

    @Test
    void relocatedSqliteRequiresTheExistingPinnedDatabaseIdentity() throws IOException {
        Path original = Files.createDirectory(directory.resolve("original"));
        Path relocated = Files.createDirectory(directory.resolve("relocated"));
        YamlConfiguration config = sqliteConfig("players.db");
        UUID instance;
        try (StorageManager storage = StorageManager.open(config, original, logger, name -> null)) {
            instance = storage.store().instanceId();
        }

        Files.copy(original.resolve("storage-state.yml"), relocated.resolve("storage-state.yml"));
        assertThrows(IOException.class, () -> StorageManager.open(config, relocated, logger, name -> null));
        assertFalse(Files.exists(relocated.resolve("players.db")));
        try (StorageManager other = StorageManager.open(config, directory, logger, name -> null)) {
            assertTrue(!instance.equals(other.store().instanceId()));
        }
        Files.copy(directory.resolve("players.db"), relocated.resolve("players.db"));
        assertThrows(IOException.class, () -> StorageManager.open(config, relocated, logger, name -> null));
        Files.copy(original.resolve("players.db"), relocated.resolve("players.db"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (StorageManager storage = StorageManager.open(config, relocated, logger, name -> null)) {
            assertEquals(instance, storage.store().instanceId());
        }

        StorageConfiguration originalConfiguration = StorageConfiguration.load(config, original);
        StorageStateStore relocatedState = new StorageStateStore(relocated);
        relocatedState.saveState(new StorageStateStore.State(
                StorageBackend.SQLITE, originalConfiguration.legacyEndpointFingerprint(), instance));
        assertThrows(IOException.class, () -> StorageManager.open(config, relocated, logger, name -> null));
        StorageManager.verifyRestore(config, relocated, relocated, logger, name -> null);
        try (StorageManager restored = StorageManager.openRestored(config, relocated, logger, name -> null)) {
            assertEquals(instance, restored.store().instanceId());
        }
    }

    private static YamlConfiguration sqliteConfig(String filename) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.backend", "auto");
        config.set("storage.sqlite.file", filename);
        config.set("storage.remote.type", "");
        config.set("storage.remote.host", "localhost");
        config.set("storage.remote.port", 0);
        config.set("storage.remote.database", "survivaltweaks");
        config.set("storage.remote.username", "survivaltweaks");
        config.set("storage.remote.password", "");
        config.set("storage.remote.ssl", false);
        config.set("storage.remote.postgresql-schema", "survivaltweaks");
        config.set("storage.remote.postgresql-ssl-mode", "disable");
        config.set("storage.remote.pool-size", 4);
        config.set("storage.remote.connection-timeout-millis", 5_000);
        config.set("storage.remote.socket-timeout-seconds", 30);
        config.set("storage.remote.query-timeout-seconds", 30);
        return config;
    }
}

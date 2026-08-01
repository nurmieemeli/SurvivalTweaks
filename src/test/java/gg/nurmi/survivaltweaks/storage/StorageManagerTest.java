package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.ProfileSnapshot;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
    void importsLegacyYamlOnceAndPinsTheDatabaseEndpoint() throws IOException {
        UUID playerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        ProfileStore yaml = new ProfileStore(directory.resolve("userdata"), logger);
        yaml.save(new ProfileSnapshot(
                playerId,
                List.of(new Home("base", worldId, "world", 1, 64, 2, 0, 0))
        ));

        YamlConfiguration config = sqliteConfig("survivaltweaks.db");
        try (StorageManager manager =
                     StorageManager.open(config, directory, logger, ignored -> worldId)) {
            assertTrue(manager.startupImport().imported());
            assertEquals(1, manager.exportSnapshot().profiles().size());
            assertTrue(manager.verify().healthy());
        }
        assertTrue(Files.isRegularFile(directory.resolve("storage-state.yml")));
        assertTrue(Files.isRegularFile(directory.resolve("userdata").resolve(playerId + ".yml")));

        try (StorageManager reopened =
                     StorageManager.open(config, directory, logger, ignored -> worldId)) {
            assertFalse(reopened.startupImport().imported());
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
                Set.of()
        );
        new ProfileStore(directory.resolve("userdata"), logger).save(legacy);
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
            assertTrue(manager.startupImport().imported());
            assertEquals(1, manager.exportSnapshot().profiles().size());
        }
        assertTrue(Files.isRegularFile(directory.resolve("storage-state.yml")));
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
        StorageConfiguration portableConfiguration = new StorageConfiguration(
                StorageBackend.SQLITE, exported.file(), "", 5432, "", "survivaltweaks",
                "", "", false, "disable", 1, 5_000, 30, 30
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
                        "", "survivaltweaks", "", "", false, "disable", 1, 5_000, 30, 30
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

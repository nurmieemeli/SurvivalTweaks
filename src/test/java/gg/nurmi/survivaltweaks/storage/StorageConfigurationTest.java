package gg.nurmi.survivaltweaks.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageConfigurationTest {

    @TempDir
    Path directory;

    @Test
    void autoUsesSqliteWhenNoRemoteEngineIsConfigured() {
        YamlConfiguration config = base();

        StorageConfiguration loaded = StorageConfiguration.load(config, directory);

        assertEquals(StorageBackend.SQLITE, loaded.backend());
        assertEquals(directory.resolve("survivaltweaks.db").toAbsolutePath(), loaded.sqliteFile());
    }

    @Test
    void autoUsesTheConfiguredRemoteEngineAndItsDefaultPort() {
        YamlConfiguration config = base();
        config.set("storage.remote.type", "mysql");
        config.set("storage.remote.port", 0);

        StorageConfiguration loaded = StorageConfiguration.load(config, directory);

        assertEquals(StorageBackend.MYSQL, loaded.backend());
        assertEquals(3306, loaded.port());
    }

    @Test
    void rejectsEndpointDriftAndUnsafeSqlitePathsAtConfigurationBoundary() {
        YamlConfiguration disagreement = base();
        disagreement.set("storage.backend", "mysql");
        disagreement.set("storage.remote.type", "postgresql");
        assertThrows(
                IllegalArgumentException.class,
                () -> StorageConfiguration.load(disagreement, directory)
        );

        YamlConfiguration traversal = base();
        traversal.set("storage.sqlite.file", "../outside.db");
        assertThrows(
                IllegalArgumentException.class,
                () -> StorageConfiguration.load(traversal, directory)
        );
    }

    private static YamlConfiguration base() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.backend", "auto");
        config.set("storage.sqlite.file", "survivaltweaks.db");
        config.set("storage.remote.type", "");
        config.set("storage.remote.host", "localhost");
        config.set("storage.remote.port", 0);
        config.set("storage.remote.database", "survivaltweaks");
        config.set("storage.remote.username", "survivaltweaks");
        config.set("storage.remote.password", "");
        config.set("storage.remote.ssl", false);
        config.set("storage.remote.pool-size", 4);
        config.set("storage.remote.connection-timeout-millis", 5_000);
        return config;
    }
}

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
    void postgresqlSchemaParticipatesInEndpointIdentity() {
        YamlConfiguration first = base();
        first.set("storage.backend", "postgresql");
        first.set("storage.remote.type", "postgresql");
        first.set("storage.remote.postgresql-schema", "survivaltweaks_a");
        YamlConfiguration second = base();
        second.set("storage.backend", "postgresql");
        second.set("storage.remote.type", "postgresql");
        second.set("storage.remote.postgresql-schema", "survivaltweaks_b");

        StorageConfiguration left = StorageConfiguration.load(first, directory);
        StorageConfiguration right = StorageConfiguration.load(second, directory);

        org.junit.jupiter.api.Assertions.assertNotEquals(
                left.endpointFingerprint(),
                right.endpointFingerprint()
        );
        assertEquals(left.legacyEndpointFingerprint(), right.legacyEndpointFingerprint());
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

    @Test
    void mysqlValidatesTheServerCertificateUnlessTlsIsTurnedOff() {
        YamlConfiguration verified = base();
        verified.set("storage.remote.type", "mysql");
        verified.set("storage.remote.ssl", true);

        StorageConfiguration secure = StorageConfiguration.load(verified, directory);

        assertEquals("VERIFY_IDENTITY", secure.mysqlSslModeProperty());
        // The legacy useSSL/requireSSL pair encrypts without ever checking the
        // certificate, so it must not come back into the URL.
        org.junit.jupiter.api.Assertions.assertFalse(secure.jdbcUrl().contains("useSSL"));
        org.junit.jupiter.api.Assertions.assertFalse(secure.jdbcUrl().contains("requireSSL"));

        YamlConfiguration plaintext = base();
        plaintext.set("storage.remote.type", "mysql");
        plaintext.set("storage.remote.ssl", false);

        assertEquals(
                "DISABLED",
                StorageConfiguration.load(plaintext, directory).mysqlSslModeProperty()
        );
    }

    @Test
    void rejectsAnUnknownMysqlSslMode() {
        YamlConfiguration config = base();
        config.set("storage.remote.type", "mysql");
        config.set("storage.remote.ssl", true);
        config.set("storage.remote.mysql-ssl-mode", "trust-everything");

        assertThrows(
                IllegalArgumentException.class,
                () -> StorageConfiguration.load(config, directory)
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
        config.set("storage.remote.postgresql-schema", "survivaltweaks");
        config.set("storage.remote.postgresql-ssl-mode", "disable");
        config.set("storage.remote.mysql-ssl-mode", "verify-identity");
        config.set("storage.remote.pool-size", 4);
        config.set("storage.remote.connection-timeout-millis", 5_000);
        config.set("storage.remote.socket-timeout-seconds", 30);
        config.set("storage.remote.query-timeout-seconds", 30);
        return config;
    }
}

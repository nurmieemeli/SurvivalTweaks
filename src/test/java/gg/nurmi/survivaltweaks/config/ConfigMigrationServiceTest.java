package gg.nurmi.survivaltweaks.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigrationServiceTest {

    @TempDir
    Path dataFolder;

    @Test
    void legacyConfigurationIsVersionedAndObsoleteSettingsAreRemoved() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("home.max-amount", 3);
        config.set("disabled-commands", java.util.List.of("seed"));
        config.set("death-recovery.give-compass", true);
        ConfigMigrationService migrations = service();

        ConfigMigrationService.Result result = migrations.migrate(config, dataFolder);

        assertTrue(result.changed());
        assertEquals(ConfigMigrationService.CURRENT_VERSION, config.getInt("config-version"));
        assertEquals("public", config.getString("storage.remote.postgresql-schema"));
        assertEquals("require", config.getString("storage.remote.postgresql-ssl-mode"));
        assertFalse(config.contains("disabled-commands", true));
        assertFalse(config.contains("death-recovery.give-compass", true));
        assertTrue(Files.readString(dataFolder.resolve("config-migration-report.txt"))
                .contains("Schema: 0 -> " + ConfigMigrationService.CURRENT_VERSION));
        assertEquals("en", config.getString("default-language"));
        assertTrue(config.getBoolean("storage.portable-exports.enabled"));
        assertEquals(24, config.getInt("storage.portable-exports.interval-hours"));
        assertEquals(5, config.getInt("storage.portable-exports.initial-delay-minutes"));
        assertEquals(7, config.getInt("storage.portable-exports.retention"));
    }

    @Test
    void versionTwoPreservesItsPostgresqlLocationAndTlsSemantics() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("config-version", 2);
        config.set("storage.remote.ssl", false);

        service().migrate(config, dataFolder);

        assertEquals("public", config.getString("storage.remote.postgresql-schema"));
        assertEquals("disable", config.getString("storage.remote.postgresql-ssl-mode"));
        assertEquals(30, config.getInt("storage.remote.socket-timeout-seconds"));
        assertEquals(30, config.getInt("storage.remote.query-timeout-seconds"));
    }

    @Test
    void versionFivePreservesItsUnverifiedMysqlTlsSemantics() throws Exception {
        YamlConfiguration encrypted = new YamlConfiguration();
        encrypted.set("config-version", 5);
        encrypted.set("storage.remote.ssl", true);

        service().migrate(encrypted, dataFolder);

        // Version 5 encrypted without validating the certificate. Upgrading must
        // not turn a working connection into a failing one.
        assertEquals("required", encrypted.getString("storage.remote.mysql-ssl-mode"));

        YamlConfiguration plaintext = new YamlConfiguration();
        plaintext.set("config-version", 5);
        plaintext.set("storage.remote.ssl", false);

        service().migrate(plaintext, dataFolder);

        assertEquals("verify-identity", plaintext.getString("storage.remote.mysql-ssl-mode"));
    }

    @Test
    void currentConfigurationIsLeftUntouched() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("config-version", ConfigMigrationService.CURRENT_VERSION);

        ConfigMigrationService.Result result = service().migrate(config, dataFolder);

        assertFalse(result.changed());
        assertFalse(Files.exists(dataFolder.resolve("config-migration-report.txt")));
    }

    @Test
    void newerConfigurationIsRejected() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("config-version", ConfigMigrationService.CURRENT_VERSION + 1);

        assertThrows(IllegalStateException.class, () -> service().migrate(config, dataFolder));
    }

    private ConfigMigrationService service() {
        return new ConfigMigrationService(
                Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC),
                Logger.getLogger("ConfigMigrationServiceTest")
        );
    }
}

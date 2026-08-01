package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.storage.StorageManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PortableExportServiceTest {

    @TempDir
    Path directory;

    @Test
    void manualRunsAreVerifiedAndRetentionKeepsOnlyNewestExports() throws Exception {
        try (StorageManager storage = StorageManager.open(
                sqliteConfig(), directory, Logger.getLogger("PortableExportServiceTest"),
                ignored -> UUID.randomUUID()
        ); PortableExportService exports = new PortableExportService(
                storage, Logger.getLogger("PortableExportServiceTest")
        )) {
            exports.reconfigure(new PortableExportService.Settings(
                    true, Duration.ofHours(1), Duration.ofMinutes(1), 2
            ));
            exports.exportNow();
            exports.exportNow();
            exports.exportNow();

            try (var files = Files.list(
                    directory.resolve("storage-exports").resolve("automatic")
            )) {
                assertEquals(2, files.filter(Files::isRegularFile).count());
            }
        }
    }

    @Test
    void invalidSettingsAreRejectedBeforeReload() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.portable-exports.interval-hours", 0);
        config.set("storage.portable-exports.initial-delay-minutes", 5);
        config.set("storage.portable-exports.retention", 7);

        assertThrows(IllegalArgumentException.class, () -> PortableExportService.validate(config));
    }

    private static YamlConfiguration sqliteConfig() {
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
        config.set("storage.remote.pool-size", 4);
        config.set("storage.remote.connection-timeout-millis", 5_000);
        config.set("storage.remote.socket-timeout-seconds", 30);
        config.set("storage.remote.query-timeout-seconds", 30);
        return config;
    }
}

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
        assertEquals(2, config.getInt("config-version"));
        assertFalse(config.contains("disabled-commands", true));
        assertFalse(config.contains("death-recovery.give-compass", true));
        assertTrue(Files.readString(dataFolder.resolve("config-migration-report.txt"))
                .contains("Schema: 0 -> 2"));
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

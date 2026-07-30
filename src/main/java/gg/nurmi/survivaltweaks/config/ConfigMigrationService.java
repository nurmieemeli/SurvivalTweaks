package gg.nurmi.survivaltweaks.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

public final class ConfigMigrationService {

    public static final int CURRENT_VERSION = 2;
    private static final List<String> VERSION_ONE_OBSOLETE_PATHS = List.of(
            "disabled-commands",
            "commands.disabled",
            "commands.disable",
            "death-recovery.compass",
            "death-recovery.recovery-compass",
            "death-recovery.give-compass"
    );

    private final Clock clock;
    private final Logger logger;

    public ConfigMigrationService(Clock clock, Logger logger) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public Result migrate(FileConfiguration config, Path dataFolder) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(dataFolder, "dataFolder");
        int sourceVersion = configuredVersion(config);
        if (sourceVersion > CURRENT_VERSION) {
            throw new IllegalStateException(
                    "config.yml uses schema " + sourceVersion
                            + ", but this build only supports up to " + CURRENT_VERSION
            );
        }
        if (sourceVersion == CURRENT_VERSION) {
            return new Result(sourceVersion, sourceVersion, List.of());
        }

        List<String> changes = new ArrayList<>();
        int version = sourceVersion;
        if (version == 0) {
            for (String path : VERSION_ONE_OBSOLETE_PATHS) {
                if (config.contains(path, true)) {
                    config.set(path, null);
                    changes.add("Removed obsolete setting: " + path);
                }
            }
            version = 1;
            changes.add("Recorded configuration schema version 1.");
        }
        if (version == 1) {
            setIfMissing(config, "storage.backend", "auto", changes);
            setIfMissing(config, "storage.sqlite.file", "survivaltweaks.db", changes);
            setIfMissing(config, "storage.remote.type", "", changes);
            setIfMissing(config, "storage.remote.host", "localhost", changes);
            setIfMissing(config, "storage.remote.port", 0, changes);
            setIfMissing(config, "storage.remote.database", "survivaltweaks", changes);
            setIfMissing(config, "storage.remote.username", "survivaltweaks", changes);
            setIfMissing(config, "storage.remote.password", "", changes);
            setIfMissing(config, "storage.remote.ssl", true, changes);
            setIfMissing(config, "storage.remote.pool-size", 4, changes);
            setIfMissing(
                    config,
                    "storage.remote.connection-timeout-millis",
                    5_000,
                    changes
            );
            version = 2;
            changes.add("Recorded configuration schema version 2.");
        }
        config.set("config-version", version);

        Result result = new Result(sourceVersion, version, List.copyOf(changes));
        writeReport(dataFolder.resolve("config-migration-report.txt"), result, clock.instant());
        logger.info("Migrated config.yml schema " + sourceVersion + " -> " + version
                + " (" + changes.size() + " change(s)); details: config-migration-report.txt");
        return result;
    }

    public static void requireCurrent(FileConfiguration config) {
        int version = configuredVersion(config);
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "config-version must be " + CURRENT_VERSION + " (found " + version
                            + "); restart the server once to migrate config.yml safely"
            );
        }
    }

    static int configuredVersion(FileConfiguration config) {
        if (!config.getValues(false).containsKey("config-version")) {
            return 0;
        }
        Object configured = config.get("config-version");
        if (!(configured instanceof Number number)
                || number.doubleValue() != Math.rint(number.doubleValue())
                || number.intValue() < 0) {
            throw new IllegalArgumentException("config-version must be a non-negative integer");
        }
        return number.intValue();
    }

    private void setIfMissing(
            FileConfiguration config,
            String path,
            Object value,
            List<String> changes
    ) {
        if (!config.contains(path, true)) {
            config.set(path, value);
            changes.add("Added storage setting: " + path);
        }
    }

    private void writeReport(Path target, Result result, Instant migratedAt) throws IOException {
        Files.createDirectories(target.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("SurvivalTweaks configuration migration");
        lines.add("Migrated at: " + migratedAt);
        lines.add("Schema: " + result.fromVersion() + " -> " + result.toVersion());
        lines.add("");
        lines.addAll(result.changes());
        Files.write(target, lines, StandardCharsets.UTF_8);
    }

    public record Result(int fromVersion, int toVersion, List<String> changes) {

        public boolean changed() {
            return fromVersion != toVersion;
        }
    }
}

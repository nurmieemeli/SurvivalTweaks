package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.ProfileSnapshot;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

final class LegacyYamlImporter {

    private final Path dataFolder;
    private final Logger logger;
    private final Function<String, UUID> worldResolver;

    LegacyYamlImporter(
            Path dataFolder,
            Logger logger,
            Function<String, UUID> worldResolver
    ) {
        this.dataFolder = dataFolder.toAbsolutePath().normalize();
        this.logger = logger;
        this.worldResolver = worldResolver;
    }

    boolean hasData() throws IOException {
        Path profiles = dataFolder.resolve("userdata");
        if (Files.isDirectory(profiles)) {
            try (Stream<Path> files = Files.list(profiles)) {
                if (files.anyMatch(this::isProfileFile)) {
                    return true;
                }
            }
        }
        return Files.isRegularFile(dataFolder.resolve("locked-containers.yml"))
                || Files.isRegularFile(dataFolder.resolve("death-markers.yml"))
                || Files.isRegularFile(dataFolder.resolve("new-player-spawns.yml"));
    }

    StorageSnapshot read() throws IOException {
        List<String> rejected = new ArrayList<>();
        Logger importLogger = Logger.getAnonymousLogger();
        importLogger.setUseParentHandlers(false);
        importLogger.setLevel(Level.ALL);
        importLogger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logger.log(record);
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    rejected.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        List<ProfileSnapshot> profiles = readProfiles(importLogger);
        Path locks = dataFolder.resolve("locked-containers.yml");
        Path deaths = dataFolder.resolve("death-markers.yml");
        Path spawns = dataFolder.resolve("new-player-spawns.yml");
        validateIfPresent(locks);
        validateIfPresent(deaths);
        validateIfPresent(spawns);
        StorageSnapshot snapshot = new StorageSnapshot(
                profiles,
                new ContainerLockStore(locks, importLogger).loadLocks(),
                new DeathMarkerStore(deaths, importLogger).loadDeathMarkers(),
                new NewPlayerSpawnStore(spawns, importLogger).loadSpawnState()
        );
        if (!rejected.isEmpty()) {
            throw new IOException(
                    "Legacy YAML import rejected " + rejected.size()
                            + " malformed value(s); original files were preserved. First problem: "
                            + rejected.getFirst()
            );
        }
        return snapshot;
    }

    private List<ProfileSnapshot> readProfiles(Logger importLogger) throws IOException {
        Path directory = dataFolder.resolve("userdata");
        if (Files.notExists(directory)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> listed = Files.list(directory)) {
            files = listed.filter(this::isProfileFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        ProfileStore store = new ProfileStore(directory, importLogger, worldResolver);
        List<ProfileSnapshot> profiles = new ArrayList<>();
        for (Path file : files) {
            validateIfPresent(file);
            String filename = file.getFileName().toString();
            try {
                UUID playerId = UUID.fromString(filename.substring(0, filename.length() - 4));
                profiles.add(store.load(playerId).snapshot());
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid profile filename " + filename, exception);
            }
        }
        return List.copyOf(profiles);
    }

    private boolean isProfileFile(Path path) {
        String name = path.getFileName().toString();
        return Files.isRegularFile(path)
                && name.endsWith(".yml")
                && name.length() == 40;
    }

    private void validateIfPresent(Path path) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IOException("Could not parse legacy file " + path.getFileName(), exception);
        }
    }
}

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
        List<ProfileSnapshot> profiles = readProfiles();
        Path locks = dataFolder.resolve("locked-containers.yml");
        Path deaths = dataFolder.resolve("death-markers.yml");
        Path spawns = dataFolder.resolve("new-player-spawns.yml");
        validateIfPresent(locks);
        validateIfPresent(deaths);
        validateIfPresent(spawns);
        return new StorageSnapshot(
                profiles,
                new ContainerLockStore(locks, logger).loadLocks(),
                new DeathMarkerStore(deaths, logger).loadDeathMarkers(),
                new NewPlayerSpawnStore(spawns, logger).loadSpawnState()
        );
    }

    private List<ProfileSnapshot> readProfiles() throws IOException {
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
        ProfileStore store = new ProfileStore(directory, logger, worldResolver);
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

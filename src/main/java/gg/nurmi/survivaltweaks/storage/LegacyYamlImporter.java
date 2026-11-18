package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.config.StrictYaml;
import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.object.ContainerLockSnapshot;
import gg.nurmi.survivaltweaks.object.DeathMarker;
import gg.nurmi.survivaltweaks.object.ProfileSnapshot;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        StorageSnapshot snapshot = new StorageSnapshot(
                profiles,
                new ContainerLockStore(locks, importLogger).load(readYamlIfPresent(locks)),
                new DeathMarkerStore(importLogger).load(readYamlIfPresent(deaths)),
                new NewPlayerSpawnStore(importLogger).load(readYamlIfPresent(spawns))
        );
        if (!rejected.isEmpty()) {
            throw new IOException(
                    "Legacy YAML import rejected " + rejected.size()
                            + " malformed value(s); original files were preserved. First problem: "
                            + rejected.getFirst()
            );
        }
        validateLocks(snapshot.locks());
        Set<UUID> markedPlayers = new HashSet<>();
        for (DeathMarker marker : snapshot.deathMarkers()) {
            if (!markedPlayers.add(marker.playerId())) {
                throw new IOException("Legacy death markers repeat player " + marker.playerId());
            }
        }
        return snapshot;
    }

    private static void validateLocks(List<ContainerLockSnapshot> locks) throws IOException {
        Set<UUID> ids = new HashSet<>();
        Set<BlockKey> blocks = new HashSet<>();
        for (ContainerLockSnapshot lock : locks) {
            if (!ids.add(lock.id())) {
                throw new IOException("Legacy container locks repeat ID " + lock.id());
            }
            for (BlockKey block : lock.blocks()) {
                if (!blocks.add(block)) {
                    throw new IOException("Legacy container locks share block " + block);
                }
            }
        }
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
            YamlConfiguration yaml = readYamlIfPresent(file);
            String filename = file.getFileName().toString();
            try {
                UUID playerId = UUID.fromString(filename.substring(0, filename.length() - 4));
                ProfileSnapshot profile = store.load(playerId, yaml).snapshot();
                // A UUID filename alone does not make a truncated or unrelated file player data.
                if (!profile.equals(new ProfileSnapshot(playerId, List.of()))) {
                    profiles.add(profile);
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid legacy profile " + filename + ": " + exception.getMessage(), exception);
            }
        }
        return List.copyOf(profiles);
    }

    private boolean isProfileFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && ProfileStore.isProfileFilename(path.getFileName().toString());
    }

    private YamlConfiguration readYamlIfPresent(Path path) throws IOException {
        if (Files.notExists(path)) {
            return new YamlConfiguration();
        }
        try {
            return StrictYaml.load(path);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Could not parse legacy file " + path.getFileName() + ": " + exception.getMessage(),
                    exception);
        }
    }
}

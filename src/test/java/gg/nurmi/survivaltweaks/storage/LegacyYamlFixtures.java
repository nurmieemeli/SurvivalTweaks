package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.object.ContainerLockSnapshot;
import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnAssignment;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnLocation;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnState;
import gg.nurmi.survivaltweaks.object.PlayerNotification;
import gg.nurmi.survivaltweaks.object.PlayerPreferences;
import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.object.ProfileSnapshot;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

public final class LegacyYamlFixtures {
    private LegacyYamlFixtures() {
    }

    private static YamlConfiguration read(Path file) {
        return Files.notExists(file) ? new YamlConfiguration() : YamlConfiguration.loadConfiguration(file.toFile());
    }

    private static void write(Path file, String prefix, YamlConfiguration yaml) throws IOException {
        Path parent = file.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, prefix, ".tmp");
        try {
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static final class ProfileFiles implements ProfileDataStore {
        private final Path directory;
        private final ProfileStore reader;

        public ProfileFiles(Path directory, Logger logger) {
            this(directory, logger, ignored -> null);
        }

        public ProfileFiles(Path directory, Logger logger, Function<String, UUID> worldResolver) {
            this.directory = directory;
            this.reader = new ProfileStore(directory, logger, worldResolver);
        }

        @Override
        public Profile load(UUID uniqueId) {
            Path file = directory.resolve(uniqueId + ".yml");
            return Files.notExists(file) ? new Profile(uniqueId) : reader.load(uniqueId, read(file));
        }

        @Override
        public Map<UUID, Set<UUID>> loadAllGlobalTrusts() {
            return Map.of();
        }

        @Override
        public void save(ProfileSnapshot snapshot) throws IOException {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("schema-version", ProfileStore.SCHEMA_VERSION);
            yaml.set("homes", snapshot.homes().stream().map(this::serialize).toList());
            yaml.set("preferences", serializePreferences(snapshot.preferences()));
            yaml.set("seen-hints", snapshot.seenHints().stream().map(Enum::name).sorted().toList());
            yaml.set("notifications", snapshot.notifications().stream().map(this::serializeNotification).toList());
            yaml.set("social.last-known-name", snapshot.lastKnownName());
            yaml.set(
                    "social.last-seen-at",
                    snapshot.lastSeenAt() == null ? null : snapshot.lastSeenAt().toString()
            );
            yaml.set("social.play-time-ticks", snapshot.playTimeTicks());
            yaml.set(
                    "social.blocked-mail-senders",
                    snapshot.blockedMailSenders().stream().map(UUID::toString).sorted().toList()
            );

            write(directory.resolve(snapshot.uniqueId() + ".yml"), snapshot.uniqueId() + "-", yaml);
        }

        private Map<String, Object> serialize(Home home) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("name", home.name());
            values.put("world-uuid", home.worldId() == null ? null : home.worldId().toString());
            values.put("world", home.worldName());
            values.put("x", home.x());
            values.put("y", home.y());
            values.put("z", home.z());
            values.put("yaw", home.yaw());
            values.put("pitch", home.pitch());
            values.put("icon", home.icon().getKey().asString());
            values.put("description", home.description());
            values.put("favorite", home.favorite());
            values.put("order", home.order());
            values.put("category", home.category().name());
            values.put("arrival-style", home.arrivalStyle().name());
            values.put("shared-with", home.sharedWith().stream().map(UUID::toString).sorted().toList());
            return values;
        }

        private Map<String, Object> serializePreferences(PlayerPreferences preferences) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("sounds", preferences.soundsEnabled());
            values.put("particles", preferences.particlesEnabled());
            values.put("dialogs", preferences.dialogsEnabled());
            values.put("action-bar", preferences.actionBarEnabled());
            values.put("reduced-effects", preferences.reducedEffects());
            values.put("player-list", preferences.playerListEnabled());
            values.put("mention-notifications", preferences.mentionNotificationsEnabled());
            values.put("journey-guidance", preferences.journeyGuidanceEnabled());
            values.put("public-profile", preferences.publicProfileEnabled());
            values.put("mail", preferences.mailEnabled());
            values.put("language", preferences.language().name());
            return values;
        }

        private Map<String, Object> serializeNotification(PlayerNotification notification) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", notification.id().toString());
            values.put("type", notification.type().name());
            values.put("created-at", notification.createdAt().toString());
            values.put(
                    "actor-id",
                    notification.actorId() == null ? null : notification.actorId().toString()
            );
            values.put("actor", notification.actor());
            values.put("detail", notification.detail());
            values.put("read", notification.read());
            return values;
        }

    }


    public static final class LockFiles implements ContainerLockDataStore {
        private final Path file;
        private final ContainerLockStore reader;

        public LockFiles(Path file, Logger logger) {
            this.file = file;
            this.reader = new ContainerLockStore(file, logger);
        }

        public List<ContainerLockSnapshot> load() {
            return reader.load(read(file));
        }

        @Override
        public List<ContainerLockSnapshot> loadLocks() {
            return load();
        }

        @Override
        public void saveLocks(Collection<ContainerLockSnapshot> locks) throws IOException {
            save(locks);
        }

        public void save(Collection<ContainerLockSnapshot> locks) throws IOException {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("schema-version", 3);
            yaml.set("locks", locks.stream().map(this::serialize).toList());

            write(file, "container-locks-", yaml);
        }

        private Map<String, Object> serialize(ContainerLockSnapshot lock) {
            Map<String, Object> serialized = new LinkedHashMap<>();
            serialized.put("id", lock.id().toString());
            serialized.put("owner", lock.ownerId().toString());
            serialized.put("name", lock.name());
            serialized.put("access-mode", lock.accessMode().name());
            serialized.put("automation-allowed", lock.automationAllowed());
            serialized.put("trusted", lock.trustedPlayers().stream().map(UUID::toString).sorted().toList());
            serialized.put("blocks", lock.blocks().stream()
                    .sorted((left, right) -> {
                        int world = left.worldId().compareTo(right.worldId());
                        if (world != 0) {
                            return world;
                        }
                        int x = Integer.compare(left.x(), right.x());
                        if (x != 0) {
                            return x;
                        }
                        int y = Integer.compare(left.y(), right.y());
                        return y != 0 ? y : Integer.compare(left.z(), right.z());
                    })
                    .map(this::serializeBlock)
                    .toList());
            return serialized;
        }

        private Map<String, Object> serializeBlock(BlockKey block) {
            Map<String, Object> serialized = new LinkedHashMap<>();
            serialized.put("world", block.worldId().toString());
            serialized.put("x", block.x());
            serialized.put("y", block.y());
            serialized.put("z", block.z());
            return serialized;
        }

    }


    public static final class SpawnFiles implements NewPlayerSpawnDataStore {
        private final Path file;
        private final NewPlayerSpawnStore reader;

        public SpawnFiles(Path file, Logger logger) {
            this.file = file;
            this.reader = new NewPlayerSpawnStore(logger);
        }

        public NewPlayerSpawnState load() {
            return Files.notExists(file) ? NewPlayerSpawnState.EMPTY : reader.load(read(file));
        }

        @Override
        public NewPlayerSpawnState loadSpawnState() {
            return load();
        }

        @Override
        public void saveSpawnState(NewPlayerSpawnState state) throws IOException {
            save(state);
        }

        public void save(NewPlayerSpawnState state) throws IOException {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("schema-version", 2);
            yaml.set("available", state.available().stream().map(this::serializeLocation).toList());
            yaml.set("assignments", state.assignments().values().stream()
                    .sorted(java.util.Comparator.comparing(assignment -> assignment.playerId().toString()))
                    .map(this::serializeAssignment)
                    .toList());
            yaml.set("retired", state.retired().stream().map(this::serializeLocation).toList());
            yaml.set(
                    "awaiting-replacement",
                    state.awaitingReplacement().stream().map(UUID::toString).sorted().toList()
            );
            write(file, "new-player-spawns-", yaml);
        }

        private Map<String, Object> serializeAssignment(NewPlayerSpawnAssignment assignment) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("player", assignment.playerId().toString());
            values.put("completed", assignment.completed());
            values.put("location", serializeLocation(assignment.location()));
            return values;
        }

        private Map<String, Object> serializeLocation(NewPlayerSpawnLocation location) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("world-uuid", location.worldId().toString());
            values.put("world", location.worldName());
            values.put("x", location.x());
            values.put("y", location.y());
            values.put("z", location.z());
            values.put("yaw", location.yaw());
            return values;
        }

    }
}

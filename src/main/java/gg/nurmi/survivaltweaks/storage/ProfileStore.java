package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.HomeArrivalStyle;
import gg.nurmi.survivaltweaks.object.HomeCategory;
import gg.nurmi.survivaltweaks.object.LanguagePreference;
import gg.nurmi.survivaltweaks.object.NotificationType;
import gg.nurmi.survivaltweaks.object.OnboardingHint;
import gg.nurmi.survivaltweaks.object.PlayerNotification;
import gg.nurmi.survivaltweaks.object.PlayerPreferences;
import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.object.ProfileSnapshot;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Material;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Function;

public final class ProfileStore {

    static final int SCHEMA_VERSION = 6;

    private final Path directory;
    private final Logger logger;
    private final Function<String, UUID> worldResolver;

    public ProfileStore(Path directory, Logger logger) {
        this(directory, logger, ignored -> null);
    }

    public ProfileStore(Path directory, Logger logger, Function<String, UUID> worldResolver) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.worldResolver = Objects.requireNonNull(worldResolver, "worldResolver");
    }

    public Profile load(UUID uniqueId) {
        Profile profile = new Profile(uniqueId);
        Path file = fileFor(uniqueId);
        if (Files.notExists(file)) {
            return profile;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        if (yaml.getInt("schema-version", 0) < SCHEMA_VERSION) {
            profile.requireMigration();
        }
        if (yaml.isList("homes")) {
            loadCurrentHomes(profile, yaml.getMapList("homes"), file);
        } else {
            loadLegacyHomes(profile, yaml.getConfigurationSection("home"), file);
        }
        loadExperience(profile, yaml, file);
        return profile;
    }

    public void save(ProfileSnapshot snapshot) throws IOException {
        Files.createDirectories(directory);

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", SCHEMA_VERSION);
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

        Path target = fileFor(snapshot.uniqueId());
        Path temporary = Files.createTempFile(directory, snapshot.uniqueId() + "-", ".tmp");
        try {
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            replaceAtomically(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void loadCurrentHomes(Profile profile, List<Map<?, ?>> serializedHomes, Path file) {
        for (Map<?, ?> values : serializedHomes) {
            try {
                String worldName = text(values, "world");
                UUID worldId = optionalUuid(values, "world-uuid");
                if (worldId == null) {
                    worldId = worldResolver.apply(worldName);
                    profile.requireMigration();
                }
                profile.addHome(new Home(
                        text(values, "name"),
                        worldId,
                        worldName,
                        number(values, "x").doubleValue(),
                        number(values, "y").doubleValue(),
                        number(values, "z").doubleValue(),
                        number(values, "yaw").floatValue(),
                        number(values, "pitch").floatValue(),
                        material(values.get("icon")),
                        Objects.toString(values.get("description"), ""),
                        Boolean.TRUE.equals(values.get("favorite")),
                        optionalInt(values.get("order")),
                        enumValue(
                                HomeCategory.class,
                                values.get("category"),
                                HomeCategory.OTHER
                        ),
                        enumValue(
                                HomeArrivalStyle.class,
                                values.get("arrival-style"),
                                HomeArrivalStyle.DEFAULT
                        ),
                        parseUuidSet(values.get("shared-with"))
                ));
            } catch (RuntimeException exception) {
                warnInvalidHome(file, exception);
            }
        }
    }

    private void loadLegacyHomes(Profile profile, ConfigurationSection homes, Path file) {
        if (homes == null) {
            return;
        }

        for (String name : homes.getKeys(false)) {
            String path = name + ".";
            try {
                String worldName = Objects.requireNonNull(homes.getString(path + "world"), "world");
                profile.addHome(new Home(
                        name,
                        worldResolver.apply(worldName),
                        worldName,
                        homes.getDouble(path + "x"),
                        homes.getDouble(path + "y"),
                        homes.getDouble(path + "z"),
                        (float) homes.getDouble(path + "yaw"),
                        (float) homes.getDouble(path + "pitch")
                ));
                profile.requireMigration();
            } catch (RuntimeException exception) {
                warnInvalidHome(file, exception);
            }
        }
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

    private void loadExperience(Profile profile, YamlConfiguration yaml, Path file) {
        profile.preferences(new PlayerPreferences(
                yaml.getBoolean("preferences.sounds", true),
                yaml.getBoolean("preferences.particles", true),
                yaml.getBoolean("preferences.dialogs", true),
                yaml.getBoolean("preferences.action-bar", true),
                yaml.getBoolean("preferences.automatic-recovery-compass", true),
                yaml.getBoolean("preferences.reduced-effects", false),
                yaml.getBoolean("preferences.player-list", true),
                yaml.getBoolean("preferences.mention-notifications", true),
                yaml.getBoolean("preferences.journey-guidance", true),
                yaml.getBoolean("preferences.public-profile", true),
                yaml.getBoolean("preferences.mail", true),
                enumValue(
                        LanguagePreference.class,
                        yaml.get("preferences.language"),
                        LanguagePreference.AUTO
                )
        ));

        ArrayList<OnboardingHint> hints = new ArrayList<>();
        for (String value : yaml.getStringList("seen-hints")) {
            try {
                hints.add(OnboardingHint.valueOf(value.toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                logger.warning("Skipped unknown onboarding hint '" + value + "' in " + file.getFileName());
            }
        }
        profile.seenHints(hints);

        ArrayList<PlayerNotification> notifications = new ArrayList<>();
        for (Map<?, ?> values : yaml.getMapList("notifications")) {
            try {
                notifications.add(new PlayerNotification(
                        UUID.fromString(text(values, "id")),
                        NotificationType.valueOf(text(values, "type").toUpperCase(java.util.Locale.ROOT)),
                        Instant.parse(text(values, "created-at")),
                        optionalUuid(values, "actor-id"),
                        Objects.toString(values.get("actor"), ""),
                        Objects.toString(values.get("detail"), ""),
                        Boolean.TRUE.equals(values.get("read"))
                ));
            } catch (RuntimeException exception) {
                logger.log(
                        Level.WARNING,
                        "Skipped an invalid notification in " + file.getFileName(),
                        exception
                );
            }
        }
        profile.notifications(notifications);
        profile.lastKnownName(yaml.getString("social.last-known-name", ""));
        profile.playTimeTicks(yaml.getLong("social.play-time-ticks", 0L));
        String lastSeen = yaml.getString("social.last-seen-at");
        if (lastSeen != null && !lastSeen.isBlank()) {
            try {
                profile.lastSeenAt(Instant.parse(lastSeen));
            } catch (RuntimeException exception) {
                logger.warning("Skipped invalid social.last-seen-at in " + file.getFileName());
            }
        }
        ArrayList<UUID> blocked = new ArrayList<>();
        for (String value : yaml.getStringList("social.blocked-mail-senders")) {
            try {
                blocked.add(UUID.fromString(value));
            } catch (IllegalArgumentException exception) {
                logger.warning("Skipped invalid blocked mail UUID in " + file.getFileName());
            }
        }
        profile.blockedMailSenders(blocked);
    }

    private Map<String, Object> serializePreferences(PlayerPreferences preferences) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sounds", preferences.soundsEnabled());
        values.put("particles", preferences.particlesEnabled());
        values.put("dialogs", preferences.dialogsEnabled());
        values.put("action-bar", preferences.actionBarEnabled());
        values.put("automatic-recovery-compass", preferences.automaticRecoveryCompass());
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

    private Material material(Object value) {
        Material material = value == null ? null : Material.matchMaterial(value.toString());
        return material == null ? Material.ENDER_PEARL : material;
    }

    private int optionalInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, Object value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toString().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private String text(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Missing text field '" + key + "'");
        }
        return text;
    }

    private Number number(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Missing numeric field '" + key + "'");
        }
        return number;
    }

    private UUID optionalUuid(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        return UUID.fromString(value.toString());
    }

    private Set<UUID> parseUuidSet(Object value) {
        if (!(value instanceof List<?> list)) {
            return Set.of();
        }
        Set<UUID> set = new java.util.LinkedHashSet<>();
        for (Object item : list) {
            if (item != null) {
                try {
                    set.add(UUID.fromString(item.toString()));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return Set.copyOf(set);
    }

    private void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void warnInvalidHome(Path file, RuntimeException exception) {
        logger.log(Level.WARNING, "Skipped an invalid home in " + file.getFileName(), exception);
    }

    private Path fileFor(UUID uniqueId) {
        return directory.resolve(uniqueId + ".yml");
    }
}

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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Material;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class ProfileStore {

    static final int SCHEMA_VERSION = 6;
    private static final Pattern PROFILE_FILENAME = Pattern.compile(
            "[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\\.yml");

    public static boolean isProfileFilename(String filename) {
        return PROFILE_FILENAME.matcher(filename).matches();
    }

    private final Path directory;
    private final Logger logger;
    private final Function<String, UUID> worldResolver;

    ProfileStore(Path directory, Logger logger, Function<String, UUID> worldResolver) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.worldResolver = Objects.requireNonNull(worldResolver, "worldResolver");
    }

    Profile load(UUID uniqueId, YamlConfiguration yaml) {
        Profile profile = new Profile(uniqueId);
        Path file = fileFor(uniqueId);
        if (yaml.getInt("schema-version", 0) < SCHEMA_VERSION) {
            profile.requireMigration();
        }
        if (yaml.get("homes") instanceof List<?> homes) {
            loadCurrentHomes(profile, homes, file);
        } else {
            if (yaml.contains("homes")) {
                throw new IllegalArgumentException("homes must be a list");
            }
            if (yaml.contains("home") && !yaml.isConfigurationSection("home")) {
                throw new IllegalArgumentException("home must be a section");
            }
            loadLegacyHomes(profile, yaml.getConfigurationSection("home"), file);
        }
        loadExperience(profile, yaml, file);
        return profile;
    }

    private void loadCurrentHomes(Profile profile, List<?> serializedHomes, Path file) {
        for (Object serialized : serializedHomes) {
            try {
                if (!(serialized instanceof Map<?, ?> values)) {
                    throw new IllegalArgumentException("homes entries must be mappings");
                }
                String worldName = text(values, "world");
                UUID worldId = optionalUuid(values, "world-uuid");
                if (worldId == null) {
                    worldId = worldResolver.apply(worldName);
                    profile.requireMigration();
                }
                addHome(profile, new Home(
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
                if (!(homes.get(path + "world") instanceof String worldName)) {
                    throw new IllegalArgumentException("home." + path + "world must be text");
                }
                addHome(profile, new Home(
                        name,
                        worldResolver.apply(worldName),
                        worldName,
                        legacyHomeNumber(homes, path + "x"),
                        legacyHomeNumber(homes, path + "y"),
                        legacyHomeNumber(homes, path + "z"),
                        (float) legacyHomeNumber(homes, path + "yaw"),
                        (float) legacyHomeNumber(homes, path + "pitch")
                ));
                profile.requireMigration();
            } catch (RuntimeException exception) {
                warnInvalidHome(file, exception);
            }
        }
    }

    private void addHome(Profile profile, Home home) {
        if (!profile.addHome(home)) {
            throw new IllegalArgumentException("Duplicate home name '" + home.name() + "'");
        }
    }

    private double legacyHomeNumber(ConfigurationSection homes, String path) {
        if (homes.get(path, 0) instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException("home." + path + " must be a finite number");
    }

    private void loadExperience(Profile profile, YamlConfiguration yaml, Path file) {
        if (yaml.contains("preferences") && !yaml.isConfigurationSection("preferences")) {
            throw new IllegalArgumentException("preferences must be a section");
        }
        profile.preferences(new PlayerPreferences(
                preference(yaml, "sounds", true),
                preference(yaml, "particles", true),
                preference(yaml, "dialogs", true),
                preference(yaml, "action-bar", true),
                preference(yaml, "reduced-effects", false),
                preference(yaml, "player-list", true),
                preference(yaml, "mention-notifications", true),
                preference(yaml, "journey-guidance", true),
                preference(yaml, "public-profile", true),
                preference(yaml, "mail", true),
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
        Set<UUID> notificationIds = new HashSet<>();
        for (Map<?, ?> values : yaml.getMapList("notifications")) {
            try {
                PlayerNotification notification = new PlayerNotification(
                        UUID.fromString(text(values, "id")),
                        NotificationType.valueOf(text(values, "type").toUpperCase(java.util.Locale.ROOT)),
                        Instant.parse(text(values, "created-at")),
                        optionalUuid(values, "actor-id"),
                        Objects.toString(values.get("actor"), ""),
                        Objects.toString(values.get("detail"), ""),
                        Boolean.TRUE.equals(values.get("read"))
                );
                if (!notificationIds.add(notification.id())) {
                    throw new IllegalArgumentException("Duplicate notification ID " + notification.id());
                }
                notifications.add(notification);
            } catch (RuntimeException exception) {
                logger.log(
                        Level.WARNING,
                        "Skipped an invalid notification in " + file.getFileName() + ": " + exception.getMessage(),
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

    private boolean preference(YamlConfiguration yaml, String key, boolean fallback) {
        String path = "preferences." + key;
        if (yaml.get(path, fallback) instanceof Boolean value) {
            return value;
        }
        throw new IllegalArgumentException(path + " must be a boolean");
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
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("'" + key + "' must be a finite number");
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

    private void warnInvalidHome(Path file, RuntimeException exception) {
        logger.log(Level.WARNING, "Skipped an invalid home in " + file.getFileName() + ": " + exception.getMessage(),
                exception);
    }

    private Path fileFor(UUID uniqueId) {
        return directory.resolve(uniqueId + ".yml");
    }
}

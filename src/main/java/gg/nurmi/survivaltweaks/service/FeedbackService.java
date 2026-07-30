package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.PlayerPreferences;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.UUID;
import java.util.function.Function;

public final class FeedbackService {

    public static final String HOME_SAVED = "home-saved";
    public static final String HOME_DELETED = "home-deleted";
    public static final String UI_OPEN = "ui-open";
    public static final String UI_CLICK = "ui-click";
    public static final String TELEPORT_REQUEST = "teleport-request";
    public static final String TELEPORT_WARMUP = "teleport-warmup";
    public static final String TELEPORT_COUNTDOWN = "teleport-countdown";
    public static final String TELEPORT_COMPLETE = "teleport-complete";
    public static final String TELEPORT_CANCELLED = "teleport-cancelled";
    public static final String NEW_PLAYER_ARRIVAL = "new-player-arrival";
    public static final String LOCK_CREATED = "lock-created";
    public static final String LOCK_REMOVED = "lock-removed";
    public static final String LOCK_ACCESS_CHANGED = "lock-access-changed";
    public static final String LOCK_DENIED = "lock-denied";
    public static final String MENTION = "mention";
    public static final String MAIL = "mail";
    public static final String GUIDE_HINT = "guide-hint";
    public static final String MAINTENANCE_WARNING = "maintenance-warning";
    public static final String ANNOUNCEMENT = "announcement";
    public static final String ENCHANT_DISCOVERED = "enchant-discovered";
    public static final String ENCHANT_AREA_BREAK = "enchant-area-break";
    public static final String ENCHANT_CULTIVATION = "enchant-cultivation";
    public static final String ENCHANT_BEHEADING = "enchant-beheading";
    public static final String ENCHANT_DEFLECTION = "enchant-deflection";

    private final Logger logger;
    private volatile State state;
    private volatile Function<UUID, PlayerPreferences> preferenceProvider =
            ignored -> PlayerPreferences.DEFAULTS;
    private final Set<String> warnedCues = new HashSet<>();

    public FeedbackService(FileConfiguration config, Logger logger) {
        this.logger = logger;
        this.state = load(config, false);
    }

    public Prepared prepare(FileConfiguration config) {
        return new Prepared(load(config, true));
    }

    public void apply(Prepared prepared) {
        this.state = prepared.state;
        synchronized (warnedCues) {
            warnedCues.clear();
        }
    }

    public Prepared snapshot() {
        return new Prepared(state);
    }

    public void preferenceProvider(Function<UUID, PlayerPreferences> provider) {
        preferenceProvider = java.util.Objects.requireNonNull(provider, "provider");
    }

    public void play(Player player, String cueName) {
        State current = state;
        if (!current.enabled() || !player.isOnline() || !current.cues().containsKey(cueName)) {
            return;
        }
        playAt(player, cueName, player.getLocation(), 1.0);
    }

    public void play(Player player, String cueName, double intensity) {
        State current = state;
        if (!current.enabled() || !player.isOnline() || !current.cues().containsKey(cueName)) {
            return;
        }
        playAt(player, cueName, player.getLocation(), intensity);
    }

    public void playAt(Player player, String cueName, Location location, double intensity) {
        State current = state;
        if (!current.enabled() || !player.isOnline()) {
            return;
        }

        Cue cue = current.cues().get(cueName);
        if (cue == null) {
            return;
        }

        try {
            PlayerPreferences preferences = preferenceProvider.apply(player.getUniqueId());
            double clampedIntensity = Math.max(0.1, Math.min(2.0, intensity));
            double accessibilityScale = preferences.reducedEffects() ? 0.3 : 1.0;
            if (current.soundsEnabled() && preferences.soundsEnabled() && cue.sound() != null) {
                player.playSound(
                        location,
                        cue.sound(),
                        SoundCategory.PLAYERS,
                        (float) (cue.volume() * Math.min(1.25, clampedIntensity)
                                * (preferences.reducedEffects() ? 0.65 : 1.0)),
                        cue.pitch()
                );
            }
            if (current.particlesEnabled()
                    && preferences.particlesEnabled()
                    && cue.particle() != null
                    && cue.count() > 0) {
                player.spawnParticle(
                        cue.particle(),
                        location.clone().add(0, 1, 0),
                        Math.max(1, (int) Math.round(cue.count() * clampedIntensity * accessibilityScale)),
                        cue.spread(),
                        cue.spread(),
                        cue.spread(),
                        0
                );
            }
        } catch (RuntimeException exception) {
            synchronized (warnedCues) {
                if (warnedCues.add(cueName)) {
                    logger.log(Level.WARNING, "Could not play feedback cue '" + cueName + "'", exception);
                }
            }
        }
    }

    private State load(FileConfiguration config, boolean strict) {
        if (strict) {
            requireBoolean(config, "feedback.enabled");
            requireBoolean(config, "feedback.sounds-enabled");
            requireBoolean(config, "feedback.particles-enabled");
        }
        return new State(
                config.getBoolean("feedback.enabled", true),
                config.getBoolean("feedback.sounds-enabled", true),
                config.getBoolean("feedback.particles-enabled", true),
                loadCues(config.getConfigurationSection("feedback.cues"), strict)
        );
    }

    private Map<String, Cue> loadCues(ConfigurationSection section, boolean strict) {
        if (section == null) {
            return Map.of();
        }

        Map<String, Cue> loaded = new HashMap<>();
        for (String name : section.getKeys(false)) {
            ConfigurationSection cue = section.getConfigurationSection(name);
            if (cue == null) {
                continue;
            }

            String sound = parseSound(name, cue.getString("sound"), strict);
            Particle particle = parseParticle(name, cue.getString("particle"), strict);
            float volume = (float) boundedDouble(cue, name, "volume", 0.7, 0, 10, strict);
            float pitch = (float) boundedDouble(cue, name, "pitch", 1, 0.5, 2, strict);
            int count = boundedInt(cue, name, "count", 0, 0, 200, strict);
            double spread = boundedDouble(cue, name, "spread", 0.35, 0, 5, strict);
            loaded.put(name, new Cue(sound, volume, pitch, particle, count, spread));
        }
        return Map.copyOf(loaded);
    }

    private String parseSound(String cueName, String configured, boolean strict) {
        String value = optionalText(configured);
        if (value == null) {
            return null;
        }
        if (NamespacedKey.fromString(value) == null) {
            String error = "Unknown sound key syntax '" + value + "' for feedback cue '" + cueName + "'";
            if (strict) {
                throw new IllegalArgumentException(error);
            }
            logger.warning(error + "; disabled.");
            return null;
        }
        return value;
    }

    private Particle parseParticle(String cueName, String configured, boolean strict) {
        String value = optionalText(configured);
        if (value == null) {
            return null;
        }

        int separator = value.indexOf(':');
        String enumName = separator >= 0 ? value.substring(separator + 1) : value;
        try {
            return Particle.valueOf(enumName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            String error = "Unknown particle '" + value + "' for feedback cue '" + cueName + "'";
            if (strict) {
                throw new IllegalArgumentException(error);
            }
            logger.warning(error + "; disabled.");
            return null;
        }
    }

    private double boundedDouble(
            ConfigurationSection cue,
            String cueName,
            String property,
            double fallback,
            double minimum,
            double maximum,
            boolean strict
    ) {
        if (strict && cue.contains(property) && !(cue.get(property) instanceof Number)) {
            throw new IllegalArgumentException(
                    "feedback.cues." + cueName + "." + property + " must be a number"
            );
        }
        double value = cue.getDouble(property, fallback);
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            String error = "feedback.cues." + cueName + "." + property + " must be between "
                    + minimum + " and " + maximum;
            if (strict) {
                throw new IllegalArgumentException(error);
            }
            logger.warning(error + "; using " + fallback + ".");
            return fallback;
        }
        return value;
    }

    private int boundedInt(
            ConfigurationSection cue,
            String cueName,
            String property,
            int fallback,
            int minimum,
            int maximum,
            boolean strict
    ) {
        Object configured = cue.get(property);
        if (strict && configured != null
                && (!(configured instanceof Number number)
                || number.doubleValue() != Math.rint(number.doubleValue()))) {
            throw new IllegalArgumentException(
                    "feedback.cues." + cueName + "." + property + " must be an integer"
            );
        }
        int value = cue.getInt(property, fallback);
        if (value < minimum || value > maximum) {
            String error = "feedback.cues." + cueName + "." + property + " must be between "
                    + minimum + " and " + maximum;
            if (strict) {
                throw new IllegalArgumentException(error);
            }
            logger.warning(error + "; using " + fallback + ".");
            return fallback;
        }
        return value;
    }

    private String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void requireBoolean(FileConfiguration config, String path) {
        if (!config.isBoolean(path)) {
            throw new IllegalArgumentException(path + " must be true or false");
        }
    }

    public static final class Prepared {

        private final State state;

        private Prepared(State state) {
            this.state = state;
        }
    }

    private record State(
            boolean enabled,
            boolean soundsEnabled,
            boolean particlesEnabled,
            Map<String, Cue> cues
    ) {
    }

    private record Cue(
            String sound,
            float volume,
            float pitch,
            Particle particle,
            int count,
            double spread
    ) {
    }
}

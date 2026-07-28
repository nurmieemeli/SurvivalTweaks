package gg.nurmi.survivaltweaks.config;

import gg.nurmi.survivaltweaks.object.VanillaGuideTopic;
import gg.nurmi.survivaltweaks.object.CustomDeathCause;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

public record PluginSettings(
        int maxHomes,
        Duration autosaveInterval,
        Duration teleportRequestLifetime,
        Duration teleportWarmup,
        Duration teleportCooldown,
        boolean cancelTeleportOnMove,
        boolean cancelTeleportOnDamage,
        int safeTeleportSearchRadius,
        int lockTargetDistance,
        int maxLocksPerPlayer,
        boolean protectLocksFromExplosions,
        boolean blockLockedContainerAutomation,
        boolean homeMenuEnabled,
        boolean dialogsEnabled,
        boolean actionBarEnabled,
        boolean lockTargetHintsEnabled,
        boolean deathRecoveryEnabled,
        boolean deathCompassOnRespawn,
        boolean deathFloatingGuideEnabled,
        boolean deathFloatingGuideAutomaticOnRespawn,
        double deathFloatingGuideNearDistance,
        double deathFloatingGuideOffset,
        Duration deathMarkerLifetime,
        Duration deathCompassCooldown,
        boolean customDeathMessagesEnabled,
        int customDeathMessageRareVariantPercent,
        Set<String> customDeathMessageCauses,
        boolean chatFormattingEnabled,
        boolean connectionMessagesEnabled,
        boolean playerListEnabled,
        int playerListRefreshSeconds,
        boolean playerListShowPing,
        boolean playerListShowTps,
        boolean playerListShowMspt,
        boolean playerListShowWorld,
        boolean playerListShowUnreadNotifications,
        boolean playerListStaffBadges,
        boolean afkIndicatorsEnabled,
        Duration afkTimeout,
        boolean sleepVotingEnabled,
        int sleepRequiredPercentage,
        boolean sleepExcludeAfk,
        boolean sleepClearWeather,
        boolean serverListEnabled,
        List<String> serverListAnnouncements,
        boolean mentionsEnabled,
        Duration mentionCooldown,
        int mentionMaxPerMessage,
        int maintenanceJoinBlockSeconds,
        boolean maintenanceBackupBeforeRestart,
        boolean journeyEnabled,
        int journeyWelcomeDelayTicks,
        boolean vanillaGuideEnabled,
        Duration vanillaGuideMinimumGap,
        Set<String> vanillaGuideTopics,
        boolean welcomeBackEnabled,
        Duration welcomeBackMinimumAway,
        int welcomeBackDelayTicks,
        boolean welcomeBackAutoOpen,
        boolean playerProfilesEnabled,
        boolean statisticsEnabled,
        boolean statisticsPublicViewing,
        boolean mailEnabled,
        int mailMaximumLength,
        Duration mailCooldown,
        int mailMaximumPerHour,
        boolean newPlayerSpawnEnabled,
        String newPlayerSpawnWorld,
        int newPlayerSpawnMinX,
        int newPlayerSpawnMaxX,
        int newPlayerSpawnMinZ,
        int newPlayerSpawnMaxZ,
        int newPlayerSpawnPreloadLocations,
        int newPlayerSpawnMinimumSeparation,
        int newPlayerSpawnMaxGenerationAttempts,
        int newPlayerSpawnGenerationDelayTicks,
        double newPlayerSpawnMinimumTps,
        int newPlayerSpawnLandingRadius,
        int newPlayerSpawnMaxHeightVariation,
        Set<String> newPlayerSpawnAllowedBiomes,
        Set<String> newPlayerSpawnBlockedBiomes
) {

    public PluginSettings {
        serverListAnnouncements = List.copyOf(serverListAnnouncements);
        customDeathMessageCauses = Set.copyOf(customDeathMessageCauses);
        vanillaGuideTopics = Set.copyOf(vanillaGuideTopics);
        newPlayerSpawnAllowedBiomes = Set.copyOf(newPlayerSpawnAllowedBiomes);
        newPlayerSpawnBlockedBiomes = Set.copyOf(newPlayerSpawnBlockedBiomes);
    }

    public static PluginSettings load(FileConfiguration config, Logger logger) {
        PluginSettings settings = new PluginSettings(
                boundedInt(config, logger, "home.max-amount", 3, 1, 100),
                Duration.ofSeconds(boundedInt(config, logger, "storage.autosave-seconds", 300, 10, 86_400)),
                Duration.ofSeconds(boundedInt(
                        config,
                        logger,
                        "teleport.request-lifetime-seconds",
                        30,
                        5,
                        300
                )),
                Duration.ofSeconds(boundedInt(config, logger, "teleport.warmup-seconds", 3, 0, 60)),
                Duration.ofSeconds(boundedInt(config, logger, "teleport.cooldown-seconds", 30, 0, 3_600)),
                config.getBoolean("teleport.cancel-on-move", true),
                config.getBoolean("teleport.cancel-on-damage", true),
                boundedInt(config, logger, "teleport.safe-search-radius", 4, 0, 16),
                boundedInt(config, logger, "locked-containers.target-distance", 5, 1, 10),
                boundedInt(config, logger, "locked-containers.max-per-player", 50, 1, 10_000),
                config.getBoolean("locked-containers.protect-from-explosions", true),
                config.getBoolean("locked-containers.block-automation", true),
                config.getBoolean("ui.home-menu-enabled", true),
                config.getBoolean("ui.dialogs-enabled", true),
                config.getBoolean("ui.action-bar-enabled", true),
                config.getBoolean("ui.lock-target-hints-enabled", true),
                config.getBoolean("death-recovery.enabled", true),
                config.getBoolean("death-recovery.compass-on-respawn", true),
                config.getBoolean("death-recovery.floating-guide.enabled", true),
                config.getBoolean("death-recovery.floating-guide.automatic-on-respawn", false),
                boundedDouble(
                        config,
                        logger,
                        "death-recovery.floating-guide.near-distance",
                        24.0,
                        8.0,
                        64.0
                ),
                boundedDouble(
                        config,
                        logger,
                        "death-recovery.floating-guide.offset",
                        3.5,
                        1.5,
                        8.0
                ),
                Duration.ofSeconds(boundedInt(
                        config,
                        logger,
                        "death-recovery.marker-lifetime-seconds",
                        3600,
                        60,
                        604_800
                )),
                Duration.ofSeconds(boundedInt(
                        config,
                        logger,
                        "death-recovery.compass-cooldown-seconds",
                        30,
                        0,
                        3_600
                )),
                config.getBoolean("custom-death-messages.enabled", true),
                boundedInt(config, logger, "custom-death-messages.rare-variant-percent", 5, 0, 100),
                customDeathMessageCauses(config, logger),
                config.getBoolean("chat.enabled", true),
                config.getBoolean("connection-messages.enabled", true),
                config.getBoolean("player-list.enabled", true),
                boundedInt(config, logger, "player-list.refresh-seconds", 2, 1, 60),
                config.getBoolean("player-list.show-ping", true),
                config.getBoolean("player-list.show-tps", true),
                config.getBoolean("player-list.show-mspt", true),
                config.getBoolean("player-list.show-world", true),
                config.getBoolean("player-list.show-unread-notifications", true),
                config.getBoolean("player-list.staff-badges", true),
                config.getBoolean("player-list.afk.enabled", true),
                Duration.ofSeconds(boundedInt(
                        config,
                        logger,
                        "player-list.afk.after-seconds",
                        300,
                        30,
                        86_400
                )),
                config.getBoolean("sleep.enabled", true),
                boundedInt(config, logger, "sleep.required-percentage", 50, 1, 100),
                config.getBoolean("sleep.exclude-afk", true),
                config.getBoolean("sleep.clear-weather", true),
                config.getBoolean("server-list.enabled", true),
                textList(config, logger, "server-list.announcements"),
                config.getBoolean("mentions.enabled", true),
                Duration.ofSeconds(boundedInt(config, logger, "mentions.cooldown-seconds", 10, 0, 300)),
                boundedInt(config, logger, "mentions.max-per-message", 3, 1, 10),
                boundedInt(config, logger, "maintenance.join-block-seconds", 30, 0, 3600),
                config.getBoolean("maintenance.backup-before-restart", true),
                config.getBoolean("journey.enabled", true),
                boundedInt(config, logger, "journey.welcome-delay-ticks", 60, 0, 1200),
                config.getBoolean("journey.vanilla-guide.enabled", true),
                Duration.ofSeconds(boundedInt(
                        config,
                        logger,
                        "journey.vanilla-guide.minimum-gap-seconds",
                        120,
                        0,
                        3600
                )),
                vanillaGuideTopics(config, logger),
                config.getBoolean("welcome-back.enabled", true),
                Duration.ofHours(boundedInt(
                        config,
                        logger,
                        "welcome-back.minimum-away-hours",
                        6,
                        1,
                        8_760
                )),
                boundedInt(config, logger, "welcome-back.delay-ticks", 80, 0, 1200),
                config.getBoolean("welcome-back.auto-open", false),
                config.getBoolean("player-profiles.enabled", true),
                config.getBoolean("statistics.enabled", true),
                config.getBoolean("statistics.public-viewing", true),
                config.getBoolean("mail.enabled", true),
                boundedInt(config, logger, "mail.maximum-length", 160, 16, 1000),
                Duration.ofSeconds(boundedInt(config, logger, "mail.cooldown-seconds", 30, 0, 3600)),
                boundedInt(config, logger, "mail.maximum-per-hour", 10, 1, 100),
                config.getBoolean("new-player-spawn.enabled", true),
                java.util.Objects.toString(config.getString("new-player-spawn.world"), "").strip(),
                boundedInt(config, logger, "new-player-spawn.min-x", -10_000, -29_999_984, 29_999_984),
                boundedInt(config, logger, "new-player-spawn.max-x", 10_000, -29_999_984, 29_999_984),
                boundedInt(config, logger, "new-player-spawn.min-z", -10_000, -29_999_984, 29_999_984),
                boundedInt(config, logger, "new-player-spawn.max-z", 10_000, -29_999_984, 29_999_984),
                boundedInt(config, logger, "new-player-spawn.preload-locations", 10, 1, 100),
                boundedInt(config, logger, "new-player-spawn.minimum-separation", 256, 0, 100_000),
                boundedInt(config, logger, "new-player-spawn.max-generation-attempts", 100, 1, 10_000),
                boundedInt(config, logger, "new-player-spawn.generation-delay-ticks", 20, 1, 12_000),
                boundedDouble(config, logger, "new-player-spawn.minimum-tps", 18.0, 0.0, 20.0),
                boundedInt(config, logger, "new-player-spawn.landing-radius", 2, 0, 8),
                boundedInt(config, logger, "new-player-spawn.max-height-variation", 2, 0, 16),
                biomeKeys(config, logger, "new-player-spawn.allowed-biomes"),
                biomeKeys(config, logger, "new-player-spawn.blocked-biomes")
        );
        if (settings.newPlayerSpawnMinX() > settings.newPlayerSpawnMaxX()) {
            logger.warning("Configuration new-player-spawn X bounds are inverted; treating them in ascending order.");
        }
        if (settings.newPlayerSpawnMinZ() > settings.newPlayerSpawnMaxZ()) {
            logger.warning("Configuration new-player-spawn Z bounds are inverted; treating them in ascending order.");
        }
        return settings;
    }

    public static PluginSettings validate(FileConfiguration config) {
        List<String> errors = new ArrayList<>();
        PluginSettings settings = new PluginSettings(
                strictInt(config, errors, "home.max-amount", 1, 100),
                Duration.ofSeconds(strictInt(config, errors, "storage.autosave-seconds", 10, 86_400)),
                Duration.ofSeconds(strictInt(config, errors, "teleport.request-lifetime-seconds", 5, 300)),
                Duration.ofSeconds(strictInt(config, errors, "teleport.warmup-seconds", 0, 60)),
                Duration.ofSeconds(strictInt(config, errors, "teleport.cooldown-seconds", 0, 3_600)),
                strictBoolean(config, errors, "teleport.cancel-on-move"),
                strictBoolean(config, errors, "teleport.cancel-on-damage"),
                strictInt(config, errors, "teleport.safe-search-radius", 0, 16),
                strictInt(config, errors, "locked-containers.target-distance", 1, 10),
                strictInt(config, errors, "locked-containers.max-per-player", 1, 10_000),
                strictBoolean(config, errors, "locked-containers.protect-from-explosions"),
                strictBoolean(config, errors, "locked-containers.block-automation"),
                strictBoolean(config, errors, "ui.home-menu-enabled"),
                strictBoolean(config, errors, "ui.dialogs-enabled"),
                strictBoolean(config, errors, "ui.action-bar-enabled"),
                strictBoolean(config, errors, "ui.lock-target-hints-enabled"),
                strictBoolean(config, errors, "death-recovery.enabled"),
                strictBoolean(config, errors, "death-recovery.compass-on-respawn"),
                strictBoolean(config, errors, "death-recovery.floating-guide.enabled"),
                strictBoolean(config, errors, "death-recovery.floating-guide.automatic-on-respawn"),
                strictDouble(config, errors, "death-recovery.floating-guide.near-distance", 8.0, 64.0),
                strictDouble(config, errors, "death-recovery.floating-guide.offset", 1.5, 8.0),
                Duration.ofSeconds(strictInt(
                        config,
                        errors,
                        "death-recovery.marker-lifetime-seconds",
                        60,
                        604_800
                )),
                Duration.ofSeconds(strictInt(
                        config,
                        errors,
                        "death-recovery.compass-cooldown-seconds",
                        0,
                        3_600
                )),
                strictBoolean(config, errors, "custom-death-messages.enabled"),
                strictInt(config, errors, "custom-death-messages.rare-variant-percent", 0, 100),
                strictCustomDeathMessageCauses(config, errors),
                strictBoolean(config, errors, "chat.enabled"),
                strictBoolean(config, errors, "connection-messages.enabled"),
                strictBoolean(config, errors, "player-list.enabled"),
                strictInt(config, errors, "player-list.refresh-seconds", 1, 60),
                strictBoolean(config, errors, "player-list.show-ping"),
                strictBoolean(config, errors, "player-list.show-tps"),
                strictBoolean(config, errors, "player-list.show-mspt"),
                strictBoolean(config, errors, "player-list.show-world"),
                strictBoolean(config, errors, "player-list.show-unread-notifications"),
                strictBoolean(config, errors, "player-list.staff-badges"),
                strictBoolean(config, errors, "player-list.afk.enabled"),
                Duration.ofSeconds(strictInt(
                        config,
                        errors,
                        "player-list.afk.after-seconds",
                        30,
                        86_400
                )),
                strictBoolean(config, errors, "sleep.enabled"),
                strictInt(config, errors, "sleep.required-percentage", 1, 100),
                strictBoolean(config, errors, "sleep.exclude-afk"),
                strictBoolean(config, errors, "sleep.clear-weather"),
                strictBoolean(config, errors, "server-list.enabled"),
                strictTextList(config, errors, "server-list.announcements"),
                strictBoolean(config, errors, "mentions.enabled"),
                Duration.ofSeconds(strictInt(config, errors, "mentions.cooldown-seconds", 0, 300)),
                strictInt(config, errors, "mentions.max-per-message", 1, 10),
                strictInt(config, errors, "maintenance.join-block-seconds", 0, 3600),
                strictBoolean(config, errors, "maintenance.backup-before-restart"),
                strictBoolean(config, errors, "journey.enabled"),
                strictInt(config, errors, "journey.welcome-delay-ticks", 0, 1200),
                strictBoolean(config, errors, "journey.vanilla-guide.enabled"),
                Duration.ofSeconds(strictInt(
                        config,
                        errors,
                        "journey.vanilla-guide.minimum-gap-seconds",
                        0,
                        3600
                )),
                strictVanillaGuideTopics(config, errors),
                strictBoolean(config, errors, "welcome-back.enabled"),
                Duration.ofHours(strictInt(
                        config,
                        errors,
                        "welcome-back.minimum-away-hours",
                        1,
                        8_760
                )),
                strictInt(config, errors, "welcome-back.delay-ticks", 0, 1200),
                strictBoolean(config, errors, "welcome-back.auto-open"),
                strictBoolean(config, errors, "player-profiles.enabled"),
                strictBoolean(config, errors, "statistics.enabled"),
                strictBoolean(config, errors, "statistics.public-viewing"),
                strictBoolean(config, errors, "mail.enabled"),
                strictInt(config, errors, "mail.maximum-length", 16, 1000),
                Duration.ofSeconds(strictInt(config, errors, "mail.cooldown-seconds", 0, 3600)),
                strictInt(config, errors, "mail.maximum-per-hour", 1, 100),
                strictBoolean(config, errors, "new-player-spawn.enabled"),
                strictString(config, errors, "new-player-spawn.world"),
                strictInt(config, errors, "new-player-spawn.min-x", -29_999_984, 29_999_984),
                strictInt(config, errors, "new-player-spawn.max-x", -29_999_984, 29_999_984),
                strictInt(config, errors, "new-player-spawn.min-z", -29_999_984, 29_999_984),
                strictInt(config, errors, "new-player-spawn.max-z", -29_999_984, 29_999_984),
                strictInt(config, errors, "new-player-spawn.preload-locations", 1, 100),
                strictInt(config, errors, "new-player-spawn.minimum-separation", 0, 100_000),
                strictInt(config, errors, "new-player-spawn.max-generation-attempts", 1, 10_000),
                strictInt(config, errors, "new-player-spawn.generation-delay-ticks", 1, 12_000),
                strictDouble(config, errors, "new-player-spawn.minimum-tps", 0.0, 20.0),
                strictInt(config, errors, "new-player-spawn.landing-radius", 0, 8),
                strictInt(config, errors, "new-player-spawn.max-height-variation", 0, 16),
                strictBiomeKeys(config, errors, "new-player-spawn.allowed-biomes"),
                strictBiomeKeys(config, errors, "new-player-spawn.blocked-biomes")
        );
        if (settings.newPlayerSpawnMinX() > settings.newPlayerSpawnMaxX()) {
            errors.add("new-player-spawn.min-x must not exceed max-x");
        }
        if (settings.newPlayerSpawnMinZ() > settings.newPlayerSpawnMaxZ()) {
            errors.add("new-player-spawn.min-z must not exceed max-z");
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        return settings;
    }

    private static int boundedInt(
            FileConfiguration config,
            Logger logger,
            String path,
            int fallback,
            int minimum,
            int maximum
    ) {
        int value = config.getInt(path, fallback);
        if (value < minimum || value > maximum) {
            logger.warning("Configuration value '" + path + "' must be between "
                    + minimum + " and " + maximum + "; using " + fallback + ".");
            return fallback;
        }
        return value;
    }

    private static double boundedDouble(
            FileConfiguration config,
            Logger logger,
            String path,
            double fallback,
            double minimum,
            double maximum
    ) {
        double value = config.getDouble(path, fallback);
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            logger.warning("Configuration value '" + path + "' must be between "
                    + minimum + " and " + maximum + "; using " + fallback + ".");
            return fallback;
        }
        return value;
    }

    private static int strictInt(
            FileConfiguration config,
            List<String> errors,
            String path,
            int minimum,
            int maximum
    ) {
        if (!config.isInt(path)) {
            errors.add(path + " must be an integer");
            return minimum;
        }
        int value = config.getInt(path);
        if (value < minimum || value > maximum) {
            errors.add(path + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static boolean strictBoolean(
            FileConfiguration config,
            List<String> errors,
            String path
    ) {
        if (!config.isBoolean(path)) {
            errors.add(path + " must be true or false");
        }
        return config.getBoolean(path);
    }

    private static double strictDouble(
            FileConfiguration config,
            List<String> errors,
            String path,
            double minimum,
            double maximum
    ) {
        if (!config.isDouble(path) && !config.isInt(path)) {
            errors.add(path + " must be a number");
            return minimum;
        }
        double value = config.getDouble(path);
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            errors.add(path + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static String strictString(
            FileConfiguration config,
            List<String> errors,
            String path
    ) {
        if (!config.isString(path)) {
            errors.add(path + " must be text");
            return "";
        }
        return config.getString(path, "").strip();
    }

    private static List<String> textList(
            FileConfiguration config,
            Logger logger,
            String path
    ) {
        ArrayList<String> values = new ArrayList<>();
        for (Object configured : config.getList(path, List.of())) {
            if (configured instanceof String text && !text.isBlank()) {
                values.add(text.strip());
            } else {
                logger.warning("Ignored a blank or non-text entry in " + path + ".");
            }
        }
        return List.copyOf(values);
    }

    private static List<String> strictTextList(
            FileConfiguration config,
            List<String> errors,
            String path
    ) {
        if (!config.isList(path)) {
            errors.add(path + " must be a list");
            return List.of();
        }
        ArrayList<String> values = new ArrayList<>();
        for (Object configured : config.getList(path, List.of())) {
            if (!(configured instanceof String text) || text.isBlank()) {
                errors.add(path + " must contain only non-blank text");
            } else {
                values.add(text.strip());
            }
        }
        return List.copyOf(values);
    }

    private static Set<String> vanillaGuideTopics(
            FileConfiguration config,
            Logger logger
    ) {
        LinkedHashSet<String> enabled = new LinkedHashSet<>();
        for (String topic : VanillaGuideTopic.keys()) {
            String path = "journey.vanilla-guide.topics." + topic;
            Object configured = config.get(path);
            if (configured != null && !(configured instanceof Boolean)) {
                logger.warning("Configuration value '" + path
                        + "' must be true or false; using true.");
                enabled.add(topic);
            } else if (config.getBoolean(path, true)) {
                enabled.add(topic);
            }
        }
        return Set.copyOf(enabled);
    }

    private static Set<String> customDeathMessageCauses(
            FileConfiguration config,
            Logger logger
    ) {
        LinkedHashSet<String> enabled = new LinkedHashSet<>();
        for (String cause : CustomDeathCause.keys()) {
            String path = "custom-death-messages.causes." + cause;
            Object configured = config.get(path);
            if (configured != null && !(configured instanceof Boolean)) {
                logger.warning("Configuration value '" + path
                        + "' must be true or false; using true.");
                enabled.add(cause);
            } else if (config.getBoolean(path, true)) {
                enabled.add(cause);
            }
        }
        return Set.copyOf(enabled);
    }

    private static Set<String> strictCustomDeathMessageCauses(
            FileConfiguration config,
            List<String> errors
    ) {
        LinkedHashSet<String> enabled = new LinkedHashSet<>();
        for (String cause : CustomDeathCause.keys()) {
            String path = "custom-death-messages.causes." + cause;
            if (strictBoolean(config, errors, path)) {
                enabled.add(cause);
            }
        }
        return Set.copyOf(enabled);
    }

    private static Set<String> strictVanillaGuideTopics(
            FileConfiguration config,
            List<String> errors
    ) {
        LinkedHashSet<String> enabled = new LinkedHashSet<>();
        for (String topic : VanillaGuideTopic.keys()) {
            String path = "journey.vanilla-guide.topics." + topic;
            if (strictBoolean(config, errors, path)) {
                enabled.add(topic);
            }
        }
        return Set.copyOf(enabled);
    }

    private static Set<String> biomeKeys(
            FileConfiguration config,
            Logger logger,
            String path
    ) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String configured : config.getStringList(path)) {
            String normalized = configured.strip().toLowerCase(Locale.ROOT);
            if (!validNamespacedKey(normalized)) {
                logger.warning("Ignored invalid biome key '" + configured + "' in " + path + ".");
            } else {
                keys.add(normalized);
            }
        }
        return Set.copyOf(keys);
    }

    private static Set<String> strictBiomeKeys(
            FileConfiguration config,
            List<String> errors,
            String path
    ) {
        if (!config.isList(path)) {
            errors.add(path + " must be a list");
            return Set.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Object configured : config.getList(path, List.of())) {
            if (!(configured instanceof String text) || !validNamespacedKey(text.strip())) {
                errors.add(path + " contains an invalid biome key");
                continue;
            }
            keys.add(text.strip().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(keys);
    }

    private static boolean validNamespacedKey(String value) {
        return value.matches("[a-z0-9._-]+:[a-z0-9/._-]+");
    }
}

package gg.nurmi.survivaltweaks.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginSettingsTest {

    @Test
    void bundledConfigurationPassesStrictValidation() {
        YamlConfiguration config = bundledConfig();

        PluginSettings settings = PluginSettings.validate(config);

        assertEquals(3, settings.maxHomes());
        assertTrue(settings.performanceGovernorEnabled());
        assertEquals(40.0, settings.performanceReducedMspt());
        assertEquals(47.0, settings.performanceCriticalMspt());
        assertEquals(256, settings.performanceWorkBudgetPerTick());
        assertEquals(5, settings.lockTargetDistance());
        assertEquals(0, settings.purgeInactiveLocksDays());
        assertTrue(settings.deathFloatingGuideEnabled());
        assertEquals(32.0, settings.deathFloatingGuideNearDistance());
        assertEquals(3.5, settings.deathFloatingGuideOffset());
        assertTrue(settings.customDeathMessagesEnabled());
        assertEquals(5, settings.customDeathMessageRareVariantPercent());
        assertEquals(12, settings.customDeathMessageCauses().size());
        assertTrue(settings.newPlayerSpawnEnabled());
        assertEquals(10, settings.newPlayerSpawnPreloadLocations());
        assertEquals(2, settings.newPlayerSpawnLandingRadius());
        assertEquals(18.0, settings.newPlayerSpawnMinimumTps());
        assertTrue(settings.newPlayerSpawnBlockedBiomes().contains("minecraft:ocean"));
        assertTrue(settings.playerListEnabled());
        assertTrue(settings.playerListShowMspt());
        assertEquals(2, settings.playerListRefreshSeconds());
        assertEquals(300, settings.afkTimeout().toSeconds());
        assertTrue(settings.sleepVotingEnabled());
        assertEquals(50, settings.sleepRequiredPercentage());
        assertTrue(settings.serverListEnabled());
        assertEquals(3, settings.serverListAnnouncements().size());
        assertTrue(settings.mentionsEnabled());
        assertEquals(10, settings.mentionCooldown().toSeconds());
        assertTrue(settings.journeyEnabled());
        assertTrue(settings.vanillaGuideEnabled());
        assertEquals(120, settings.vanillaGuideMinimumGap().toSeconds());
        assertEquals(7, settings.vanillaGuideTopics().size());
        assertTrue(settings.vanillaGuideTopics().contains("nether-coordinates"));
        assertTrue(settings.welcomeBackEnabled());
        assertEquals(6, settings.welcomeBackMinimumAway().toHours());
        assertTrue(settings.playerProfilesEnabled());
        assertTrue(settings.statisticsEnabled());
        assertTrue(settings.statisticsPublicViewing());
        assertTrue(settings.mailEnabled());
        assertEquals(160, settings.mailMaximumLength());
        assertEquals(10, settings.mailMaximumPerHour());
        assertEquals(128, settings.treeFellerMaxBlocks());
        assertTrue(settings.fastLeafDecayEnabled());
        assertEquals(2, settings.fastLeafDecayDelayTicks());
        assertEquals(5, settings.fastLeafDecayRadius());
        assertTrue(settings.petProtectionEnabled());
        assertTrue(settings.hotbarRefillEnabled());
        assertTrue(settings.decorationProtectionEnabled());
        assertTrue(settings.atmosphereAmbientEffects());
        assertTrue(settings.atmosphereDeathSiteWisps());
        assertTrue(settings.atmosphereDeepDarkSpores());
        assertTrue(settings.atmosphereSculkWarningActionbar());
    }

    @Test
    void strictValidationRejectsOutOfRangeValues() {
        YamlConfiguration config = bundledConfig();
        config.set("home.max-amount", 0);

        assertThrows(IllegalArgumentException.class, () -> PluginSettings.validate(config));
    }

    @Test
    void strictValidationRejectsAnInvertedSpawnRange() {
        YamlConfiguration config = bundledConfig();
        config.set("new-player-spawn.min-x", 100);
        config.set("new-player-spawn.max-x", -100);

        assertThrows(IllegalArgumentException.class, () -> PluginSettings.validate(config));
    }

    @Test
    void strictValidationRejectsInvalidBiomeKeysAndTps() {
        YamlConfiguration config = bundledConfig();
        config.set("new-player-spawn.blocked-biomes", java.util.List.of("not a biome"));
        config.set("new-player-spawn.minimum-tps", 21.0);

        assertThrows(IllegalArgumentException.class, () -> PluginSettings.validate(config));
    }

    @Test
    void strictValidationRejectsInvalidPlayerListTiming() {
        YamlConfiguration config = bundledConfig();
        config.set("player-list.refresh-seconds", 0);
        config.set("player-list.afk.after-seconds", 20);

        assertThrows(IllegalArgumentException.class, () -> PluginSettings.validate(config));
    }

    @Test
    void strictValidationRejectsInvalidExperienceSystemSettings() {
        YamlConfiguration config = bundledConfig();
        config.set("sleep.required-percentage", 0);
        config.set("mentions.max-per-message", 20);
        config.set("server-list.announcements", java.util.List.of(""));
        config.set("mail.maximum-length", 4);
        config.set("welcome-back.minimum-away-hours", 0);
        config.set("journey.vanilla-guide.minimum-gap-seconds", 5000);
        config.set("journey.vanilla-guide.topics.enchanting", "sometimes");
        config.set("statistics.public-viewing", "sometimes");
        config.set("death-recovery.floating-guide.offset", 20.0);
        config.set("custom-death-messages.rare-variant-percent", 101);
        config.set("custom-death-messages.causes.fall", "sometimes");

        assertThrows(IllegalArgumentException.class, () -> PluginSettings.validate(config));
    }

    @Test
    void strictValidationRejectsInvalidLeafDecaySettings() {
        YamlConfiguration config = bundledConfig();
        config.set("fast-leaf-decay.decay-delay-ticks", 0);
        config.set("fast-leaf-decay.radius", 17);

        assertThrows(IllegalArgumentException.class, () -> PluginSettings.validate(config));
    }

    private YamlConfiguration bundledConfig() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"),
                StandardCharsets.UTF_8
        ));
    }
}

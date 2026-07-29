package gg.nurmi.survivaltweaks;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginResourcesTest {

    @Test
    void pluginDescriptorContainsTheExpectedCommandsAndApiVersion() {
        YamlConfiguration descriptor = resource("plugin.yml");

        assertEquals("26.2", descriptor.getString("api-version"));
        assertEquals(
                "survivaltweaks.command.teleport",
                descriptor.getString("commands.teleport.permission")
        );
        assertNotNull(descriptor.getConfigurationSection("commands.teleportaccept"));
        assertNotNull(descriptor.getConfigurationSection("commands.lock"));
        assertNotNull(descriptor.getConfigurationSection("commands.unlock"));
        assertNotNull(descriptor.getConfigurationSection("commands.survivaltweaks"));
        assertNotNull(descriptor.getConfigurationSection("commands.teleportinbox"));
        assertNotNull(descriptor.getConfigurationSection("commands.deathlocation"));
        assertNotNull(descriptor.getConfigurationSection("commands.afk"));
        assertNotNull(descriptor.getConfigurationSection("commands.mail"));
        assertNotNull(descriptor.getConfigurationSection("commands.profile"));
        assertNotNull(descriptor.getConfigurationSection("commands.stats"));
        assertNotNull(descriptor.getConfigurationSection("commands.welcome"));
        assertNotNull(descriptor.getConfigurationSection("commands.guide"));
        assertNotNull(descriptor.getConfigurationSection("permissions.survivaltweaks.teleport.bypass"));
        assertNotNull(descriptor.getConfigurationSection("permissions.survivaltweaks.lock.admin"));
        assertNotNull(descriptor.getConfigurationSection("permissions.survivaltweaks.command.reload"));
        assertNotNull(descriptor.getConfigurationSection("permissions.survivaltweaks.command.doctor"));
        assertNotNull(descriptor.getConfigurationSection("permissions.survivaltweaks.command.spawnpool"));
        assertNotNull(descriptor.getConfigurationSection("permissions.survivaltweaks.command.backup"));
        assertNotNull(descriptor.getConfigurationSection("permissions.survivaltweaks.command.afk"));
        assertNotNull(descriptor.getConfigurationSection("permissions.survivaltweaks.playerlist.staff"));
        assertNotNull(descriptor.getConfigurationSection("permissions.survivaltweaks.command.maintenance"));
        assertNotNull(descriptor.getConfigurationSection("permissions.survivaltweaks.maintenance.bypass"));
        assertNotNull(descriptor.getConfigurationSection("permissions.survivaltweaks.command.mail"));
        assertNotNull(descriptor.getConfigurationSection("permissions.survivaltweaks.command.profile"));
        assertNotNull(descriptor.getConfigurationSection("permissions.survivaltweaks.command.stats"));
        assertNotNull(descriptor.getConfigurationSection("permissions.survivaltweaks.command.guide"));
        assertNotNull(descriptor.getConfigurationSection("permissions.survivaltweaks.profile.bypass"));
        assertTrue(descriptor.getStringList("commands.survivaltweaks.aliases").contains("st"));
    }

    @Test
    void defaultConfigurationContainsRequiredMessageTemplates() {
        YamlConfiguration config = resource("config.yml");
        YamlConfiguration english = resource("messages_en.yml");
        YamlConfiguration finnish = resource("messages_fi.yml");

        assertEquals(messageKeys(english), messageKeys(finnish));
        assertNotNull(english.getString("chat-format"));
        assertNotNull(english.getString("home.teleported"));
        assertNotNull(english.getString("teleport.accept.accepted"));
        assertNotNull(english.getString("teleport.safety.warmup"));
        assertNotNull(english.getString("teleport.actionbar.cooldown"));
        assertNotNull(english.getString("new-player-spawn.preparing"));
        assertNotNull(english.getString("new-player-spawn.arrived"));
        assertNotNull(english.getString("new-player-spawn.replacing"));
        assertNotNull(english.getString("player-list.header"));
        assertNotNull(english.getString("player-list.mspt"));
        assertNotNull(english.getString("player-list.afk-marker"));
        assertNotNull(english.getString("afk.enabled"));
        assertNotNull(english.getString("sleep.progress"));
        assertNotNull(english.getString("server-list.motd"));
        assertNotNull(english.getString("maintenance.restart-warning"));
        assertNotNull(english.getString("mentions.target-afk"));
        assertNotNull(english.getString("ui.journey.objective.home"));
        assertNotNull(english.getString("mail.result.sent"));
        assertNotNull(english.getString("welcome-back.prompt"));
        assertNotNull(english.getString("ui.mailbox.title"));
        assertNotNull(english.getString("ui.profile.status.online"));
        assertNotNull(english.getString("ui.statistics.label.playtime"));
        assertNotNull(english.getString("ui.welcome-back.news-detail"));
        assertNotNull(english.getString("vanilla-guide.hint.nether-coordinates"));
        assertNotNull(english.getString("ui.vanilla-guide.description.enchanting"));
        assertNotNull(english.getString("admin.help.header"));
        assertNotNull(english.getString("admin.doctor.summary"));
        assertNotNull(english.getString("admin.spawnpool.status-pool"));
        assertNotNull(english.getString("admin.spawnpool.validate-result"));
        assertNotNull(english.getString("admin.backup.restore-staged"));
        assertNotNull(english.getString("lock.actionbar.owner"));
        assertNotNull(english.getString("death-recovery.compass-name"));
        assertNotNull(english.getString("death-recovery.guide-navigation"));
        assertNotNull(english.getString("death-recovery.guide-prompt-disabled"));
        assertNotNull(english.getString("death-messages.fall.1"));
        assertNotNull(english.getString("death-messages.world-border.rare"));
        assertNotNull(english.getString("ui.lock-panel.title"));
        assertNotNull(english.getString("ui.home-editor.title"));
        assertNotNull(english.getString("ui.teleport-inbox.title"));
        assertNotNull(english.getString("ui.home-menu.title"));
        assertNotNull(english.getString("ui.hub.title"));
        assertNotNull(english.getString("ui.preferences.language.auto"));
        assertNotNull(english.getString("ui.notifications.type.lock-access-denied"));
        assertNotNull(english.getString("ui.lock-panel.mode.deposit_only"));
        assertNotNull(english.getString("death-recovery.actionbar-navigation"));
        assertNotNull(finnish.getString("ui.unlock-dialog.title"));
        assertNotNull(config.getString("feedback.cues.teleport-complete.sound"));
        assertNotNull(config.getString("feedback.cues.lock-created.particle"));
        assertNotNull(config.getString("feedback.cues.teleport-countdown.sound"));
        assertNotNull(config.getString("feedback.cues.death-reached.particle"));
        assertNotNull(config.getString("feedback.cues.new-player-arrival.sound"));
        assertEquals(10, config.getInt("new-player-spawn.preload-locations"));
        assertEquals(2, config.getInt("new-player-spawn.landing-radius"));
        assertEquals(18.0, config.getDouble("new-player-spawn.minimum-tps"));
        assertTrue(config.getStringList("new-player-spawn.blocked-biomes").contains("minecraft:ocean"));
        assertTrue(config.getBoolean("ui.home-menu-enabled"));
        assertTrue(config.getBoolean("ui.dialogs-enabled"));
        assertTrue(config.getBoolean("ui.action-bar-enabled"));
        assertTrue(config.getBoolean("ui.lock-target-hints-enabled"));
        assertTrue(config.getBoolean("death-recovery.enabled"));
        assertTrue(config.getBoolean("death-recovery.floating-guide.enabled"));
        assertEquals(24.0, config.getDouble("death-recovery.floating-guide.near-distance"));
        assertEquals(3.5, config.getDouble("death-recovery.floating-guide.offset"));
        assertTrue(config.getBoolean("custom-death-messages.enabled"));
        assertEquals(5, config.getInt("custom-death-messages.rare-variant-percent"));
        assertTrue(config.getBoolean("custom-death-messages.causes.fall"));
        assertTrue(config.getBoolean("player-list.enabled"));
        assertTrue(config.getBoolean("player-list.show-mspt"));
        assertEquals(300, config.getInt("player-list.afk.after-seconds"));
        assertEquals(50, config.getInt("sleep.required-percentage"));
        assertEquals(3, config.getStringList("server-list.announcements").size());
        assertEquals(10, config.getInt("mentions.cooldown-seconds"));
        assertTrue(config.getBoolean("maintenance.backup-before-restart"));
        assertTrue(config.getBoolean("journey.enabled"));
        assertTrue(config.getBoolean("journey.vanilla-guide.enabled"));
        assertEquals(120, config.getInt("journey.vanilla-guide.minimum-gap-seconds"));
        assertTrue(config.getBoolean("journey.vanilla-guide.topics.villager-curing"));
        assertTrue(config.getBoolean("welcome-back.enabled"));
        assertTrue(config.getBoolean("player-profiles.enabled"));
        assertTrue(config.getBoolean("statistics.enabled"));
        assertTrue(config.getBoolean("statistics.public-viewing"));
        assertTrue(config.getBoolean("mail.enabled"));
        assertEquals(160, config.getInt("mail.maximum-length"));
        assertNotNull(config.getString("feedback.cues.mention.sound"));
        assertNotNull(config.getString("feedback.cues.mail.sound"));
        assertNotNull(config.getString("feedback.cues.guide-hint.sound"));
        assertNotNull(config.getString("feedback.cues.maintenance-warning.sound"));
        assertNotNull(finnish.getString("lock.denied-open"));
    }

    @Test
    void bothCatalogsDeclareTheirMessagesInTheSameOrder() {
        YamlConfiguration english = resource("messages_en.yml");
        YamlConfiguration finnish = resource("messages_fi.yml");

        // Matching order keeps the two files reviewable side by side.
        assertEquals(orderedMessageKeys(english), orderedMessageKeys(finnish));
    }

    @Test
    void bothCatalogsUseTheSameTagsAndPlaceholdersForEachMessage() {
        YamlConfiguration english = resource("messages_en.yml");
        YamlConfiguration finnish = resource("messages_fi.yml");

        // Translated hover text and localized argument names in usage strings
        // are the only places the two catalogs may legitimately differ.
        Set<String> translatable = Set.of(
                "welcome-back.prompt",
                "teleport.usage",
                "shout.usage",
                "death-recovery.guide-prompt-enabled",
                "death-recovery.guide-prompt-disabled"
        );
        for (String key : orderedMessageKeys(english)) {
            if (translatable.contains(key)) {
                continue;
            }
            assertEquals(
                    tags(english.getString(key)),
                    tags(finnish.getString(key)),
                    key
            );
        }
    }

    @Test
    void everyCountedMessageOffersBothASingularAndAPluralForm() {
        YamlConfiguration english = resource("messages_en.yml");
        YamlConfiguration finnish = resource("messages_fi.yml");

        // Finnish inflects the counted noun, so one template cannot serve both.
        for (String key : List.of(
                "player-list.unread",
                "notifications.unread-summary",
                "ui.hub.homes-count",
                "ui.hub.mail-count",
                "ui.hub.notifications-count",
                "ui.mailbox.title",
                "ui.notifications.title",
                "ui.notifications.age",
                "ui.welcome-back.count",
                "ui.profile.homes-value",
                "ui.profile.last-seen",
                "ui.teleport-inbox.expires",
                "teleport.received",
                "teleport.safety.warmup",
                "teleport.safety.cooldown",
                "death-recovery.recorded",
                "death-recovery.location",
                "death-recovery.compass-cooldown",
                "admin.restart.scheduled",
                "admin.doctor.truncated",
                "admin.backup.players-online",
                "admin.spawnpool.cleared"
        )) {
            for (YamlConfiguration catalog : List.of(english, finnish)) {
                assertNotNull(catalog.getString(key), key);
                assertNotNull(catalog.getString(key + "-one"), key + "-one");
            }
        }
    }

    @Test
    void everyRestartDurationOffersTheFormUsedBeforeAPostposition() {
        YamlConfiguration english = resource("messages_en.yml");
        YamlConfiguration finnish = resource("messages_fi.yml");

        // "5 minuutin kuluttua" needs the genitive, not the standalone partitive.
        for (String unit : List.of("second", "seconds", "minute", "minutes", "hour", "hours")) {
            String key = "maintenance.duration." + unit;
            for (YamlConfiguration catalog : List.of(english, finnish)) {
                assertNotNull(catalog.getString(key), key);
                assertNotNull(catalog.getString(key + "-before"), key + "-before");
            }
        }
    }

    private YamlConfiguration resource(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(name)),
                StandardCharsets.UTF_8
        ));
    }

    private List<String> orderedMessageKeys(YamlConfiguration catalog) {
        return catalog.getKeys(true).stream().filter(catalog::isString).toList();
    }

    /** Every MiniMessage tag and placeholder in a template, sorted. */
    private List<String> tags(String template) {
        return Pattern.compile("<[^>]+>").matcher(Objects.requireNonNull(template)).results()
                .map(java.util.regex.MatchResult::group)
                .sorted()
                .toList();
    }

    private Set<String> messageKeys(YamlConfiguration catalog) {
        return catalog.getKeys(true).stream()
                .filter(catalog::isString)
                .collect(Collectors.toSet());
    }
}

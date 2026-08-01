package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReloadServiceTest {

    @TempDir
    Path dataFolder;

    @Test
    void reloadAppliesOnlyAfterEveryCandidatePassesValidation() throws Exception {
        copyResource("config.yml");
        copyResource("messages_en.yml");
        copyResource("messages_fi.yml");
        JavaPlugin plugin = plugin();
        YamlConfiguration initialConfig = bundledConfig();
        SettingsService settings = new SettingsService(PluginSettings.validate(initialConfig));
        MessageService messages = new MessageService(plugin, initialConfig, plugin.getLogger());
        FeedbackService feedback = new FeedbackService(initialConfig, plugin.getLogger());
        AtomicReference<PluginSettings> applied = new AtomicReference<>();
        ReloadService reloads = new ReloadService(
                plugin,
                settings,
                messages,
                feedback,
                new BackupService(
                        dataFolder,
                        Clock.fixed(Instant.parse("2026-07-28T08:00:00Z"), ZoneOffset.UTC),
                        plugin.getLogger()
                ),
                applied::set
        );

        YamlConfiguration valid = bundledConfig();
        valid.set("home.max-amount", 9);
        valid.save(dataFolder.resolve("config.yml").toFile());
        ReloadService.Result successful = reloads.reload();

        assertTrue(successful.successful());
        assertEquals(9, settings.current().maxHomes());
        assertEquals(9, applied.get().maxHomes());

        YamlConfiguration invalid = bundledConfig();
        invalid.set("home.max-amount", 12);
        invalid.set("feedback.cues.home-saved.particle", "NOT_A_PARTICLE");
        invalid.save(dataFolder.resolve("config.yml").toFile());
        ReloadService.Result rejected = reloads.reload();

        assertFalse(rejected.successful());
        assertEquals(9, settings.current().maxHomes());
        assertEquals(9, applied.get().maxHomes());
        try (var backups = Files.list(dataFolder.resolve("backups"))) {
            assertEquals(2L, backups.filter(path -> path.toString().endsWith(".zip")).count());
        }
    }

    @Test
    void reloadCallbackReceivesTheExactValidatedConfiguration() throws Exception {
        copyResource("config.yml");
        copyResource("messages_en.yml");
        copyResource("messages_fi.yml");
        JavaPlugin plugin = plugin();
        YamlConfiguration initialConfig = bundledConfig();
        SettingsService settings = new SettingsService(PluginSettings.validate(initialConfig));
        MessageService messages = new MessageService(plugin, initialConfig, plugin.getLogger());
        FeedbackService feedback = new FeedbackService(initialConfig, plugin.getLogger());
        AtomicReference<FileConfiguration> appliedConfig = new AtomicReference<>();
        ReloadService reloads = new ReloadService(
                plugin,
                settings,
                messages,
                feedback,
                null,
                (updated, configuration) -> appliedConfig.set(configuration)
        );

        YamlConfiguration candidate = bundledConfig();
        candidate.set("home.max-amount", 13);
        candidate.save(dataFolder.resolve("config.yml").toFile());

        assertTrue(reloads.reload().successful());
        assertEquals(13, appliedConfig.get().getInt("home.max-amount"));
    }

    @Test
    void failedRuntimeApplyRestoresSettingsAndRuntimeConfiguration() throws Exception {
        copyResource("config.yml");
        copyResource("messages_en.yml");
        copyResource("messages_fi.yml");
        JavaPlugin plugin = plugin();
        YamlConfiguration initialConfig = bundledConfig();
        when(plugin.getConfig()).thenReturn(initialConfig);
        SettingsService settings = new SettingsService(PluginSettings.validate(initialConfig));
        MessageService messages = new MessageService(plugin, initialConfig, plugin.getLogger());
        FeedbackService feedback = new FeedbackService(initialConfig, plugin.getLogger());
        AtomicInteger callbackCalls = new AtomicInteger();
        AtomicReference<FileConfiguration> rolledBackConfig = new AtomicReference<>();
        ReloadService reloads = new ReloadService(
                plugin,
                settings,
                messages,
                feedback,
                null,
                (updated, configuration) -> {
                    if (callbackCalls.incrementAndGet() == 1) {
                        throw new IllegalStateException("simulated runtime apply failure");
                    }
                    rolledBackConfig.set(configuration);
                }
        );

        YamlConfiguration candidate = bundledConfig();
        candidate.set("home.max-amount", 14);
        candidate.save(dataFolder.resolve("config.yml").toFile());
        ReloadService.Result result = reloads.reload();

        assertFalse(result.successful());
        assertEquals(initialConfig.getInt("home.max-amount"), settings.current().maxHomes());
        assertEquals(2, callbackCalls.get());
        assertSame(initialConfig, rolledBackConfig.get());
        verify(plugin, never()).reloadConfig();
    }

    private JavaPlugin plugin() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getResource(anyString())).thenAnswer(invocation ->
                resource(invocation.getArgument(0))
        );
        return plugin;
    }

    private YamlConfiguration bundledConfig() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                resource("config.yml"),
                StandardCharsets.UTF_8
        ));
    }

    private void copyResource(String name) throws IOException {
        try (InputStream input = resource(name)) {
            Files.copy(input, dataFolder.resolve(name));
        }
    }

    private InputStream resource(String name) {
        InputStream input = getClass().getResourceAsStream("/" + name);
        if (input == null) {
            throw new IllegalArgumentException("Missing test resource " + name);
        }
        return input;
    }
}

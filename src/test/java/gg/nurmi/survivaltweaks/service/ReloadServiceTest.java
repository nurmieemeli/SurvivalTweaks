package gg.nurmi.survivaltweaks.service;

import com.sun.nio.file.ExtendedOpenOption;
import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.Server;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReloadServiceTest {

    @TempDir
    Path dataFolder;

    private final AtomicReference<Runnable> prepare = new AtomicReference<>();
    private final AtomicReference<Runnable> apply = new AtomicReference<>();

    @ParameterizedTest
    @CsvSource({
            "postgresql, storage.sqlite.file, ../unused.db",
            "sqlite, storage.remote.pool-size, 0",
            "sqlite, storage.remote.type, invalid",
            "sqlite, storage.remote.socket-timeout-seconds, 0",
            "sqlite, storage.remote.query-timeout-seconds, 0"
    })
    void testingIgnoresUnusedEngineSettingsWhileMigrationRejectsThem(
            String backend, String path, String value
    ) throws Exception {
        copyResource("config.yml");
        Path file = dataFolder.resolve("config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
        config.set("storage.remote.type", "postgresql");
        config.set(path, path.endsWith("file") || path.endsWith("type") ? value : Integer.valueOf(value));
        config.save(file.toFile());
        byte[] original = Files.readAllBytes(file);
        var reloads = new ReloadService(plugin(), null, null, null, null, null);

        assertEquals(backend, reloads.prepareStorageTest(backend).backend().key());
        assertThrows(IllegalArgumentException.class, () -> reloads.prepareStorageBackend(backend));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @ParameterizedTest
    @CsvSource({
            "sqlite, storage.sqlite.file, ../unsafe.db",
            "sqlite, storage.remote.connection-timeout-millis, 0",
            "postgresql, storage.remote.pool-size, 0"
    })
    void testingStillRejectsInvalidSettingsUsedByTheRequestedEngine(
            String backend, String path, String value
    ) throws Exception {
        copyResource("config.yml");
        Path file = dataFolder.resolve("config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
        config.set("storage.remote.type", "postgresql");
        config.set(path, path.endsWith("file") ? value : Integer.valueOf(value));
        config.save(file.toFile());
        var reloads = new ReloadService(plugin(), null, null, null, null, null);

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> reloads.prepareStorageTest(backend)).getMessage().contains(path));
    }

    @ParameterizedTest
    @CsvSource({
            "home.max-amount, -1, home.max-amount",
            "storage.portable-exports.retention, 0, Portable export settings",
            "config-version, 0, config-version"
    })
    void destinationTestingAllowsUnrelatedErrorsButMigrationRequiresTheFullConfig(
            String path, int value, String problem
    ) throws Exception {
        copyResource("config.yml");
        Path file = dataFolder.resolve("config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
        config.set("storage.remote.type", "postgresql");
        config.set("storage.remote.database", "edited_destination");
        config.set(path, value);
        config.save(file.toFile());
        byte[] original = Files.readAllBytes(file);
        var reloads = new ReloadService(plugin(), null, null, null, null, null);

        assertEquals("edited_destination", reloads.prepareStorageTest("postgresql").database());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> reloads.prepareStorageBackend("postgresql")).getMessage().contains(problem));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void lockedConfigKeepsTheActiveBackendUntilPublicationSucceeds() throws Exception {
        JavaPlugin plugin = plugin();
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.backend", "sqlite");
        config.set("storage.remote.type", "postgresql");
        when(plugin.getConfig()).thenReturn(config);
        Path file = dataFolder.resolve("config.yml");
        config.save(file.toFile());
        String original = Files.readString(file);
        var reloads = new ReloadService(plugin, null, null, null, null, null);
        ReloadService.Candidate candidate = reloads.prepareStorageBackend("postgresql");

        try (var ignored = Files.newByteChannel(file, StandardOpenOption.READ,
                ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE)) {
            assertThrows(IOException.class, () -> reloads.publishStorageBackend(candidate));
        }
        assertEquals(original, Files.readString(file));
        assertEquals("sqlite", config.getString("storage.backend"));
        reloads.publishStorageBackend(candidate);
        assertEquals("postgresql", YamlConfiguration.loadConfiguration(file.toFile()).getString("storage.backend"));
    }

    @Test
    void migrationPreservesUnloadedConfigEditsAndUsesTheirDestination() throws Exception {
        copyResource("config.yml");
        JavaPlugin plugin = plugin();
        YamlConfiguration active = bundledConfig();
        when(plugin.getConfig()).thenReturn(active);
        Path file = dataFolder.resolve("config.yml");
        YamlConfiguration edited = YamlConfiguration.loadConfiguration(file.toFile());
        edited.set("home.max-amount", 9);
        edited.set("storage.remote.type", "postgresql");
        edited.set("storage.remote.database", "edited_destination");
        edited.save(file.toFile());
        var reloads = new ReloadService(plugin, null, null, null, null, null);

        ReloadService.Candidate candidate = reloads.prepareStorageBackend("postgresql");
        assertEquals("edited_destination", candidate.configuration().getString("storage.remote.database"));
        reloads.publishStorageBackend(candidate);

        YamlConfiguration published = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(9, published.getInt("home.max-amount"));
        assertEquals("edited_destination", published.getString("storage.remote.database"));
        assertEquals("postgresql", published.getString("storage.backend"));
        assertEquals("auto", active.getString("storage.backend"));
    }

    @Test
    void configEditDuringMigrationBlocksPublicationWithoutChangingEitherConfig() throws Exception {
        copyResource("config.yml");
        JavaPlugin plugin = plugin();
        YamlConfiguration active = bundledConfig();
        when(plugin.getConfig()).thenReturn(active);
        var reloads = new ReloadService(plugin, null, null, null, null, null);
        ReloadService.Candidate candidate = reloads.prepareStorageBackend("sqlite");
        Path file = dataFolder.resolve("config.yml");
        String edited = Files.readString(file) + "\naudit-edit: retained\n";
        Files.writeString(file, edited);

        assertThrows(IllegalStateException.class, () -> reloads.publishStorageBackend(candidate));

        assertEquals(edited, Files.readString(file));
        assertEquals("auto", active.getString("storage.backend"));
        try (var files = Files.list(dataFolder)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".config-")));
        }
    }

    @Test
    void malformedPasswordIsAbsentFromReloadDiagnostics() throws Exception {
        copyResource("config.yml");
        copyResource("messages_en.yml");
        copyResource("messages_fi.yml");
        JavaPlugin plugin = plugin();
        Logger logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        YamlConfiguration initial = bundledConfig();
        SettingsService settings = new SettingsService(PluginSettings.validate(initial));
        ReloadService reloads = new ReloadService(plugin, settings, new MessageService(plugin, initial, logger),
                new FeedbackService(initial, logger), null, (updated, configuration) -> {});
        Path config = dataFolder.resolve("config.yml");
        String password = "fake-secret";
        Files.writeString(config, Files.readString(config).replaceFirst("(?m)^    password:.*$",
                "    password: \"" + password + "\" garbage"));

        ReloadService.Result result = reload(reloads);

        assertFalse(result.successful());
        assertTrue(result.reason().contains("Could not parse config.yml at line"));
        assertFalse(result.reason().contains(password));
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(logger).log(eq(Level.WARNING), eq("Rejected SurvivalTweaks reload"), error.capture());
        StringWriter stack = new StringWriter();
        error.getValue().printStackTrace(new PrintWriter(stack));
        assertFalse(stack.toString().contains(password));
    }

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
                (updated, configuration) -> applied.set(updated)
        );

        YamlConfiguration valid = bundledConfig();
        valid.set("home.max-amount", 9);
        valid.save(dataFolder.resolve("config.yml").toFile());
        ReloadService.Result successful = reload(reloads);

        assertTrue(successful.successful());
        assertEquals(9, settings.current().maxHomes());
        assertEquals(9, applied.get().maxHomes());

        YamlConfiguration invalid = bundledConfig();
        invalid.set("home.max-amount", 12);
        invalid.set("feedback.cues.home-saved.particle", "NOT_A_PARTICLE");
        invalid.save(dataFolder.resolve("config.yml").toFile());
        ReloadService.Result rejected = reload(reloads);

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

        assertTrue(reload(reloads).successful());
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
        ReloadService.Result result = reload(reloads);

        assertFalse(result.successful());
        assertEquals(initialConfig.getInt("home.max-amount"), settings.current().maxHomes());
        assertEquals(2, callbackCalls.get());
        assertSame(initialConfig, rolledBackConfig.get());
        verify(plugin, never()).reloadConfig();
    }

    @Test
    void configSetPublishesOnlyAValidatedEditAndRejectsConcurrentEdits() throws Exception {
        copyResource("config.yml");
        copyResource("messages_en.yml");
        copyResource("messages_fi.yml");
        JavaPlugin plugin = plugin();
        YamlConfiguration initial = bundledConfig();
        when(plugin.getConfig()).thenReturn(initial);
        SettingsService settings = new SettingsService(PluginSettings.validate(initial));
        ReloadService reloads = new ReloadService(plugin, settings,
                new MessageService(plugin, initial, plugin.getLogger()),
                new FeedbackService(initial, plugin.getLogger()), null, (updated, configuration) -> {});
        Path config = dataFolder.resolve("config.yml");
        String original = Files.readString(config);
        AtomicReference<ReloadService.Result> result = new AtomicReference<>();

        assertTrue(reloads.setConfigAsync("home.max-amount", -1, result::set));
        assertFalse(reloads.setConfigAsync("home.max-amount", 8, result::set));
        prepare.getAndSet(null).run();
        apply.getAndSet(null).run();
        assertFalse(result.get().successful());
        assertEquals(original, Files.readString(config));

        assertTrue(reloads.setConfigAsync("home.max-amount", 8, result::set));
        prepare.getAndSet(null).run();
        assertEquals(original, Files.readString(config));
        apply.getAndSet(null).run();
        assertTrue(result.get().successful());
        assertEquals(8, settings.current().maxHomes());
        assertEquals(8, YamlConfiguration.loadConfiguration(config.toFile()).getInt("home.max-amount"));
    }

    private ReloadService.Result reload(ReloadService reloads) {
        AtomicReference<ReloadService.Result> result = new AtomicReference<>();
        assertTrue(reloads.reloadAsync(result::set));
        prepare.getAndSet(null).run();
        apply.getAndSet(null).run();
        return result.get();
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
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            prepare.set(call.getArgument(1));
            return null;
        });
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            apply.set(call.getArgument(1));
            return null;
        });
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

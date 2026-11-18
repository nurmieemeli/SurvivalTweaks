package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.ConfigMigrationService;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.config.StrictYaml;
import gg.nurmi.survivaltweaks.storage.StorageConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.logging.Level;

public final class ReloadService {

    private final JavaPlugin plugin;
    private final SettingsService settings;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final BackupService backups;
    private final BiConsumer<PluginSettings, FileConfiguration> afterApply;
    private final AtomicBoolean reloading = new AtomicBoolean();

    public ReloadService(
            JavaPlugin plugin,
            SettingsService settings,
            MessageService messages,
            FeedbackService feedback,
            BackupService backups,
            BiConsumer<PluginSettings, FileConfiguration> afterApply
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.feedback = feedback;
        this.backups = backups;
        this.afterApply = afterApply;
    }

    public boolean reloadAsync(Consumer<Result> completion) {
        return reloadAsync(config -> {}, false, completion);
    }

    public boolean setConfigAsync(String path, Object value, Consumer<Result> completion) {
        return reloadAsync(config -> config.set(path, value), true, completion);
    }

    private boolean reloadAsync(Consumer<YamlConfiguration> edit, boolean publish, Consumer<Result> completion) {
        Objects.requireNonNull(completion, "completion");
        if (!reloading.compareAndSet(false, true)) {
            return false;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Prepared prepared;
            try {
                prepared = prepare(edit);
            } catch (Exception exception) {
                Result result = rejected(exception);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    reloading.set(false);
                    completion.accept(result);
                });
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Result result;
                try {
                    result = apply(prepared, publish);
                } catch (Exception exception) {
                    result = rejected(exception);
                }
                reloading.set(false);
                completion.accept(result);
            });
        });
        return true;
    }

    private Prepared prepare(Consumer<YamlConfiguration> edit) throws Exception {
        if (backups != null) {
            backups.createReloadBackup();
        }
        Candidate candidate = loadCandidateConfig();
        YamlConfiguration candidateConfig = candidate.configuration();
        edit.accept(candidateConfig);
        return new Prepared(
                validateConfiguration(candidateConfig, plugin.getDataFolder().toPath()),
                messages.prepareReload(candidateConfig),
                feedback.prepare(candidateConfig),
                candidateConfig,
                candidate.fingerprint()
        );
    }

    private Result apply(Prepared prepared, boolean publish) throws IOException {
        if (!prepared.configFingerprint().equals(configFingerprint(configPath()))) {
            throw new IllegalStateException(
                    "config.yml changed while reload validation was running; retry the command"
            );
        }
        PluginSettings previousSettings = settings.current();
        MessageService.Prepared previousMessages = messages.snapshot();
        FeedbackService.Prepared previousFeedback = feedback.snapshot();
        FileConfiguration previousConfig = plugin.getConfig();
        try {
            settings.apply(prepared.settings());
            messages.apply(prepared.messages());
            feedback.apply(prepared.feedback());
            afterApply.accept(prepared.settings(), prepared.configuration());
            if (publish) {
                publishConfig(prepared.configuration(), prepared.configFingerprint());
            }
        } catch (RuntimeException | IOException applyFailure) {
            settings.apply(previousSettings);
            messages.apply(previousMessages);
            feedback.apply(previousFeedback);
            try {
                afterApply.accept(previousSettings, previousConfig);
            } catch (RuntimeException rollbackFailure) {
                applyFailure.addSuppressed(rollbackFailure);
            }
            throw applyFailure;
        }
        plugin.getLogger().info("Reloaded configuration and language catalogs.");
        return Result.success();
    }

    public Candidate prepareStorageBackend(String backend) throws Exception {
        Candidate candidate = loadStorageCandidate(backend);
        validateConfiguration(candidate.configuration(), plugin.getDataFolder().toPath());
        return candidate;
    }

    public StorageConfiguration prepareStorageTest(String backend) throws Exception {
        Candidate candidate = loadStorageCandidate(backend);
        YamlConfiguration config = candidate.configuration();
        // Test only the requested endpoint; the connection timeout is also used by SQLite.
        if (backend.equals("sqlite")) {
            for (String key : List.of("type", "pool-size", "socket-timeout-seconds", "query-timeout-seconds")) {
                String path = "storage.remote." + key;
                config.set(path, config.getDefaults().get(path));
            }
        } else {
            config.set("storage.sqlite.file", config.getDefaults().get("storage.sqlite.file"));
        }
        return StorageConfiguration.load(config, plugin.getDataFolder().toPath());
    }

    private Candidate loadStorageCandidate(String backend) throws Exception {
        Candidate candidate = loadCandidateConfig();
        YamlConfiguration config = candidate.configuration();
        if (!backend.equals("sqlite")
                && !backend.equalsIgnoreCase(config.getString("storage.remote.type", "").strip())) {
            throw new IllegalArgumentException("storage.remote.type must select " + backend);
        }
        config.set("storage.backend", backend);
        return candidate;
    }

    public void publishStorageBackend(Candidate candidate) throws IOException {
        publishConfig(candidate.configuration(), candidate.fingerprint());
    }

    private void publishConfig(FileConfiguration configuration, String expectedFingerprint) throws IOException {
        Path path = configPath();
        Path temporary = Files.createTempFile(path.getParent(), ".config-", ".tmp");
        try {
            configuration.save(temporary.toFile());
            if (!expectedFingerprint.equals(configFingerprint(path))) {
                throw new IllegalStateException("config.yml changed while reload validation was running; retry the command");
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Result rejected(Exception exception) {
        plugin.getLogger().log(Level.WARNING, "Rejected SurvivalTweaks reload", exception);
        String reason = exception.getMessage();
        return Result.failure(reason == null || reason.isBlank()
                ? exception.getClass().getSimpleName()
                : reason);
    }

    private Candidate loadCandidateConfig() throws Exception {
        byte[] source = StrictYaml.read(configPath());
        return new Candidate(parseConfiguration(plugin, source), configFingerprint(source));
    }

    static YamlConfiguration parseConfiguration(JavaPlugin plugin, byte[] source)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(plugin.getResource("config.yml"), "Missing bundled config.yml"),
                StandardCharsets.UTF_8
        ));
        YamlConfiguration candidate = StrictYaml.parse(source, "config.yml");
        candidate.setDefaults(defaults);
        candidate.options().copyDefaults(true);
        return candidate;
    }

    static PluginSettings validateConfiguration(FileConfiguration config, Path dataFolder) {
        ConfigMigrationService.requireCurrent(config);
        StorageConfiguration.load(config, dataFolder);
        PortableExportService.validate(config);
        return PluginSettings.validate(config);
    }

    private Path configPath() {
        return plugin.getDataFolder().toPath().resolve("config.yml");
    }

    private String configFingerprint(Path path) {
        try {
            return configFingerprint(StrictYaml.read(path));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not re-read config.yml before applying reload", exception);
        }
    }

    private String configFingerprint(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Could not fingerprint config.yml", exception);
        }
    }

    public record Result(boolean successful, String reason) {

        private static Result success() {
            return new Result(true, "");
        }

        private static Result failure(String reason) {
            return new Result(false, reason);
        }
    }

    private record Prepared(
            PluginSettings settings,
            MessageService.Prepared messages,
            FeedbackService.Prepared feedback,
            YamlConfiguration configuration,
            String configFingerprint
    ) {
    }

    public record Candidate(YamlConfiguration configuration, String fingerprint) {
    }
}

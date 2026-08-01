package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.ConfigMigrationService;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.storage.StorageConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
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
            Consumer<PluginSettings> afterApply
    ) {
        this(
                plugin, settings, messages, feedback, null,
                (updated, ignored) -> afterApply.accept(updated)
        );
    }

    public ReloadService(
            JavaPlugin plugin,
            SettingsService settings,
            MessageService messages,
            FeedbackService feedback,
            BackupService backups,
            Consumer<PluginSettings> afterApply
    ) {
        this(
                plugin, settings, messages, feedback, backups,
                (updated, ignored) -> afterApply.accept(updated)
        );
    }

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

    public synchronized Result reload() {
        try {
            return apply(prepare());
        } catch (Exception exception) {
            return rejected(exception);
        }
    }

    public boolean reloadAsync(Consumer<Result> completion) {
        Objects.requireNonNull(completion, "completion");
        if (!reloading.compareAndSet(false, true)) {
            return false;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Prepared prepared;
            try {
                prepared = prepare();
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
                    result = apply(prepared);
                } catch (Exception exception) {
                    result = rejected(exception);
                }
                reloading.set(false);
                completion.accept(result);
            });
        });
        return true;
    }

    private Prepared prepare() throws Exception {
        if (backups != null) {
            backups.create("reload");
        }
        Candidate candidate = loadCandidateConfig();
        YamlConfiguration candidateConfig = candidate.configuration();
        ConfigMigrationService.requireCurrent(candidateConfig);
        StorageConfiguration.load(candidateConfig, plugin.getDataFolder().toPath());
        PortableExportService.validate(candidateConfig);
        return new Prepared(
                PluginSettings.validate(candidateConfig),
                messages.prepareReload(),
                feedback.prepare(candidateConfig),
                candidateConfig,
                candidate.fingerprint()
        );
    }

    private Result apply(Prepared prepared) {
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
            plugin.reloadConfig();
        } catch (RuntimeException applyFailure) {
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

    private Result rejected(Exception exception) {
        plugin.getLogger().log(Level.WARNING, "Rejected SurvivalTweaks reload", exception);
        String reason = exception.getMessage();
        return Result.failure(reason == null || reason.isBlank()
                ? exception.getClass().getSimpleName()
                : reason);
    }

    private Candidate loadCandidateConfig() throws Exception {
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(plugin.getResource("config.yml"), "Missing bundled config.yml"),
                StandardCharsets.UTF_8
        ));
        byte[] source = Files.readAllBytes(configPath());
        YamlConfiguration candidate = new YamlConfiguration();
        candidate.loadFromString(new String(source, StandardCharsets.UTF_8));
        candidate.setDefaults(defaults);
        candidate.options().copyDefaults(true);
        return new Candidate(candidate, configFingerprint(source));
    }

    private Path configPath() {
        return plugin.getDataFolder().toPath().resolve("config.yml");
    }

    private String configFingerprint(Path path) {
        try {
            return configFingerprint(Files.readAllBytes(path));
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

    private record Candidate(YamlConfiguration configuration, String fingerprint) {
    }
}

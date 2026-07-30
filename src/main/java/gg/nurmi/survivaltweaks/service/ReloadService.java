package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.ConfigMigrationService;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class ReloadService {

    private final JavaPlugin plugin;
    private final SettingsService settings;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final BackupService backups;
    private final Consumer<PluginSettings> afterApply;
    private final AtomicBoolean reloading = new AtomicBoolean();

    public ReloadService(
            JavaPlugin plugin,
            SettingsService settings,
            MessageService messages,
            FeedbackService feedback,
            Consumer<PluginSettings> afterApply
    ) {
        this(plugin, settings, messages, feedback, null, afterApply);
    }

    public ReloadService(
            JavaPlugin plugin,
            SettingsService settings,
            MessageService messages,
            FeedbackService feedback,
            BackupService backups,
            Consumer<PluginSettings> afterApply
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
        YamlConfiguration candidateConfig = loadCandidateConfig();
        ConfigMigrationService.requireCurrent(candidateConfig);
        return new Prepared(
                PluginSettings.validate(candidateConfig),
                messages.prepareReload(),
                feedback.prepare(candidateConfig)
        );
    }

    private Result apply(Prepared prepared) {
        PluginSettings previousSettings = settings.current();
        MessageService.Prepared previousMessages = messages.snapshot();
        FeedbackService.Prepared previousFeedback = feedback.snapshot();
        try {
            plugin.reloadConfig();
            settings.apply(prepared.settings());
            messages.apply(prepared.messages());
            feedback.apply(prepared.feedback());
            afterApply.accept(prepared.settings());
        } catch (RuntimeException applyFailure) {
            settings.apply(previousSettings);
            messages.apply(previousMessages);
            feedback.apply(previousFeedback);
            try {
                afterApply.accept(previousSettings);
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

    private YamlConfiguration loadCandidateConfig() throws Exception {
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(plugin.getResource("config.yml"), "Missing bundled config.yml"),
                StandardCharsets.UTF_8
        ));
        YamlConfiguration candidate = new YamlConfiguration();
        candidate.load(new File(plugin.getDataFolder(), "config.yml"));
        candidate.setDefaults(defaults);
        candidate.options().copyDefaults(true);
        return candidate;
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
            FeedbackService.Prepared feedback
    ) {
    }
}

package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.logging.Logger;

public final class PerformanceGovernor implements AutoCloseable {

    private final JavaPlugin plugin;
    private final SettingsService settings;
    private final TaskFailureIsolation failures;
    private final Logger logger;
    private volatile Level level = Level.NORMAL;
    private volatile double mspt;
    private int healthySamples;
    private BukkitTask task;

    public PerformanceGovernor(
            JavaPlugin plugin,
            SettingsService settings,
            TaskFailureIsolation failures
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.failures = Objects.requireNonNull(failures, "failures");
        this.logger = plugin.getLogger() == null
                ? Logger.getLogger(PerformanceGovernor.class.getName())
                : plugin.getLogger();
    }

    public void start() {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                failures.guard("performance governor", () ->
                        sample(plugin.getServer().getAverageTickTime())
                ),
                20L,
                20L
        );
    }

    synchronized void sample(double measuredMspt) {
        PluginSettings current = settings.current();
        mspt = Double.isFinite(measuredMspt) ? Math.max(0.0, measuredMspt) : 0.0;
        if (!current.performanceGovernorEnabled()) {
            transition(Level.NORMAL, "governor disabled");
            healthySamples = 0;
            return;
        }

        if (mspt >= current.performanceCriticalMspt()) {
            transition(Level.CRITICAL, "critical threshold exceeded");
            healthySamples = 0;
            return;
        }
        if (mspt >= current.performanceReducedMspt()) {
            if (level == Level.NORMAL) {
                transition(Level.REDUCED, "reduced threshold exceeded");
            }
            healthySamples = 0;
            return;
        }
        if (mspt > current.performanceRecoveryMspt() || level == Level.NORMAL) {
            healthySamples = 0;
            return;
        }

        healthySamples++;
        if (healthySamples >= current.performanceRecoverySeconds()) {
            transition(
                    level == Level.CRITICAL ? Level.REDUCED : Level.NORMAL,
                    "healthy recovery window completed"
            );
            healthySamples = 0;
        }
    }

    private void transition(Level next, String reason) {
        Level previous = level;
        if (previous == next) {
            return;
        }
        level = next;
        String message = "Adaptive performance governor: "
                + previous.name().toLowerCase()
                + " -> " + next.name().toLowerCase()
                + " at " + String.format(java.util.Locale.ROOT, "%.1f", mspt)
                + " MSPT (" + reason + ").";
        if (next.ordinal() > previous.ordinal()) {
            logger.warning(message);
        } else {
            logger.info(message);
        }
    }

    public Level level() {
        return level;
    }

    public int cosmeticDivisor() {
        return switch (level) {
            case NORMAL -> 1;
            case REDUCED -> 2;
            case CRITICAL -> 4;
        };
    }

    public double particleScale() {
        return switch (level) {
            case NORMAL -> 1.0;
            case REDUCED -> 0.6;
            case CRITICAL -> 0.3;
        };
    }

    public double workScale() {
        return switch (level) {
            case NORMAL -> 1.0;
            case REDUCED -> 0.65;
            case CRITICAL -> 0.35;
        };
    }

    public Snapshot snapshot() {
        int recoveryTarget = settings.current().performanceRecoverySeconds();
        int recoveryRemaining = level == Level.NORMAL
                ? 0
                : Math.max(0, recoveryTarget - healthySamples);
        return new Snapshot(level, mspt, healthySamples, recoveryRemaining);
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        level = Level.NORMAL;
        healthySamples = 0;
    }

    public enum Level {
        NORMAL,
        REDUCED,
        CRITICAL
    }

    public record Snapshot(
            Level level,
            double mspt,
            int healthySamples,
            int recoverySecondsRemaining
    ) {
    }
}

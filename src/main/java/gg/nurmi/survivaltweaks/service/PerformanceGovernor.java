package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;

public final class PerformanceGovernor implements AutoCloseable {

    private final JavaPlugin plugin;
    private final SettingsService settings;
    private final TaskFailureIsolation failures;
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
            level = Level.NORMAL;
            healthySamples = 0;
            return;
        }

        if (mspt >= current.performanceCriticalMspt()) {
            level = Level.CRITICAL;
            healthySamples = 0;
            return;
        }
        if (mspt >= current.performanceReducedMspt()) {
            if (level == Level.NORMAL) {
                level = Level.REDUCED;
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
            level = level == Level.CRITICAL ? Level.REDUCED : Level.NORMAL;
            healthySamples = 0;
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
        return new Snapshot(level, mspt, healthySamples);
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

    public record Snapshot(Level level, double mspt, int healthySamples) {
    }
}

package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.SettingsService;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.Locale;

public final class MaintenanceService implements Listener, AutoCloseable {

    private static final Duration FLUSH_TIMEOUT = Duration.ofSeconds(15);
    private static final Set<Long> ANNOUNCEMENT_SECONDS = Set.of(
            3600L, 1800L, 900L, 600L, 300L, 120L, 60L, 30L, 10L, 5L, 4L, 3L, 2L, 1L
    );

    private final JavaPlugin plugin;
    private final SettingsService settings;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final ProfileRepository profiles;
    private final BackupService backups;
    private final Clock clock;
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private volatile boolean maintenanceMode;
    private volatile boolean stopping;
    private volatile Instant restartAt;
    private Duration restartDuration = Duration.ZERO;
    private BukkitTask task;
    private long lastRemaining = Long.MIN_VALUE;

    public MaintenanceService(
            JavaPlugin plugin,
            SettingsService settings,
            MessageService messages,
            FeedbackService feedback,
            ProfileRepository profiles,
            BackupService backups,
            Clock clock
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.feedback = feedback;
        this.profiles = profiles;
        this.backups = backups;
        this.clock = clock;
    }

    public ScheduleResult schedule(Duration delay) {
        if (delay.compareTo(Duration.ofSeconds(10)) < 0
                || delay.compareTo(Duration.ofHours(24)) > 0) {
            return ScheduleResult.INVALID;
        }
        if (restartAt != null || stopping) {
            return ScheduleResult.ALREADY_SCHEDULED;
        }
        restartDuration = delay;
        restartAt = clock.instant().plus(delay);
        lastRemaining = Long.MIN_VALUE;
        ensureTask();
        tick();
        return ScheduleResult.SCHEDULED;
    }

    public boolean cancelRestart() {
        if (restartAt == null || stopping) {
            return false;
        }
        restartAt = null;
        restartDuration = Duration.ZERO;
        lastRemaining = Long.MIN_VALUE;
        hideBossBars();
        cancelTask();
        return true;
    }

    public void maintenanceMode(boolean enabled) {
        maintenanceMode = enabled;
    }

    public Status status() {
        return new Status(
                maintenanceMode,
                restartAt != null,
                stopping,
                remainingSeconds(),
                joinBlocked()
        );
    }

    public static Duration parseDelay(String input) {
        String normalized = input.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9]+[smh]")) {
            return null;
        }
        long amount;
        try {
            amount = Long.parseLong(normalized.substring(0, normalized.length() - 1));
        } catch (NumberFormatException exception) {
            return null;
        }
        try {
            return switch (normalized.charAt(normalized.length() - 1)) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                default -> null;
            };
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onValidateLogin(PlayerConnectionValidateLoginEvent event) {
        if (!event.isAllowed() || !restartJoinBlocked()) {
            return;
        }
        event.kickMessage(messages.component("maintenance.restart-join-blocked"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (restartJoinBlocked()) {
            player.kick(messages.component(player, "maintenance.restart-join-blocked"));
            return;
        }
        if (maintenanceMode
                && !player.hasPermission("survivaltweaks.maintenance.bypass")) {
            player.kick(messages.component(player, "maintenance.join-blocked"));
            return;
        }
        if (restartAt != null && !stopping) {
            showBossBar(player, remainingSeconds());
        }
    }

    private void ensureTask() {
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        }
    }

    private void tick() {
        if (restartAt == null || stopping) {
            return;
        }
        long remaining = remainingSeconds();
        if (remaining <= 0) {
            beginShutdown();
            return;
        }
        plugin.getServer().getOnlinePlayers().forEach(player -> showBossBar(player, remaining));
        bossBars.keySet().removeIf(playerId -> plugin.getServer().getPlayer(playerId) == null);
        if (remaining != lastRemaining && ANNOUNCEMENT_SECONDS.contains(remaining)) {
            plugin.getServer().getOnlinePlayers().forEach(player -> {
                messages.send(
                        player,
                        "maintenance.restart-warning",
                        Placeholder.component("time", durationBefore(player, remaining))
                );
                feedback.play(player, FeedbackService.MAINTENANCE_WARNING);
            });
            Audience console = plugin.getServer().getConsoleSender();
            messages.send(
                    console,
                    "maintenance.restart-warning",
                    Placeholder.component("time", durationBefore(console, remaining))
            );
        }
        lastRemaining = remaining;
    }

    private void showBossBar(Player player, long remaining) {
        float progress = restartDuration.isZero()
                ? 0.0f
                : (float) Math.max(0.0, Math.min(1.0, remaining / (double) restartDuration.toSeconds()));
        Component name = messages.component(
                player,
                "maintenance.restart-bossbar",
                Placeholder.component("time", duration(player, remaining))
        );
        BossBar bar = bossBars.computeIfAbsent(player.getUniqueId(), ignored -> {
            BossBar created = BossBar.bossBar(name, progress, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            player.showBossBar(created);
            return created;
        });
        bar.name(name);
        bar.progress(progress);
    }

    /** A standalone duration, as shown on its own in the boss bar. */
    private Component duration(Audience audience, long seconds) {
        return duration(audience, seconds, "");
    }

    /**
     * A duration that is followed by a preposition or postposition. Finnish puts
     * the counted noun in the genitive there ("5 minuutin kuluttua"), so this
     * cannot reuse the standalone forms.
     */
    private Component durationBefore(Audience audience, long seconds) {
        return duration(audience, seconds, "-before");
    }

    private Component duration(Audience audience, long seconds, String suffix) {
        if (seconds >= 3600 && seconds % 3600 == 0) {
            long hours = seconds / 3600;
            return messages.component(
                    audience,
                    "maintenance.duration." + (hours == 1 ? "hour" : "hours") + suffix,
                    Placeholder.unparsed("count", Long.toString(hours))
            );
        }
        if (seconds >= 60 && seconds % 60 == 0) {
            long minutes = seconds / 60;
            return messages.component(
                    audience,
                    "maintenance.duration." + (minutes == 1 ? "minute" : "minutes") + suffix,
                    Placeholder.unparsed("count", Long.toString(minutes))
            );
        }
        return messages.component(
                audience,
                "maintenance.duration." + (seconds == 1 ? "second" : "seconds") + suffix,
                Placeholder.unparsed("count", Long.toString(seconds))
        );
    }

    private long remainingSeconds() {
        Instant deadline = restartAt;
        if (deadline == null) {
            return -1;
        }
        long millis = Duration.between(clock.instant(), deadline).toMillis();
        return Math.max(0, (millis + 999) / 1000);
    }

    private boolean joinBlocked() {
        return maintenanceMode
                || restartJoinBlocked();
    }

    private boolean restartJoinBlocked() {
        return stopping
                || (restartAt != null
                && remainingSeconds() <= settings.current().maintenanceJoinBlockSeconds());
    }

    private void beginShutdown() {
        stopping = true;
        cancelTask();
        hideBossBars();
        plugin.getServer().getOnlinePlayers().forEach(player ->
                player.kick(messages.component(player, "maintenance.restart-kick")));
        profiles.saveAll();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            profiles.flush(FLUSH_TIMEOUT);
            if (settings.current().maintenanceBackupBeforeRestart()) {
                try {
                    backups.create("restart");
                } catch (IOException exception) {
                    plugin.getLogger().log(
                            Level.SEVERE,
                            "Could not create the pre-restart backup; continuing shutdown",
                            exception
                    );
                }
            }
            plugin.getServer().getScheduler().runTask(plugin, plugin.getServer()::shutdown);
        });
    }

    private void hideBossBars() {
        bossBars.forEach((playerId, bar) -> {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                player.hideBossBar(bar);
            }
        });
        bossBars.clear();
    }

    private void cancelTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @Override
    public void close() {
        cancelTask();
        hideBossBars();
        restartAt = null;
    }

    public enum ScheduleResult {
        SCHEDULED,
        ALREADY_SCHEDULED,
        INVALID
    }

    public record Status(
            boolean maintenanceMode,
            boolean restartScheduled,
            boolean stopping,
            long remainingSeconds,
            boolean joinBlocked
    ) {
    }
}

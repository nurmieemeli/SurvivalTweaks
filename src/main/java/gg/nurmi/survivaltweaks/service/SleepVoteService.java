package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SleepVoteService implements Listener, AutoCloseable {

    private static final Duration ACTION_BAR_LIFETIME = Duration.ofSeconds(2);

    private final JavaPlugin plugin;
    private final SettingsService settings;
    private final MessageService messages;
    private final ActionBarService actionBars;
    private final PlayerExperienceService experience;
    private final PlayerListService playerList;
    private final Map<UUID, Integer> previousPercentages = new HashMap<>();
    private BukkitTask task;

    public SleepVoteService(
            JavaPlugin plugin,
            SettingsService settings,
            MessageService messages,
            ActionBarService actionBars,
            PlayerExperienceService experience,
            PlayerListService playerList
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.actionBars = actionBars;
        this.experience = experience;
        this.playerList = playerList;
    }

    public void start() {
        reconfigure();
    }

    public void reconfigure() {
        cancelTask();
        if (!settings.current().sleepVotingEnabled()) {
            restoreGameRules();
            clearActionBars();
            return;
        }
        plugin.getServer().getWorlds().forEach(this::protectFromVanillaSkip);
        evaluate();
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (settings.current().sleepVotingEnabled()) {
            protectFromVanillaSkip(event.getWorld());
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        Integer previous = previousPercentages.remove(event.getWorld().getUID());
        if (previous != null) {
            event.getWorld().setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, previous);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (settings.current().sleepVotingEnabled() && !event.isCancelled()) {
            ensureEvaluationTask();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedLeave(PlayerBedLeaveEvent event) {
        if (settings.current().sleepVotingEnabled()) {
            ensureEvaluationTask();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (task != null) {
            plugin.getServer().getScheduler().runTask(plugin, this::evaluate);
        }
    }

    private void evaluate() {
        PluginSettings current = settings.current();
        boolean activeVote = false;
        for (World world : plugin.getServer().getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            List<Player> eligible = world.getPlayers().stream()
                    .filter(this::eligible)
                    .filter(player -> !current.sleepExcludeAfk()
                            || !playerList.isAfk(player.getUniqueId()))
                    .toList();
            long sleeping = eligible.stream().filter(Player::isSleeping).count();
            if (eligible.isEmpty() || sleeping == 0) {
                world.getPlayers().forEach(player ->
                        actionBars.clearExact(player, ActionBarService.SLEEP_PRIORITY));
                continue;
            }
            activeVote = true;
            int required = requiredSleepers(
                    eligible.size(),
                    current.sleepRequiredPercentage()
            );
            if (sleeping >= required) {
                skipNight(world, current);
                continue;
            }
            ComponentProgress progress = new ComponentProgress(sleeping, required);
            world.getPlayers().stream()
                    .filter(player -> experience.actionBars(player))
                    .forEach(player -> actionBars.show(
                            player,
                            messages.component(
                                    player,
                                    "sleep.progress",
                                    Placeholder.unparsed("sleeping", Long.toString(progress.sleeping())),
                                    Placeholder.unparsed("required", Integer.toString(progress.required()))
                            ),
                            ActionBarService.SLEEP_PRIORITY,
                            ACTION_BAR_LIFETIME
                    ));
        }
        if (activeVote) {
            ensureEvaluationTask();
        } else {
            cancelTask();
        }
    }

    private boolean eligible(Player player) {
        return !player.isSleepingIgnored()
                && (player.getGameMode() == GameMode.SURVIVAL
                || player.getGameMode() == GameMode.ADVENTURE);
    }

    static int requiredSleepers(int eligible, int percentage) {
        if (eligible < 0 || percentage < 1 || percentage > 100) {
            throw new IllegalArgumentException("Invalid sleep vote inputs");
        }
        return eligible == 0
                ? 0
                : Math.max(1, (int) (((long) eligible * percentage + 99L) / 100L));
    }

    private void skipNight(World world, PluginSettings current) {
        long fullTime = world.getFullTime();
        world.setFullTime(fullTime + (24_000L - Math.floorMod(fullTime, 24_000L)));
        if (current.sleepClearWeather()) {
            world.setStorm(false);
            world.setThundering(false);
            world.setClearWeatherDuration(12_000);
        }
        world.getPlayers().forEach(player -> {
            actionBars.clearExact(player, ActionBarService.SLEEP_PRIORITY);
            messages.send(player, "sleep.skipped");
        });
    }

    private void protectFromVanillaSkip(World world) {
        if (world.getEnvironment() != World.Environment.NORMAL
                || previousPercentages.containsKey(world.getUID())) {
            return;
        }
        Integer previous = world.getGameRuleValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
        previousPercentages.put(world.getUID(), previous == null ? 100 : previous);
        world.setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 100);
    }

    private void restoreGameRules() {
        previousPercentages.forEach((worldId, percentage) -> {
            World world = plugin.getServer().getWorld(worldId);
            if (world != null) {
                world.setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, percentage);
            }
        });
        previousPercentages.clear();
    }

    private void clearActionBars() {
        plugin.getServer().getOnlinePlayers().forEach(player ->
                actionBars.clearExact(player, ActionBarService.SLEEP_PRIORITY));
    }

    private void cancelTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void ensureEvaluationTask() {
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(
                    plugin,
                    this::evaluate,
                    1L,
                    20L
            );
        }
    }

    @Override
    public void close() {
        cancelTask();
        clearActionBars();
        restoreGameRules();
    }

    private record ComponentProgress(long sleeping, int required) {
    }
}

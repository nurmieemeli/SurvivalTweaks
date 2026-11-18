package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import io.papermc.paper.event.world.WorldGameRuleChangeEvent;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;

public final class SleepVoteService implements Listener, AutoCloseable {

    private static final Duration ACTION_BAR_LIFETIME = Duration.ofSeconds(2);
    private static final int VANILLA_SKIP_DISABLED = 101;

    private final JavaPlugin plugin;
    private final SettingsService settings;
    private final MessageService messages;
    private final ActionBarService actionBars;
    private final PlayerExperienceService experience;
    private final PlayerListService playerList;
    private final Map<UUID, SleepVote> attemptedVotes = new HashMap<>();
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
        plugin.getServer().getWorlds().forEach(this::configureLoadedWorld);
        if (settings.current().sleepVotingEnabled()) {
            evaluate();
        }
    }

    public void reconfigure() {
        cancelTask();
        attemptedVotes.clear();
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
        configureLoadedWorld(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        attemptedVotes.remove(event.getWorld().getUID());
        restoreGameRule(event.getWorld(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameRuleChange(WorldGameRuleChangeEvent event) {
        if (settings.current().sleepVotingEnabled()
                || event.getWorld().getEnvironment() != World.Environment.NORMAL
                || !GameRules.PLAYERS_SLEEPING_PERCENTAGE.equals(event.getGameRule())
                || !Integer.toString(VANILLA_SKIP_DISABLED).equals(event.getValue())) {
            return;
        }
        // A deliberate 101 with voting off supersedes the saved override.
        try {
            Files.deleteIfExists(recoveryFile(event.getWorld()));
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not clear the sleep gamerule recovery file for "
                    + event.getWorld().getName(), exception);
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
            Player player = event.getPlayer();
            UUID worldId = event.getBed().getWorld().getUID();
            SleepVote vote = attemptedVotes.get(worldId);
            if (vote != null && vote.sleepers().contains(player.getUniqueId())) {
                attemptedVotes.remove(worldId);
            }
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
        if (!current.sleepVotingEnabled()) {
            return;
        }
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
            Set<UUID> sleepers = eligible.stream().filter(Player::isSleeping)
                    .map(Player::getUniqueId).collect(Collectors.toSet());
            UUID worldId = world.getUID();
            if (sleepers.isEmpty()) {
                attemptedVotes.remove(worldId);
                world.getPlayers().forEach(player ->
                        actionBars.clearExact(player, ActionBarService.SLEEP_PRIORITY));
                continue;
            }
            activeVote = true;
            int required = requiredSleepers(
                    eligible.size(),
                    current.sleepRequiredPercentage()
            );
            if (sleepers.size() >= required) {
                SleepVote vote = new SleepVote(sleepers, required);
                if (!vote.equals(attemptedVotes.put(worldId, vote))) {
                    world.getPlayers().forEach(player -> actionBars.clearExact(player, ActionBarService.SLEEP_PRIORITY));
                    skipNight(world, current);
                }
                continue;
            }
            attemptedVotes.remove(worldId);
            world.getPlayers().stream()
                    .filter(player -> experience.actionBars(player))
                    .forEach(player -> actionBars.show(
                            player,
                            messages.component(
                                    player,
                                    "sleep.progress",
                                    Placeholder.unparsed("sleeping", Integer.toString(sleepers.size())),
                                    Placeholder.unparsed("required", Integer.toString(required))
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
        long dawn = fullTime + (24_000L - Math.floorMod(fullTime, 24_000L));
        world.setFullTime(dawn);
        if (world.getFullTime() < dawn) {
            return;
        }
        if (current.sleepClearWeather()) {
            world.setStorm(false);
            world.setThundering(false);
            world.setClearWeatherDuration(12_000);
        }
        world.getPlayers().forEach(player -> messages.send(player, "sleep.skipped"));
    }

    private void protectFromVanillaSkip(World world) {
        if (world.getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        Integer previous = world.getGameRuleValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
        if (previous == null || previous == VANILLA_SKIP_DISABLED) {
            return;
        }
        Path recoveryFile = recoveryFile(world);
        try {
            Path temporary = Files.createTempFile(recoveryFile.getParent(), ".sleep-vote-", ".tmp");
            try {
                Files.writeString(temporary, previous.toString(), StandardOpenOption.WRITE, StandardOpenOption.SYNC);
                Files.move(temporary, recoveryFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            world.setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, VANILLA_SKIP_DISABLED);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not save the sleep gamerule for " + world.getName(), exception);
        }
    }

    private void configureLoadedWorld(World world) {
        if (settings.current().sleepVotingEnabled()) {
            protectFromVanillaSkip(world);
        } else {
            restoreGameRule(world, true);
        }
    }

    private void restoreGameRule(World world, boolean justLoaded) {
        if (world.getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        Path recoveryFile = recoveryFile(world);
        try {
            String previous = Files.readString(recoveryFile);
            if (Integer.valueOf(VANILLA_SKIP_DISABLED).equals(world.getGameRuleValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE))) {
                world.setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, Integer.parseInt(previous.strip()));
            } else if (justLoaded) {
                // Keep the original until a fresh load confirms Paper saved the restored gamerule.
                Files.deleteIfExists(recoveryFile);
            }
        } catch (NoSuchFileException ignored) {
            // A world can intentionally set its own gamerule to 101.
        } catch (IOException | NumberFormatException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not restore the sleep gamerule for " + world.getName(), exception);
        }
    }

    private Path recoveryFile(World world) {
        return world.getWorldFolder().toPath().resolve(".survivaltweaks-sleep-percentage");
    }

    private void restoreGameRules() {
        plugin.getServer().getWorlds().forEach(world -> restoreGameRule(world, false));
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
        attemptedVotes.clear();
        clearActionBars();
        restoreGameRules();
    }

    private record SleepVote(Set<UUID> sleepers, int required) {
    }
}

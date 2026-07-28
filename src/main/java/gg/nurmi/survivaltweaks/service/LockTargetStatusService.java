package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.command.lock.LockCommand;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.ContainerLock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class LockTargetStatusService implements AutoCloseable {

    private final Server server;
    private final ContainerBlockResolver resolver;
    private final ContainerLockService locks;
    private final MessageService messages;
    private final SettingsService settings;
    private final ActionBarService actionBars;
    private final SafeTeleportService teleports;
    private final PlayerExperienceService experience;
    private final Set<UUID> hinted = new HashSet<>();
    private final Map<UUID, String> ownerNames = new HashMap<>();
    private final Map<UUID, HintState> hintStates = new HashMap<>();
    private final BukkitTask task;

    public LockTargetStatusService(
            JavaPlugin plugin,
            ContainerBlockResolver resolver,
            ContainerLockService locks,
            MessageService messages,
            SettingsService settings,
            ActionBarService actionBars,
            SafeTeleportService teleports,
            PlayerExperienceService experience
    ) {
        this.server = plugin.getServer();
        this.resolver = resolver;
        this.locks = locks;
        this.messages = messages;
        this.settings = settings;
        this.actionBars = actionBars;
        this.teleports = teleports;
        this.experience = experience;
        this.task = server.getScheduler().runTaskTimer(plugin, this::update, 10L, 10L);
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel();
        }
        server.getOnlinePlayers().forEach(this::clear);
        hinted.clear();
        hintStates.clear();
        ownerNames.clear();
    }

    private void update() {
        if (!settings.current().actionBarEnabled()
                || !settings.current().lockTargetHintsEnabled()
                || locks.lockCount() == 0) {
            server.getOnlinePlayers().forEach(this::clear);
            return;
        }

        for (Player player : server.getOnlinePlayers()) {
            if (!experience.actionBars(player)) {
                clear(player);
                continue;
            }
            if (teleports.isPending(player.getUniqueId())) {
                continue;
            }
            Optional<ContainerLock> lock = resolver.target(
                            player,
                            settings.current().lockTargetDistance()
                    )
                    .flatMap(target -> locks.singleLockFor(target.blocks()));
            if (lock.isEmpty()) {
                clear(player);
                continue;
            }

            ContainerLock selected = lock.orElseThrow();
            boolean administrator = player.hasPermission(LockCommand.ADMIN_PERMISSION);
            String key;
            if (selected.canManage(player.getUniqueId(), administrator)) {
                key = "lock.actionbar.owner";
            } else if (!selected.canWithdraw(player.getUniqueId(), administrator)
                    && selected.canAccess(player.getUniqueId(), administrator)) {
                key = "lock.actionbar.deposit-only";
            } else if (selected.canAccess(player.getUniqueId(), administrator)) {
                key = "lock.actionbar.trusted";
            } else {
                key = "lock.actionbar.denied";
            }
            UUID playerId = player.getUniqueId();
            String owner = playerName(selected.ownerId());
            HintState previous = hintStates.get(playerId);
            Component component;
            if (previous != null && previous.key().equals(key) && previous.owner().equals(owner)) {
                component = previous.component();
            } else {
                component = messages.component(
                        player,
                        key,
                        Placeholder.unparsed("owner", owner)
                );
                hintStates.put(playerId, new HintState(key, owner, component));
            }
            actionBars.show(
                    player,
                    component,
                    ActionBarService.LOCK_HINT_PRIORITY,
                    Duration.ofMillis(750)
            );
            hinted.add(playerId);
        }
    }

    private void clear(Player player) {
        UUID playerId = player.getUniqueId();
        hintStates.remove(playerId);
        if (hinted.remove(playerId)) {
            actionBars.clear(player, ActionBarService.LOCK_HINT_PRIORITY);
        }
    }

    private String playerName(UUID uniqueId) {
        return ownerNames.computeIfAbsent(uniqueId, playerId -> {
            OfflinePlayer player = server.getOfflinePlayer(playerId);
            return player.getName() == null ? playerId.toString().substring(0, 8) : player.getName();
        });
    }

    private record HintState(String key, String owner, Component component) {
    }
}

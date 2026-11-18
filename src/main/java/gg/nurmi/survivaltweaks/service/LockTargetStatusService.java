package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.command.lock.LockCommand;
import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.ContainerLock;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class LockTargetStatusService implements AutoCloseable {

    private static final long UNKNOWN_NAME_RETRY_MILLIS = 5_000;
    private static final long KNOWN_NAME_RETRY_MILLIS = 60_000;

    private final Server server;
    private final ContainerBlockResolver resolver;
    private final ContainerLockService locks;
    private final MessageService messages;
    private final SettingsService settings;
    private final ActionBarService actionBars;
    private final SafeTeleportService teleports;
    private final PlayerExperienceService experience;
    private final ProfileRepository profiles;
    private final Set<UUID> hinted = new HashSet<>();
    private final Map<UUID, OwnerName> ownerNames = new HashMap<>();
    private final Clock clock;
    private final BukkitTask task;

    public LockTargetStatusService(
            JavaPlugin plugin,
            ContainerBlockResolver resolver,
            ContainerLockService locks,
            MessageService messages,
            SettingsService settings,
            ActionBarService actionBars,
            SafeTeleportService teleports,
            PlayerExperienceService experience,
            ProfileRepository profiles,
            Clock clock,
            TaskFailureIsolation failures
    ) {
        this.profiles = java.util.Objects.requireNonNull(profiles, "profiles");
        this.server = plugin.getServer();
        this.resolver = resolver;
        this.locks = locks;
        this.messages = messages;
        this.settings = settings;
        this.actionBars = actionBars;
        this.teleports = teleports;
        this.experience = experience;
        this.clock = clock;
        this.task = server.getScheduler().runTaskTimer(
                plugin,
                failures.guard("lock target hints", this::update),
                20L,
                20L
        );
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel();
        }
        server.getOnlinePlayers().forEach(this::clear);
        hinted.clear();
        ownerNames.clear();
    }

    private void update() {
        hinted.removeIf(playerId -> server.getPlayer(playerId) == null);

        PluginSettings current = settings.current();
        if (!current.actionBarEnabled()
                || !current.lockTargetHintsEnabled()
                || locks.lockCount() == 0) {
            server.getOnlinePlayers().forEach(this::clear);
            if (locks.lockCount() == 0) {
                ownerNames.clear();
            }
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
                            current.lockTargetDistance()
                    )
                    .flatMap(target -> locks.singleLockFor(target.blocks()));
            if (lock.isEmpty()) {
                clear(player);
                continue;
            }

            ContainerLock selected = lock.orElseThrow();
            UUID playerId = player.getUniqueId();
            boolean administrator = player.hasPermission(LockCommand.ADMIN_PERMISSION);
            boolean trustedProfile = profiles.isTrusted(selected.ownerId(), playerId);
            boolean canAccess = selected.canAccess(playerId, administrator, trustedProfile);
            boolean canWithdraw = selected.canWithdraw(playerId, administrator, trustedProfile);
            String key;
            if (selected.canManage(playerId, administrator)) {
                key = "lock.actionbar.owner";
            } else if (!canWithdraw && canAccess) {
                key = "lock.actionbar.deposit-only";
            } else if (canAccess) {
                key = "lock.actionbar.trusted";
            } else {
                key = "lock.actionbar.denied";
            }
            String owner = playerName(selected.ownerId());
            actionBars.show(
                    player,
                    messages.component(player, key, Placeholder.unparsed("owner", owner)),
                    ActionBarService.LOCK_HINT_PRIORITY,
                    Duration.ofMillis(1_250)
            );
            hinted.add(playerId);
        }
    }

    private void clear(Player player) {
        UUID playerId = player.getUniqueId();
        if (hinted.remove(playerId)) {
            actionBars.clear(player, ActionBarService.LOCK_HINT_PRIORITY);
        }
    }

    private String playerName(UUID uniqueId) {
        OwnerName cached = ownerNames.get(uniqueId);
        long now = clock.millis();
        Player online = server.getPlayer(uniqueId);
        if (online != null) {
            String name = online.getName();
            if (cached == null || !name.equals(cached.name()) || now >= cached.retryAtMillis()) {
                ownerNames.put(uniqueId, new OwnerName(name, now + KNOWN_NAME_RETRY_MILLIS));
            }
            return name;
        }
        if (cached == null || now >= cached.retryAtMillis()) {
            String name = server.getOfflinePlayer(uniqueId).getName();
            long retry = name == null ? UNKNOWN_NAME_RETRY_MILLIS : KNOWN_NAME_RETRY_MILLIS;
            cached = new OwnerName(name, now + retry);
            ownerNames.put(uniqueId, cached);
        }
        return PlayerNameLookup.displayName(uniqueId, cached.name());
    }

    private record OwnerName(String name, long retryAtMillis) {
    }
}

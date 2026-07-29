package gg.nurmi.survivaltweaks.command.lock;

import gg.nurmi.survivaltweaks.object.ContainerLock;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.service.ContainerBlockResolver;
import gg.nurmi.survivaltweaks.service.ContainerLockService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.OnboardingService;
import gg.nurmi.survivaltweaks.object.OnboardingHint;
import gg.nurmi.survivaltweaks.object.NotificationType;
import gg.nurmi.survivaltweaks.service.NotificationService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class LockCommand implements CommandExecutor, TabCompleter {

    public static final String ADMIN_PERMISSION = "survivaltweaks.lock.admin";

    private static final List<String> SUBCOMMANDS = List.of("trust", "untrust", "transfer", "info", "nearby");

    private final Server server;
    private final ContainerBlockResolver resolver;
    private final ContainerLockService locks;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final SettingsService settings;
    private final OnboardingService onboarding;
    private final NotificationService notifications;

    public LockCommand(
            Server server,
            ContainerBlockResolver resolver,
            ContainerLockService locks,
            MessageService messages,
            FeedbackService feedback,
            SettingsService settings,
            OnboardingService onboarding,
            NotificationService notifications
    ) {
        this.server = server;
        this.resolver = resolver;
        this.locks = locks;
        this.messages = messages;
        this.feedback = feedback;
        this.settings = settings;
        this.onboarding = onboarding;
        this.notifications = notifications;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments
    ) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return true;
        }

        if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("nearby")) {
            if (arguments.length > 2) {
                messages.send(player, "lock.usage");
                return true;
            }
            int radius = settings.current().lockTargetDistance();
            if (arguments.length == 2) {
                try {
                    radius = Integer.parseInt(arguments[1]);
                } catch (NumberFormatException exception) {
                    messages.send(player, "lock.nearby-radius");
                    return true;
                }
            }
            lockNearby(player, radius);
            return true;
        }

        Optional<ContainerBlockResolver.Target> target = resolver.target(
                player,
                settings.current().lockTargetDistance()
        );
        if (target.isEmpty()) {
            messages.send(player, "lock.not-container");
            return true;
        }
        if (arguments.length == 0) {
            create(player, target.orElseThrow());
            return true;
        }

        String action = arguments[0].toLowerCase(Locale.ROOT);
        if (action.equals("info") && arguments.length == 1) {
            showInfo(player, target.orElseThrow());
            return true;
        }
        if ((action.equals("trust") || action.equals("untrust")) && arguments.length == 2) {
            updateTrust(player, target.orElseThrow(), action, arguments[1]);
            return true;
        }
        if (action.equals("transfer") && arguments.length == 2) {
            transferLock(player, target.orElseThrow(), arguments[1]);
            return true;
        }

        messages.send(player, "lock.usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments
    ) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }

        if (arguments.length == 1) {
            return matching(SUBCOMMANDS, arguments[0]);
        }
        if (arguments.length != 2) {
            return List.of();
        }

        String action = arguments[0].toLowerCase(Locale.ROOT);
        if (action.equals("trust") || action.equals("transfer")) {
            return matching(
                    server.getOnlinePlayers().stream()
                            .filter(candidate -> !candidate.getUniqueId().equals(player.getUniqueId()))
                            .map(Player::getName)
                            .toList(),
                    arguments[1]
            );
        }
        if (action.equals("untrust")) {
            return targetLock(player).map(lock -> matching(
                    lock.trustedPlayers().stream().map(this::playerName).toList(),
                    arguments[1]
            )).orElseGet(List::of);
        }
        return List.of();
    }

    private void create(Player player, ContainerBlockResolver.Target target) {
        if (!locks.locksFor(target.blocks()).isEmpty()) {
            messages.send(player, "lock.already-locked");
            return;
        }
        if (!player.hasPermission(ADMIN_PERMISSION)
                && locks.lockCount(player.getUniqueId()) >= settings.current().maxLocksPerPlayer()) {
            int maxLocksPerPlayer = settings.current().maxLocksPerPlayer();
            messages.send(
                    player,
                    "lock.limit",
                    Placeholder.unparsed("limit", Integer.toString(maxLocksPerPlayer))
            );
            return;
        }

        if (locks.create(player.getUniqueId(), target.blocks()).isPresent()) {
            messages.send(player, "lock.created");
            feedback.play(player, FeedbackService.LOCK_CREATED);
            onboarding.show(player, OnboardingHint.LOCK_CONTROL);
        } else {
            messages.send(player, "lock.conflict");
        }
    }

    private void lockNearby(Player player, int requestedRadius) {
        if (requestedRadius < 1 || requestedRadius > 8) {
            messages.send(player, "lock.nearby-radius");
            return;
        }
        int available = player.hasPermission(ADMIN_PERMISSION)
                ? Integer.MAX_VALUE
                : settings.current().maxLocksPerPlayer() - (int) locks.lockCount(player.getUniqueId());
        if (available <= 0) {
            messages.send(
                    player,
                    "lock.limit",
                    Placeholder.unparsed(
                            "limit",
                            Integer.toString(settings.current().maxLocksPerPlayer())
                    )
            );
            return;
        }
        int created = 0;
        java.util.Set<gg.nurmi.survivaltweaks.object.BlockKey> visited = new java.util.HashSet<>();
        org.bukkit.Location origin = player.getLocation();
        outer:
        for (int x = -requestedRadius; x <= requestedRadius; x++) {
            for (int y = -requestedRadius; y <= requestedRadius; y++) {
                for (int z = -requestedRadius; z <= requestedRadius; z++) {
                    org.bukkit.block.Block block = origin.getWorld().getBlockAt(
                            origin.getBlockX() + x,
                            origin.getBlockY() + y,
                            origin.getBlockZ() + z
                    );
                    if (!isPotentialContainer(block.getType())) {
                        continue;
                    }
                    Set<gg.nurmi.survivaltweaks.object.BlockKey> blocks = resolver.blocksFor(block);
                    if (blocks.isEmpty() || blocks.stream().anyMatch(visited::contains)) {
                        continue;
                    }
                    visited.addAll(blocks);
                    if (locks.locksFor(blocks).isEmpty()
                            && locks.create(player.getUniqueId(), blocks).isPresent()) {
                        created++;
                        if (created >= available) {
                            break outer;
                        }
                    }
                }
            }
        }
        messages.send(
                player,
                "lock.nearby-created",
                Placeholder.unparsed("count", Integer.toString(created))
        );
        if (created > 0) {
            feedback.play(player, FeedbackService.LOCK_CREATED, 1.4);
            onboarding.show(player, OnboardingHint.LOCK_CONTROL);
        }
    }

    private void updateTrust(
            Player player,
            ContainerBlockResolver.Target target,
            String action,
            String playerName
    ) {
        Optional<ContainerLock> selected = managedLock(player, target);
        if (selected.isEmpty()) {
            return;
        }

        ContainerLock lock = selected.orElseThrow();
        if (action.equals("untrust")) {
            Optional<UUID> trustedId = lock.trustedPlayers().stream()
                    .filter(uniqueId -> playerName(uniqueId).equalsIgnoreCase(playerName))
                    .findFirst();
            if (trustedId.isEmpty()) {
                messages.send(
                        player,
                        "lock.not-trusted",
                        Placeholder.unparsed("player", playerName)
                );
                return;
            }

            String resolvedName = playerName(trustedId.orElseThrow());
            locks.untrust(lock, trustedId.orElseThrow());
            notifyAdministrativeChange(player, lock, "untrusted:" + resolvedName);
            messages.send(
                    player,
                    "lock.untrusted",
                    Placeholder.unparsed("player", resolvedName)
            );
            feedback.play(player, FeedbackService.LOCK_ACCESS_CHANGED);
            return;
        }

        Player trustedPlayer = server.getPlayer(playerName);
        if (trustedPlayer == null) {
            messages.send(player, "lock.player-not-found", Placeholder.unparsed("player", playerName));
            return;
        }
        if (trustedPlayer.getUniqueId().equals(lock.ownerId())) {
            messages.send(player, "lock.self-trust");
            return;
        }

        boolean changed = locks.trust(lock, trustedPlayer.getUniqueId());
        String message = changed ? "lock.trusted" : "lock.already-trusted";
        messages.send(player, message, Placeholder.unparsed("player", trustedPlayer.getName()));
        if (changed) {
            notifyAdministrativeChange(player, lock, "trusted:" + trustedPlayer.getName());
            feedback.play(player, FeedbackService.LOCK_ACCESS_CHANGED);
        }
    }

    private void transferLock(Player player, ContainerBlockResolver.Target target, String newOwnerName) {
        Optional<ContainerLock> selected = managedLock(player, target);
        if (selected.isEmpty()) {
            return;
        }

        ContainerLock lock = selected.orElseThrow();
        Player newOwner = server.getPlayer(newOwnerName);
        if (newOwner == null) {
            messages.send(player, "lock.player-not-found", Placeholder.unparsed("player", newOwnerName));
            return;
        }
        if (newOwner.getUniqueId().equals(lock.ownerId())) {
            messages.send(player, "lock.already-owner");
            return;
        }

        if (locks.transfer(lock, newOwner.getUniqueId())) {
            notifyAdministrativeChange(player, lock, "transferred:" + newOwner.getName());
            messages.send(player, "lock.transferred", Placeholder.unparsed("player", newOwner.getName()));
            messages.send(newOwner, "lock.transfer-received", Placeholder.unparsed("player", player.getName()));
            feedback.play(player, FeedbackService.LOCK_ACCESS_CHANGED);
            feedback.play(newOwner, FeedbackService.LOCK_ACCESS_CHANGED);
        }
    }

    private void showInfo(Player player, ContainerBlockResolver.Target target) {
        Optional<ContainerLock> selected = singleLock(player, target);
        if (selected.isEmpty()) {
            return;
        }

        ContainerLock lock = selected.orElseThrow();
        Component trusted = lock.trustedPlayers().isEmpty()
                ? messages.component(player, "lock.nobody")
                : Component.text(String.join(
                        ", ",
                        lock.trustedPlayers().stream().map(this::playerName).sorted().toList()
                ));
        messages.send(
                player,
                "lock.info",
                Placeholder.unparsed("owner", playerName(lock.ownerId())),
                Placeholder.component("trusted", trusted)
        );
    }

    private Optional<ContainerLock> managedLock(Player player, ContainerBlockResolver.Target target) {
        Optional<ContainerLock> lock = singleLock(player, target);
        if (lock.isPresent() && !lock.orElseThrow().canManage(
                player.getUniqueId(),
                player.hasPermission(ADMIN_PERMISSION)
        )) {
            messages.send(player, "lock.not-owner");
            return Optional.empty();
        }
        return lock;
    }

    private Optional<ContainerLock> singleLock(Player player, ContainerBlockResolver.Target target) {
        Set<ContainerLock> found = locks.locksFor(target.blocks());
        if (found.isEmpty()) {
            messages.send(player, "lock.not-locked");
            return Optional.empty();
        }
        if (found.size() > 1) {
            messages.send(player, "lock.conflict");
            return Optional.empty();
        }
        return found.stream().findFirst();
    }

    private Optional<ContainerLock> targetLock(Player player) {
        return resolver.target(player, settings.current().lockTargetDistance())
                .flatMap(target -> locks.locksFor(target.blocks()).stream().findFirst());
    }

    private String playerName(UUID uniqueId) {
        OfflinePlayer player = server.getOfflinePlayer(uniqueId);
        return player.getName() == null ? uniqueId.toString().substring(0, 8) : player.getName();
    }

    private void notifyAdministrativeChange(Player actor, ContainerLock lock, String detail) {
        if (!actor.getUniqueId().equals(lock.ownerId())) {
            notifications.notify(
                    lock.ownerId(),
                    NotificationType.LOCK_ADMIN_CHANGED,
                    actor.getName(),
                    detail
            );
        }
    }

    private List<String> matching(List<String> values, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted()
                .toList();
    }

    private static boolean isPotentialContainer(org.bukkit.Material type) {
        if (type == null || !type.isBlock() || type.isAir()) {
            return false;
        }
        String name = type.name();
        return name.endsWith("_CHEST") || name.endsWith("_BARREL") || name.endsWith("_SHULKER_BOX")
                || type == org.bukkit.Material.HOPPER || type == org.bukkit.Material.DISPENSER || type == org.bukkit.Material.DROPPER
                || type == org.bukkit.Material.FURNACE || type == org.bukkit.Material.BLAST_FURNACE || type == org.bukkit.Material.SMOKER
                || type == org.bukkit.Material.BREWING_STAND || type == org.bukkit.Material.JUKEBOX
                || name.contains("CHEST") || name.contains("BARREL") || name.contains("SHULKER") || name.contains("CRAFTER");
    }
}

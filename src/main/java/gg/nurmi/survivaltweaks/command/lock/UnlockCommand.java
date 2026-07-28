package gg.nurmi.survivaltweaks.command.lock;

import gg.nurmi.survivaltweaks.object.ContainerLock;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.service.ContainerBlockResolver;
import gg.nurmi.survivaltweaks.service.ContainerLockService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.NotificationService;
import gg.nurmi.survivaltweaks.object.NotificationType;
import gg.nurmi.survivaltweaks.ui.ConfirmationDialogService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.Set;

public final class UnlockCommand implements CommandExecutor {

    private final ContainerBlockResolver resolver;
    private final ContainerLockService locks;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final ConfirmationDialogService dialogs;
    private final SettingsService settings;
    private final NotificationService notifications;

    public UnlockCommand(
            ContainerBlockResolver resolver,
            ContainerLockService locks,
            MessageService messages,
            FeedbackService feedback,
            ConfirmationDialogService dialogs,
            SettingsService settings,
            NotificationService notifications
    ) {
        this.resolver = resolver;
        this.locks = locks;
        this.messages = messages;
        this.feedback = feedback;
        this.dialogs = dialogs;
        this.settings = settings;
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
        if (arguments.length > 1
                || (arguments.length == 1 && !arguments[0].equalsIgnoreCase("confirm"))) {
            messages.send(player, "lock.unlock-usage");
            return true;
        }

        Optional<ContainerLock> selected = targetLock(player);
        if (selected.isEmpty()) {
            return true;
        }
        ContainerLock lock = selected.orElseThrow();
        if (arguments.length == 1) {
            confirmRemoval(player, lock);
        } else if (!dialogs.showUnlock(player, confirmed -> confirmRemoval(confirmed, lock))) {
            messages.send(player, "lock.confirm-command");
        }
        return true;
    }

    private Optional<ContainerLock> targetLock(Player player) {
        Optional<ContainerBlockResolver.Target> target = resolver.target(
                player,
                settings.current().lockTargetDistance()
        );
        if (target.isEmpty()) {
            messages.send(player, "lock.not-container");
            return Optional.empty();
        }

        Set<ContainerLock> found = locks.locksFor(target.orElseThrow().blocks());
        if (found.isEmpty()) {
            messages.send(player, "lock.not-locked");
            return Optional.empty();
        }
        if (found.size() > 1) {
            messages.send(player, "lock.conflict");
            return Optional.empty();
        }

        ContainerLock lock = found.iterator().next();
        if (!lock.canManage(player.getUniqueId(), player.hasPermission(LockCommand.ADMIN_PERMISSION))) {
            messages.send(player, "lock.not-owner");
            feedback.play(player, FeedbackService.LOCK_DENIED);
            return Optional.empty();
        }
        return Optional.of(lock);
    }

    private void confirmRemoval(Player player, ContainerLock lock) {
        if (!locks.contains(lock)) {
            messages.send(player, "lock.not-locked");
            return;
        }
        if (!lock.canManage(player.getUniqueId(), player.hasPermission(LockCommand.ADMIN_PERMISSION))) {
            messages.send(player, "lock.not-owner");
            feedback.play(player, FeedbackService.LOCK_DENIED);
            return;
        }

        if (!player.getUniqueId().equals(lock.ownerId())) {
            notifications.notify(
                    lock.ownerId(),
                    NotificationType.LOCK_ADMIN_CHANGED,
                    player.getName(),
                    "removed"
            );
        }
        locks.remove(lock);
        messages.send(player, "lock.unlocked");
        feedback.play(player, FeedbackService.LOCK_REMOVED);
    }
}

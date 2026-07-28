package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.PlayerExperienceService;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class ConfirmationDialogService {

    private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(2);

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final SettingsService settings;
    private final PlayerExperienceService experience;

    public ConfirmationDialogService(
            JavaPlugin plugin,
            MessageService messages,
            FeedbackService feedback,
            SettingsService settings
    ) {
        this(plugin, messages, feedback, settings, null);
    }

    public ConfirmationDialogService(
            JavaPlugin plugin,
            MessageService messages,
            FeedbackService feedback,
            SettingsService settings,
            PlayerExperienceService experience
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.feedback = feedback;
        this.settings = settings;
        this.experience = experience;
    }

    public boolean showUnlock(Player player, Consumer<Player> confirm) {
        return show(
                player,
                messages.component(player, "ui.unlock-dialog.title"),
                messages.component(player, "ui.unlock-dialog.body"),
                messages.component(player, "ui.unlock-dialog.confirm"),
                messages.component(player, "ui.unlock-dialog.confirm-tooltip"),
                messages.component(player, "ui.unlock-dialog.cancel"),
                confirmed -> confirm.accept(confirmed),
                ignored -> {
                }
        );
    }

    public boolean showTeleportRequest(
            Player recipient,
            String senderName,
            Consumer<Player> accept,
            Consumer<Player> decline
    ) {
        return show(
                recipient,
                messages.component(
                        recipient,
                        "ui.teleport-dialog.title",
                        Placeholder.unparsed("player", senderName)
                ),
                messages.component(
                        recipient,
                        "ui.teleport-dialog.body",
                        Placeholder.unparsed("player", senderName)
                ),
                messages.component(recipient, "ui.teleport-dialog.accept"),
                messages.component(recipient, "ui.teleport-dialog.accept-tooltip"),
                messages.component(recipient, "ui.teleport-dialog.decline"),
                accept,
                decline
        );
    }

    public boolean showOwnershipTransfer(
            Player player,
            String newOwner,
            Consumer<Player> confirm
    ) {
        return show(
                player,
                messages.component(player, "ui.lock-transfer-dialog.title"),
                messages.component(
                        player,
                        "ui.lock-transfer-dialog.body",
                        Placeholder.unparsed("player", newOwner)
                ),
                messages.component(player, "ui.lock-transfer-dialog.confirm"),
                messages.component(player, "ui.lock-transfer-dialog.confirm-tooltip"),
                messages.component(player, "ui.lock-transfer-dialog.cancel"),
                confirm,
                ignored -> {
                }
        );
    }

    public boolean showHomeDelete(Player player, String home, Consumer<Player> confirm) {
        return show(
                player,
                messages.component(player, "ui.home-delete-dialog.title"),
                messages.component(
                        player,
                        "ui.home-delete-dialog.body",
                        Placeholder.unparsed("home", home)
                ),
                messages.component(player, "ui.home-delete-dialog.confirm"),
                messages.component(player, "ui.home-delete-dialog.confirm-tooltip"),
                messages.component(player, "ui.home-delete-dialog.cancel"),
                confirm,
                ignored -> {
                }
        );
    }

    private boolean show(
            Player player,
            Component title,
            Component body,
            Component confirmLabel,
            Component confirmTooltip,
            Component cancelLabel,
            Consumer<Player> confirm,
            Consumer<Player> cancel
    ) {
        if (!settings.current().dialogsEnabled()
                || (experience != null && !experience.dialogs(player))) {
            return false;
        }

        try {
            ActionButton confirmButton = button(confirmLabel, confirmTooltip, player, confirm);
            ActionButton cancelButton = button(
                    cancelLabel,
                    messages.component(player, "ui.dialog-cancel-tooltip"),
                    player,
                    cancel
            );
            Dialog dialog = Dialog.create(builder -> builder.empty()
                    .base(DialogBase.builder(title)
                            .canCloseWithEscape(true)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(List.of(DialogBody.plainMessage(body, 320)))
                            .build())
                    .type(DialogType.confirmation(confirmButton, cancelButton)));
            player.showDialog(dialog);
            feedback.play(player, FeedbackService.UI_OPEN);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not show a confirmation dialog", exception);
            return false;
        }
    }

    private ActionButton button(
            Component label,
            Component tooltip,
            Player intendedPlayer,
            Consumer<Player> action
    ) {
        DialogAction dialogAction = DialogAction.customClick(
                (response, audience) -> {
                    if (!(audience instanceof Player player)
                            || !player.getUniqueId().equals(intendedPlayer.getUniqueId())) {
                        return;
                    }
                    runOnMainThread(() -> {
                        if (player.isOnline()) {
                            feedback.play(player, FeedbackService.UI_CLICK);
                            action.accept(player);
                        }
                    });
                },
                ClickCallback.Options.builder()
                        .uses(1)
                        .lifetime(CALLBACK_LIFETIME)
                        .build()
        );
        return ActionButton.builder(label)
                .tooltip(tooltip)
                .width(150)
                .action(dialogAction)
                .build();
    }

    private void runOnMainThread(Runnable action) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, action);
        }
    }
}

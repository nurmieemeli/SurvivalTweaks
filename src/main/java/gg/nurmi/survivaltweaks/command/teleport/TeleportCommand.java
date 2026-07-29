package gg.nurmi.survivaltweaks.command.teleport;

import gg.nurmi.survivaltweaks.object.TeleportRequest;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.SafeTeleportService;
import gg.nurmi.survivaltweaks.service.TeleportRequestService;
import gg.nurmi.survivaltweaks.service.OnboardingService;
import gg.nurmi.survivaltweaks.object.OnboardingHint;
import gg.nurmi.survivaltweaks.ui.ConfirmationDialogService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

public final class TeleportCommand implements CommandExecutor, TabCompleter {

    public static final String BYPASS_PERMISSION = "survivaltweaks.teleport.bypass";

    private final Server server;
    private final TeleportRequestService requests;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final SafeTeleportService safeTeleports;
    private final ConfirmationDialogService dialogs;
    private final TeleportAcceptCommand acceptCommand;
    private final Clock clock;
    private final SettingsService settings;
    private final OnboardingService onboarding;

    public TeleportCommand(
            Server server,
            TeleportRequestService requests,
            MessageService messages,
            FeedbackService feedback,
            SafeTeleportService safeTeleports,
            ConfirmationDialogService dialogs,
            TeleportAcceptCommand acceptCommand,
            Clock clock,
            SettingsService settings,
            OnboardingService onboarding
    ) {
        this.server = server;
        this.requests = requests;
        this.messages = messages;
        this.feedback = feedback;
        this.safeTeleports = safeTeleports;
        this.dialogs = dialogs;
        this.acceptCommand = acceptCommand;
        this.clock = clock;
        this.settings = settings;
        this.onboarding = onboarding;
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
        if (arguments.length != 1) {
            messages.send(player, "teleport.usage");
            return true;
        }

        Player target = server.getPlayer(arguments[0]);
        if (target == null) {
            messages.send(player, "teleport.player-not-found", Placeholder.unparsed("player", arguments[0]));
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            messages.send(player, "teleport.self");
            return true;
        }

        if (!safeTeleports.ensureAvailable(player)) {
            return true;
        }

        if (player.hasPermission(BYPASS_PERMISSION)) {
            safeTeleports.begin(
                    player,
                    () -> {
                        Player currentTarget = server.getPlayer(target.getUniqueId());
                        return currentTarget == null ? null : currentTarget.getLocation();
                    },
                    messages.component(
                            player,
                            "teleport.completed",
                            Placeholder.component("player", messages.formatPlayerName(target, settings))
                    ),
                    messages.component(
                            player,
                            "teleport.destination.player",
                            Placeholder.component("player", messages.formatPlayerName(target, settings))
                    ),
                    FeedbackService.TELEPORT_COMPLETE
            );
            return true;
        }

        if (requests.hasOutgoingRequest(player.getUniqueId(), clock.instant())) {
            messages.send(player, "teleport.cooldown");
            return true;
        }

        var requestLifetime = settings.current().teleportRequestLifetime();
        var request = requests.create(
                player.getUniqueId(),
                target.getUniqueId(),
                clock.instant(),
                requestLifetime
        ).orElseThrow();
        messages.send(player, "teleport.sent", Placeholder.component("player", messages.formatPlayerName(target, settings)));
        messages.send(
                target,
                MessageService.plural("teleport.received", requestLifetime.toSeconds()),
                Placeholder.component("player", messages.formatPlayerName(player, settings)),
                Placeholder.unparsed("seconds", Long.toString(requestLifetime.toSeconds()))
        );
        feedback.play(player, FeedbackService.UI_CLICK);
        feedback.play(target, FeedbackService.TELEPORT_REQUEST);
        onboarding.show(target, OnboardingHint.TELEPORT_INBOX);
        dialogs.showTeleportRequest(
                target,
                player.getName(),
                recipient -> acceptCommand.acceptRequest(recipient, request),
                recipient -> acceptCommand.declineRequest(recipient, request)
        );
        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments
    ) {
        if (!(sender instanceof Player player) || arguments.length != 1) {
            return List.of();
        }
        String prefix = arguments[0].toLowerCase(Locale.ROOT);
        return server.getOnlinePlayers().stream()
                .filter(candidate -> !candidate.getUniqueId().equals(player.getUniqueId()))
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted()
                .toList();
    }

}

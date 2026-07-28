package gg.nurmi.survivaltweaks.command.teleport;

import gg.nurmi.survivaltweaks.object.TeleportRequest;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.SafeTeleportService;
import gg.nurmi.survivaltweaks.service.TeleportRequestService;
import gg.nurmi.survivaltweaks.service.NotificationService;
import gg.nurmi.survivaltweaks.object.NotificationType;
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
import java.util.Optional;
import gg.nurmi.survivaltweaks.ui.TeleportInboxController;

public final class TeleportAcceptCommand implements CommandExecutor, TabCompleter {

    private final Server server;
    private final TeleportRequestService requests;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final SafeTeleportService safeTeleports;
    private final Clock clock;
    private final NotificationService notifications;
    private TeleportInboxController inbox;

    public TeleportAcceptCommand(
            Server server,
            TeleportRequestService requests,
            MessageService messages,
            FeedbackService feedback,
            SafeTeleportService safeTeleports,
            Clock clock,
            NotificationService notifications
    ) {
        this.server = server;
        this.requests = requests;
        this.messages = messages;
        this.feedback = feedback;
        this.safeTeleports = safeTeleports;
        this.clock = clock;
        this.notifications = notifications;
    }

    public void inbox(TeleportInboxController inbox) {
        this.inbox = inbox;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments
    ) {
        if (!(sender instanceof Player recipient)) {
            messages.send(sender, "player-only");
            return true;
        }
        if (arguments.length > 1) {
            messages.send(recipient, "teleport.accept.usage");
            return true;
        }

        List<TeleportRequest> incoming = requests.incomingRequests(recipient.getUniqueId(), clock.instant());
        if (incoming.isEmpty()) {
            messages.send(recipient, "teleport.accept.none");
            return true;
        }

        Optional<TeleportRequest> selected;
        if (arguments.length == 1) {
            selected = incoming.stream().filter(request -> senderName(request)
                    .map(name -> name.equalsIgnoreCase(arguments[0]))
                    .orElse(false)).findFirst();
        } else if (incoming.size() == 1) {
            selected = Optional.of(incoming.getFirst());
        } else {
            if (inbox != null) {
                inbox.open(recipient);
            } else {
                String names = String.join(", ", incoming.stream()
                        .map(this::senderName)
                        .flatMap(Optional::stream)
                        .sorted()
                        .toList());
                messages.send(recipient, "teleport.accept.choose", Placeholder.unparsed("players", names));
            }
            return true;
        }

        if (selected.isEmpty()) {
            String requestedName = arguments.length == 1 ? arguments[0] : "";
            messages.send(
                    recipient,
                    "teleport.accept.not-found",
                    Placeholder.unparsed("player", requestedName)
            );
            return true;
        }

        acceptRequest(recipient, selected.orElseThrow());
        return true;
    }

    public void acceptRequest(Player recipient, TeleportRequest request) {
        if (!request.recipientId().equals(recipient.getUniqueId())
                || !requests.incomingRequests(recipient.getUniqueId(), clock.instant()).contains(request)) {
            messages.send(recipient, "teleport.accept.none");
            return;
        }

        Player requestingPlayer = server.getPlayer(request.senderId());
        if (requestingPlayer == null) {
            requests.consume(request);
            messages.send(recipient, "teleport.accept.sender-offline");
            feedback.play(recipient, FeedbackService.TELEPORT_CANCELLED);
            return;
        }

        boolean started = safeTeleports.begin(
                requestingPlayer,
                () -> {
                    Player currentRecipient = server.getPlayer(request.recipientId());
                    return currentRecipient == null ? null : currentRecipient.getLocation();
                },
                messages.component(
                        requestingPlayer,
                        "teleport.completed",
                        Placeholder.unparsed("player", recipient.getName())
                ),
                messages.component(
                        requestingPlayer,
                        "teleport.destination.player",
                        Placeholder.unparsed("player", recipient.getName())
                ),
                FeedbackService.TELEPORT_COMPLETE
        );
        if (!started) {
            messages.send(recipient, "teleport.accept.sender-unavailable");
            feedback.play(recipient, FeedbackService.TELEPORT_CANCELLED);
            return;
        }

        requests.consume(request);
        messages.send(
                recipient,
                "teleport.accept.accepted",
                Placeholder.unparsed("player", requestingPlayer.getName())
        );
        feedback.play(recipient, FeedbackService.UI_CLICK);
    }

    public void declineRequest(Player recipient, TeleportRequest request) {
        if (!request.recipientId().equals(recipient.getUniqueId()) || !requests.consume(request)) {
            messages.send(recipient, "teleport.accept.none");
            return;
        }
        String senderName = senderName(request).orElse(request.senderId().toString().substring(0, 8));
        messages.send(
                recipient,
                "teleport.accept.declined",
                Placeholder.unparsed("player", senderName)
        );
        Player sender = server.getPlayer(request.senderId());
        notifications.notify(
                request.senderId(),
                NotificationType.TELEPORT_DECLINED,
                recipient.getName(),
                ""
        );
        if (sender != null) {
            messages.send(
                    sender,
                    "teleport.declined",
                    Placeholder.unparsed("player", recipient.getName())
            );
            feedback.play(sender, FeedbackService.TELEPORT_CANCELLED);
        }
        feedback.play(recipient, FeedbackService.UI_CLICK);
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
        return requests.incomingRequests(player.getUniqueId(), clock.instant()).stream()
                .map(this::senderName)
                .flatMap(Optional::stream)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted()
                .toList();
    }

    private Optional<String> senderName(TeleportRequest request) {
        return Optional.ofNullable(server.getPlayer(request.senderId())).map(Player::getName);
    }
}

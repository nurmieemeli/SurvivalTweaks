package gg.nurmi.survivaltweaks.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SessionSummaryService {

    private static final long JOIN_DELAY_TICKS = 40L;
    private static final Component SEPARATOR =
            Component.text(" \u2022 ", NamedTextColor.DARK_GRAY);

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final NotificationService notifications;
    private final TeleportRequestService requests;
    private final DeathRecoveryService deathRecovery;
    private final MaintenanceService maintenance;
    private final Clock clock;

    public SessionSummaryService(
            JavaPlugin plugin,
            MessageService messages,
            NotificationService notifications,
            TeleportRequestService requests,
            DeathRecoveryService deathRecovery,
            MaintenanceService maintenance,
            Clock clock
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.deathRecovery = Objects.requireNonNull(deathRecovery, "deathRecovery");
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void playerJoined(Player player) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            Component summary = summary(player);
            if (summary != null) {
                player.sendMessage(summary);
            }
        }, JOIN_DELAY_TICKS);
    }

    Component summary(Player player) {
        long unread = notifications.unread(player.getUniqueId());
        int incoming = requests.incomingRequests(player.getUniqueId(), clock.instant()).size();
        List<Component> items = new ArrayList<>();
        if (unread > 0) {
            items.add(messages.component(
                    player,
                    MessageService.plural("session-summary.unread", unread),
                    Placeholder.unparsed("count", Long.toString(unread))
            ));
        }
        if (incoming > 0) {
            items.add(messages.component(
                    player,
                    MessageService.plural("session-summary.teleports", incoming),
                    Placeholder.unparsed("count", Integer.toString(incoming))
            ));
        }
        if (deathRecovery.hasActiveMarker(player.getUniqueId())) {
            items.add(messages.component(player, "session-summary.death-marker"));
        }

        MaintenanceService.Status status = maintenance.status();
        if (status.maintenanceMode()) {
            items.add(messages.component(player, "session-summary.maintenance"));
        }
        if (status.restartScheduled()) {
            items.add(messages.component(
                    player,
                    "session-summary.restart",
                    Placeholder.unparsed("seconds", Long.toString(status.remainingSeconds()))
            ));
        }
        if (items.isEmpty()) {
            return null;
        }

        Component open = messages.component(player, "session-summary.open")
                .clickEvent(ClickEvent.runCommand("/survival"))
                .hoverEvent(HoverEvent.showText(messages.component(
                        player,
                        "session-summary.open-hover"
                )));
        return messages.component(
                player,
                "session-summary.line",
                Placeholder.component("items", join(items)),
                Placeholder.component("open", open)
        );
    }

    private Component join(List<Component> components) {
        Component result = Component.empty();
        for (int index = 0; index < components.size(); index++) {
            if (index > 0) {
                result = result.append(SEPARATOR);
            }
            result = result.append(components.get(index));
        }
        return result;
    }
}

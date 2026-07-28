package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.SettingsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class ServerListService implements Listener {

    private final MiniMessage strictMiniMessage = MiniMessage.builder().strict(true).build();
    private final SettingsService settings;
    private final MessageService messages;
    private final MaintenanceService maintenance;
    private final Logger logger;
    private final AtomicInteger rotation = new AtomicInteger();
    private volatile List<Component> announcements;

    public ServerListService(
            SettingsService settings,
            MessageService messages,
            MaintenanceService maintenance,
            Logger logger
    ) {
        this.settings = settings;
        this.messages = messages;
        this.maintenance = maintenance;
        this.logger = logger;
        this.announcements = parseAnnouncements(false);
    }

    public void reconfigure() {
        List<Component> prepared = parseAnnouncements(true);
        announcements = prepared;
        rotation.set(0);
    }

    @EventHandler
    public void onPing(ServerListPingEvent event) {
        if (!settings.current().serverListEnabled()) {
            return;
        }
        List<Component> current = announcements;
        Component announcement = current.isEmpty()
                ? Component.empty()
                : current.get(Math.floorMod(rotation.getAndIncrement(), current.size()));
        event.motd(messages.component(
                "server-list.motd",
                Placeholder.component("status", status()),
                Placeholder.component("announcement", announcement)
        ));
    }

    private Component status() {
        MaintenanceService.Status status = maintenance.status();
        if (status.stopping()) {
            return messages.component("server-list.status.restarting");
        }
        if (status.restartScheduled()) {
            return messages.component(
                    "server-list.status.restart-scheduled",
                    Placeholder.unparsed("time", compactDuration(status.remainingSeconds()))
            );
        }
        if (status.maintenanceMode()) {
            return messages.component("server-list.status.maintenance");
        }
        return messages.component("server-list.status.online");
    }

    static String compactDuration(long seconds) {
        if (seconds >= 3600 && seconds % 3600 == 0) {
            return seconds / 3600 + "h";
        }
        if (seconds >= 60 && seconds % 60 == 0) {
            return seconds / 60 + "m";
        }
        return seconds + "s";
    }

    private List<Component> parseAnnouncements(boolean strict) {
        ArrayList<Component> parsed = new ArrayList<>();
        for (String template : settings.current().serverListAnnouncements()) {
            try {
                parsed.add(strictMiniMessage.deserialize(template));
            } catch (RuntimeException exception) {
                if (strict) {
                    throw new IllegalArgumentException(
                            "server-list.announcements contains invalid MiniMessage: " + exception.getMessage(),
                            exception
                    );
                }
                logger.warning("Ignored invalid server-list announcement: " + exception.getMessage());
            }
        }
        return List.copyOf(parsed);
    }
}

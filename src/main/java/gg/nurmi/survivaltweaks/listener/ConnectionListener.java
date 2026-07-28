package gg.nurmi.survivaltweaks.listener;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import gg.nurmi.survivaltweaks.service.TeleportRequestService;
import gg.nurmi.survivaltweaks.service.NotificationService;
import gg.nurmi.survivaltweaks.service.NewPlayerSpawnService;
import gg.nurmi.survivaltweaks.service.PlayerExperienceService;
import gg.nurmi.survivaltweaks.ui.WelcomeBackController;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ConnectionListener implements Listener {

    private final ProfileRepository profiles;
    private final TeleportRequestService requests;
    private final MessageService messages;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final NewPlayerSpawnService newPlayerSpawns;
    private final WelcomeBackController welcomeBack;
    private final PlayerExperienceService experience;

    public ConnectionListener(
            ProfileRepository profiles,
            TeleportRequestService requests,
            MessageService messages,
            SettingsService settings,
            NotificationService notifications,
            NewPlayerSpawnService newPlayerSpawns,
            WelcomeBackController welcomeBack,
            PlayerExperienceService experience
    ) {
        this.profiles = profiles;
        this.requests = requests;
        this.messages = messages;
        this.settings = settings;
        this.notifications = notifications;
        this.newPlayerSpawns = newPlayerSpawns;
        this.welcomeBack = welcomeBack;
        this.experience = experience;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        profiles.load(event.getPlayer().getUniqueId());
        experience.prime(event.getPlayer().getUniqueId());
        welcomeBack.playerJoined(event.getPlayer());
        newPlayerSpawns.playerJoined(event.getPlayer());
        long unread = notifications.unread(event.getPlayer().getUniqueId());
        if (unread > 0) {
            messages.send(
                    event.getPlayer(),
                    "notifications.unread-summary",
                    Placeholder.unparsed("count", Long.toString(unread))
            );
        }
        if (settings.current().connectionMessagesEnabled()) {
            event.joinMessage(null);
            Server server = event.getPlayer().getServer();
            server.getOnlinePlayers().forEach(viewer -> messages.send(
                    viewer,
                    "connection.join",
                    Placeholder.unparsed("player", event.getPlayer().getName())
            ));
            messages.send(
                    server.getConsoleSender(),
                    "connection.join",
                    Placeholder.unparsed("player", event.getPlayer().getName())
            );
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        welcomeBack.playerLeaving(event.getPlayer());
        profiles.playerDisconnected(event.getPlayer().getUniqueId());
        experience.forget(event.getPlayer().getUniqueId());
        newPlayerSpawns.playerDisconnected(event.getPlayer().getUniqueId());
        requests.removeForPlayer(event.getPlayer().getUniqueId());
        if (settings.current().connectionMessagesEnabled()) {
            event.quitMessage(null);
            Server server = event.getPlayer().getServer();
            server.getOnlinePlayers().stream()
                    .filter(viewer -> !viewer.getUniqueId().equals(event.getPlayer().getUniqueId()))
                    .forEach(viewer -> messages.send(
                            viewer,
                            "connection.quit",
                            Placeholder.unparsed("player", event.getPlayer().getName())
                    ));
            messages.send(
                    server.getConsoleSender(),
                    "connection.quit",
                    Placeholder.unparsed("player", event.getPlayer().getName())
            );
        }
    }
}

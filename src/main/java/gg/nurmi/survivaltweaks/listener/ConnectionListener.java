package gg.nurmi.survivaltweaks.listener;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import gg.nurmi.survivaltweaks.service.TeleportRequestService;
import gg.nurmi.survivaltweaks.service.NewPlayerSpawnService;
import gg.nurmi.survivaltweaks.service.PlayerExperienceService;
import gg.nurmi.survivaltweaks.service.ReleaseUpdateService;
import gg.nurmi.survivaltweaks.service.SessionSummaryService;
import gg.nurmi.survivaltweaks.ui.WelcomeBackController;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Server;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.logging.Level;

public final class ConnectionListener implements Listener {

    private final ProfileRepository profiles;
    private final TeleportRequestService requests;
    private final MessageService messages;
    private final SettingsService settings;
    private final NewPlayerSpawnService newPlayerSpawns;
    private final WelcomeBackController welcomeBack;
    private final PlayerExperienceService experience;
    private final ReleaseUpdateService releaseUpdates;
    private final SessionSummaryService sessionSummaries;

    public ConnectionListener(
            ProfileRepository profiles,
            TeleportRequestService requests,
            MessageService messages,
            SettingsService settings,
            NewPlayerSpawnService newPlayerSpawns,
            WelcomeBackController welcomeBack,
            PlayerExperienceService experience,
            ReleaseUpdateService releaseUpdates,
            SessionSummaryService sessionSummaries
    ) {
        this.profiles = profiles;
        this.requests = requests;
        this.messages = messages;
        this.settings = settings;
        this.newPlayerSpawns = newPlayerSpawns;
        this.welcomeBack = welcomeBack;
        this.experience = experience;
        this.releaseUpdates = releaseUpdates;
        this.sessionSummaries = sessionSummaries;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            try {
                profiles.load(event.getUniqueId());
            } catch (RuntimeException exception) {
                Bukkit.getLogger().log(
                        Level.SEVERE,
                        "Could not load SurvivalTweaks profile for " + event.getUniqueId(),
                        exception
                );
                event.disallow(
                        AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text(
                                "Your player data could not be loaded. Please try again shortly."
                        )
                );
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        profiles.load(event.getPlayer().getUniqueId());
        experience.prime(event.getPlayer().getUniqueId());
        welcomeBack.playerJoined(event.getPlayer());
        newPlayerSpawns.playerJoined(event.getPlayer());
        releaseUpdates.playerJoined(event.getPlayer());
        sessionSummaries.playerJoined(event.getPlayer());
        if (settings.current().connectionMessagesEnabled()) {
            event.joinMessage(null);
            Server server = event.getPlayer().getServer();
            Component nameComp = messages.formatPlayerName(event.getPlayer(), settings);
            if (nameComp == null) {
                nameComp = Component.text(event.getPlayer().getName());
            }
            Component finalNameComp = nameComp;
            server.getOnlinePlayers().forEach(viewer -> messages.send(
                    viewer,
                    "connection.join",
                    Placeholder.component("player", finalNameComp)
            ));
            messages.send(
                    server.getConsoleSender(),
                    "connection.join",
                    Placeholder.component("player", finalNameComp)
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
            Component nameComp = messages.formatPlayerName(event.getPlayer(), settings);
            if (nameComp == null) {
                nameComp = Component.text(event.getPlayer().getName());
            }
            Component finalNameComp = nameComp;
            server.getOnlinePlayers().stream()
                    .filter(viewer -> !viewer.getUniqueId().equals(event.getPlayer().getUniqueId()))
                    .forEach(viewer -> messages.send(
                            viewer,
                            "connection.quit",
                            Placeholder.component("player", finalNameComp)
                    ));
            messages.send(
                    server.getConsoleSender(),
                    "connection.quit",
                    Placeholder.component("player", finalNameComp)
            );
        }
    }
}

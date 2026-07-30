package gg.nurmi.survivaltweaks.listener;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.NewPlayerSpawnService;
import gg.nurmi.survivaltweaks.service.NotificationService;
import gg.nurmi.survivaltweaks.service.PlayerExperienceService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import gg.nurmi.survivaltweaks.service.ReleaseUpdateService;
import gg.nurmi.survivaltweaks.service.TeleportRequestService;
import gg.nurmi.survivaltweaks.ui.WelcomeBackController;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionLifecycleIntegrationTest {

    @Test
    void preloginJoinReconnectAndQuitCoordinateEveryPlayerSubsystem() {
        UUID playerId = UUID.randomUUID();
        ProfileRepository profiles = mock(ProfileRepository.class);
        TeleportRequestService requests = mock(TeleportRequestService.class);
        MessageService messages = mock(MessageService.class);
        PluginSettings configuration = mock(PluginSettings.class);
        SettingsService settings = new SettingsService(configuration);
        NotificationService notifications = mock(NotificationService.class);
        NewPlayerSpawnService spawns = mock(NewPlayerSpawnService.class);
        WelcomeBackController welcomeBack = mock(WelcomeBackController.class);
        PlayerExperienceService experience = mock(PlayerExperienceService.class);
        ReleaseUpdateService releaseUpdates = mock(ReleaseUpdateService.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(configuration.connectionMessagesEnabled()).thenReturn(false);
        when(notifications.unread(playerId)).thenReturn(0L);

        ConnectionListener listener = new ConnectionListener(
                profiles,
                requests,
                messages,
                settings,
                notifications,
                spawns,
                welcomeBack,
                experience,
                releaseUpdates
        );
        AsyncPlayerPreLoginEvent preLogin = mock(AsyncPlayerPreLoginEvent.class);
        when(preLogin.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.ALLOWED);
        when(preLogin.getUniqueId()).thenReturn(playerId);
        listener.onPreLogin(preLogin);

        PlayerJoinEvent firstJoin = mock(PlayerJoinEvent.class);
        when(firstJoin.getPlayer()).thenReturn(player);
        listener.onJoin(firstJoin);
        PlayerJoinEvent reconnect = mock(PlayerJoinEvent.class);
        when(reconnect.getPlayer()).thenReturn(player);
        listener.onJoin(reconnect);

        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        listener.onQuit(quit);

        verify(profiles, times(3)).load(playerId);
        verify(experience, times(2)).prime(playerId);
        verify(welcomeBack, times(2)).playerJoined(player);
        verify(spawns, times(2)).playerJoined(player);
        verify(releaseUpdates, times(2)).playerJoined(player);
        verify(welcomeBack).playerLeaving(player);
        verify(profiles).playerDisconnected(playerId);
        verify(experience).forget(playerId);
        verify(spawns).playerDisconnected(playerId);
        verify(requests).removeForPlayer(playerId);
        verify(messages, never()).formatPlayerName(player, settings);
    }
}

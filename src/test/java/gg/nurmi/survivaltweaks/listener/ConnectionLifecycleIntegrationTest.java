package gg.nurmi.survivaltweaks.listener;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.JoinExperienceCoordinator;
import gg.nurmi.survivaltweaks.service.PlayerExperienceService;
import gg.nurmi.survivaltweaks.service.PlayerSessionService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import gg.nurmi.survivaltweaks.service.TeleportRequestService;
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
        PlayerExperienceService experience = mock(PlayerExperienceService.class);
        PlayerSessionService sessions = mock(PlayerSessionService.class);
        JoinExperienceCoordinator joinExperience = mock(JoinExperienceCoordinator.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(configuration.connectionMessagesEnabled()).thenReturn(false);
        PlayerSessionService.Session session = new PlayerSessionService.Session(
                false,
                java.util.Optional.empty()
        );
        when(sessions.begin(player)).thenReturn(session);

        ConnectionListener listener = new ConnectionListener(
                profiles,
                requests,
                messages,
                settings,
                experience,
                sessions,
                joinExperience
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

        verify(profiles).load(playerId);
        verify(sessions, times(2)).begin(player);
        verify(experience, times(2)).prime(playerId);
        verify(joinExperience, times(2)).playerJoined(player, session);
        verify(sessions).end(player);
        verify(profiles).playerDisconnected(playerId);
        verify(experience).forget(playerId);
        verify(joinExperience).playerDisconnected(player);
        verify(requests).removeForPlayer(playerId);
        verify(messages, never()).formatPlayerName(player, settings);
    }
}

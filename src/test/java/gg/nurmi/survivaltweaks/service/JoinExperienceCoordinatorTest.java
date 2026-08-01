package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.ui.WelcomeBackController;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JoinExperienceCoordinatorTest {

    @Test
    void richWelcomeOverviewSuppressesOnlyDuplicateSessionActivity() {
        NewPlayerSpawnService spawns = mock(NewPlayerSpawnService.class);
        WelcomeBackController welcome = mock(WelcomeBackController.class);
        ReleaseUpdateService updates = mock(ReleaseUpdateService.class);
        SessionSummaryService summary = mock(SessionSummaryService.class);
        OperationalHealthService health = mock(OperationalHealthService.class);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        PlayerSessionService.Session session = new PlayerSessionService.Session(
                false,
                java.util.Optional.of(java.time.Instant.parse("2026-08-01T10:00:00Z"))
        );
        when(welcome.playerJoined(player, session)).thenReturn(true);
        JoinExperienceCoordinator coordinator = new JoinExperienceCoordinator(
                spawns, welcome, updates, summary, health
        );

        coordinator.playerJoined(player, session);
        coordinator.playerDisconnected(player);

        verify(spawns).playerJoined(player);
        verify(welcome).playerJoined(player, session);
        verify(summary).playerJoined(player, false);
        verify(updates).playerJoined(player);
        verify(health).playerJoined(player);
        verify(spawns).playerDisconnected(playerId);
    }
}

package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.Profile;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerSessionServiceTest {

    @Test
    void ownsIdentityPreviousSeenAndLastSeenLifecycle() {
        UUID playerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-01T12:00:00Z");
        Instant previous = now.minus(Duration.ofHours(3));
        ProfileRepository profiles = mock(ProfileRepository.class);
        Profile profile = mock(Profile.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Alex");
        when(player.getStatistic(Statistic.PLAY_ONE_MINUTE)).thenReturn(12_000);
        when(player.hasPlayedBefore()).thenReturn(true);
        when(profiles.load(playerId)).thenReturn(profile);
        when(profile.lastSeenAt()).thenReturn(Optional.of(previous));
        PlayerSessionService sessions = new PlayerSessionService(
                profiles,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        PlayerSessionService.Session started = sessions.begin(player);

        assertFalse(started.firstJoin());
        assertEquals(Optional.of(previous), started.previousSeen());
        assertEquals(Duration.ofHours(3), sessions.awayDuration(playerId));
        verify(profile).lastKnownName("Alex");
        verify(profile).playTimeTicks(12_000);
        verify(profiles).save(profile);

        sessions.end(player);

        verify(profile).lastSeenAt(now);
        verify(profile, times(2)).lastKnownName("Alex");
        verify(profiles, times(2)).save(profile);
        assertEquals(Optional.empty(), sessions.previousSeen(playerId));
    }
}

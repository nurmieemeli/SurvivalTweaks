package gg.nurmi.survivaltweaks.service;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionBarServiceTest {

    @Test
    void higherPriorityStatusTemporarilyBlocksLowerPriorityHints() {
        MutableClock clock = new MutableClock();
        ActionBarService actionBars = new ActionBarService(clock);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Component teleport = Component.text("teleport");
        Component lock = Component.text("lock");

        actionBars.show(player, teleport, ActionBarService.TELEPORT_PRIORITY, Duration.ofSeconds(2));
        actionBars.show(player, lock, ActionBarService.LOCK_HINT_PRIORITY, Duration.ofSeconds(1));

        verify(player).sendActionBar(teleport);
        verify(player, never()).sendActionBar(lock);

        clock.advance(Duration.ofSeconds(3));
        actionBars.show(player, lock, ActionBarService.LOCK_HINT_PRIORITY, Duration.ofSeconds(1));

        verify(player).sendActionBar(lock);
    }

    @Test
    void exactClearDoesNotEraseAnotherSystemsLowerPriorityMessage() {
        ActionBarService actionBars = new ActionBarService(new MutableClock());
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Component death = Component.text("death");

        actionBars.show(player, death, ActionBarService.DEATH_MARKER_PRIORITY, Duration.ofSeconds(2));
        actionBars.clearExact(player, ActionBarService.SLEEP_PRIORITY);

        verify(player).sendActionBar(death);
        verify(player, never()).sendActionBar(Component.empty());
    }

    @Test
    void unchangedStatusExtendsItsLifetimeWithoutSpammingPackets() {
        MutableClock clock = new MutableClock();
        ActionBarService actionBars = new ActionBarService(clock);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Component status = Component.text("warming");

        actionBars.show(player, status, ActionBarService.TELEPORT_PRIORITY, Duration.ofSeconds(1));
        clock.advance(Duration.ofMillis(500));
        actionBars.show(player, status, ActionBarService.TELEPORT_PRIORITY, Duration.ofSeconds(1));
        clock.advance(Duration.ofMillis(500));
        actionBars.show(player, status, ActionBarService.TELEPORT_PRIORITY, Duration.ofSeconds(1));

        verify(player, times(1)).sendActionBar(status);

        clock.advance(Duration.ofSeconds(1));
        actionBars.show(player, status, ActionBarService.TELEPORT_PRIORITY, Duration.ofSeconds(1));
        verify(player, times(2)).sendActionBar(status);
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-07-27T12:00:00Z");

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

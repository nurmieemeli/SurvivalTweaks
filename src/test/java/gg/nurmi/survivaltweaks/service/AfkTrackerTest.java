package gg.nurmi.survivaltweaks.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfkTrackerTest {

    @Test
    void automaticallyMarksIdlePlayersAndActivityClearsThem() {
        MutableClock clock = new MutableClock();
        AfkTracker tracker = new AfkTracker(clock);
        UUID playerId = UUID.randomUUID();
        tracker.joined(playerId);

        clock.advance(Duration.ofMinutes(4));
        assertFalse(tracker.updateAutomatic(List.of(playerId), Duration.ofMinutes(5)));
        assertFalse(tracker.isAfk(playerId));

        clock.advance(Duration.ofMinutes(1));
        assertTrue(tracker.updateAutomatic(List.of(playerId), Duration.ofMinutes(5)));
        assertTrue(tracker.isAfk(playerId));
        assertTrue(tracker.activity(playerId));
        assertFalse(tracker.isAfk(playerId));
    }

    @Test
    void manualToggleIsSessionScoped() {
        MutableClock clock = new MutableClock();
        AfkTracker tracker = new AfkTracker(clock);
        UUID playerId = UUID.randomUUID();
        tracker.joined(playerId);

        assertEquals(AfkTracker.State.AFK, tracker.toggle(playerId));
        assertEquals(1, tracker.count());
        assertEquals(AfkTracker.State.ACTIVE, tracker.toggle(playerId));
        assertEquals(0, tracker.count());

        tracker.toggle(playerId);
        tracker.left(playerId);
        tracker.joined(playerId);
        assertFalse(tracker.isAfk(playerId));
    }

    @Test
    void onlyOnlinePlayersAreEvaluatedForAutomaticAfk() {
        MutableClock clock = new MutableClock();
        AfkTracker tracker = new AfkTracker(clock);
        UUID online = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        tracker.joined(online);
        tracker.joined(offline);
        clock.advance(Duration.ofMinutes(10));

        assertTrue(tracker.updateAutomatic(List.of(online), Duration.ofMinutes(5)));
        assertTrue(tracker.isAfk(online));
        assertFalse(tracker.isAfk(offline));
    }

    @Test
    void movementActivitySamplesAtMostOncePerSecondButClearsAfkImmediately() {
        MutableClock clock = new MutableClock();
        AfkTracker tracker = new AfkTracker(clock);
        UUID playerId = UUID.randomUUID();
        tracker.joined(playerId);

        clock.advance(Duration.ofMillis(500));
        assertFalse(tracker.movementActivity(playerId));
        clock.advance(Duration.ofMillis(4_500));
        assertTrue(tracker.updateAutomatic(List.of(playerId), Duration.ofSeconds(5)));

        assertTrue(tracker.movementActivity(playerId));
        assertFalse(tracker.isAfk(playerId));
    }

    private static final class MutableClock extends Clock {

        private Instant instant = Instant.parse("2026-07-28T08:00:00Z");

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

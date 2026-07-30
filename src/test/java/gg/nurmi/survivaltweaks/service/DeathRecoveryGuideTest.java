package gg.nurmi.survivaltweaks.service;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeathRecoveryGuideTest {

    @Test
    void positionsTheFarGuideAtAStableOffsetTowardTheDestination() {
        Location origin = new Location(null, 0, 64, 0);
        Location target = new Location(null, 10, 64, 0);

        Location guide = DeathRecoveryService.directionalGuideLocation(origin, target, 3.5);

        assertEquals(3.5, guide.getX(), 0.000_001);
        assertEquals(64.0, guide.getY(), 0.000_001);
        assertEquals(0.0, guide.getZ(), 0.000_001);
    }

    @Test
    void positionsTheFarGuideAlongThreeDimensionalRoutes() {
        Location origin = new Location(null, 2, 10, -3);
        Location target = new Location(null, 2, 14, -3);

        Location guide = DeathRecoveryService.directionalGuideLocation(origin, target, 2.0);

        assertEquals(2.0, guide.getX(), 0.000_001);
        assertEquals(12.0, guide.getY(), 0.000_001);
        assertEquals(-3.0, guide.getZ(), 0.000_001);
    }

    @Test
    void doesNotOvershootACloserTarget() {
        Location origin = new Location(null, 0, 64, 0);
        Location target = new Location(null, 2, 64, 0);

        Location guide = DeathRecoveryService.directionalGuideLocation(origin, target, 3.5);

        assertEquals(2.0, guide.getX(), 0.000_001);
        assertEquals(64.0, guide.getY(), 0.000_001);
        assertEquals(0.0, guide.getZ(), 0.000_001);
    }

    @Test
    void calculatesCorrectDirectionalArrows() {
        Location player = new Location(null, 0, 64, 0, 180.0f, 0.0f); // Facing North (-Z)

        assertEquals("↑", DeathRecoveryService.directionArrow(player, new Location(null, 0, 64, -10)));
        assertEquals("↓", DeathRecoveryService.directionArrow(player, new Location(null, 0, 64, 10)));
        assertEquals("→", DeathRecoveryService.directionArrow(player, new Location(null, 10, 64, 0)));
        assertEquals("←", DeathRecoveryService.directionArrow(player, new Location(null, -10, 64, 0)));
        assertEquals("↗", DeathRecoveryService.directionArrow(player, new Location(null, 10, 64, -10)));
        assertEquals("↖", DeathRecoveryService.directionArrow(player, new Location(null, -10, 64, -10)));
        assertEquals("↘", DeathRecoveryService.directionArrow(player, new Location(null, 10, 64, 10)));
        assertEquals("↙", DeathRecoveryService.directionArrow(player, new Location(null, -10, 64, 10)));
    }

    @Test
    void mapsStoredPaperDamageCausesToLocaleKeysAndSafelyHandlesOldValues() {
        assertEquals("death-recovery.cause.fall", DeathRecoveryService.causeKey("FALL"));
        assertEquals(
                "death-recovery.cause.fly-into-wall",
                DeathRecoveryService.causeKey("fly_into_wall")
        );
        assertEquals("death-recovery.cause.unknown", DeathRecoveryService.causeKey("UNKNOWN"));
        assertEquals("death-recovery.cause.unknown", DeathRecoveryService.causeKey("removed-cause"));
        assertEquals("death-recovery.cause.unknown", DeathRecoveryService.causeKey(null));
    }

    @Test
    void detectsChunkTransitionsWithoutTreatingMovementInsideAChunkAsOne() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());

        assertTrue(DeathRecoveryService.sameChunk(
                new Location(world, 1, 64, 1),
                new Location(world, 15.9, 70, 15.9)
        ));
        assertFalse(DeathRecoveryService.sameChunk(
                new Location(world, 15.9, 64, 1),
                new Location(world, 16.0, 64, 1)
        ));
    }

    @Test
    void clampsFallbackDisplaysInsideThePlayersLoadedChunk() {
        assertEquals(0.25, DeathRecoveryService.clampToChunk(-5.0, 0), 0.000_001);
        assertEquals(15.75, DeathRecoveryService.clampToChunk(20.0, 0), 0.000_001);
        assertEquals(-15.75, DeathRecoveryService.clampToChunk(-20.0, -1), 0.000_001);
        assertEquals(-0.25, DeathRecoveryService.clampToChunk(5.0, -1), 0.000_001);
    }
}

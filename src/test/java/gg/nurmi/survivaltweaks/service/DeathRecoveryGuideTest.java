package gg.nurmi.survivaltweaks.service;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}

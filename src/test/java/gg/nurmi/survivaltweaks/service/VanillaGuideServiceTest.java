package gg.nurmi.survivaltweaks.service;

import org.bukkit.event.player.PlayerBedEnterEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VanillaGuideServiceTest {

    @Test
    void netherCoordinateScalingMatchesVanillaForPositiveAndNegativePositions() {
        assertEquals(100, VanillaGuideService.scaledCoordinate(800, true));
        assertEquals(-101, VanillaGuideService.scaledCoordinate(-801, true));
        assertEquals(800, VanillaGuideService.scaledCoordinate(100, false));
        assertEquals(-808, VanillaGuideService.scaledCoordinate(-101, false));
    }

    @Test
    void bedFailuresMapToStableLocalizedReasonKeys() {
        assertEquals(
                "time",
                VanillaGuideService.sleepReasonKey(
                        PlayerBedEnterEvent.BedEnterResult.NOT_POSSIBLE_NOW
                )
        );
        assertEquals(
                "monsters",
                VanillaGuideService.sleepReasonKey(PlayerBedEnterEvent.BedEnterResult.NOT_SAFE)
        );
        assertEquals(
                "dimension",
                VanillaGuideService.sleepReasonKey(PlayerBedEnterEvent.BedEnterResult.EXPLOSION)
        );
        assertEquals(
                "other",
                VanillaGuideService.sleepReasonKey(
                        PlayerBedEnterEvent.BedEnterResult.OTHER_PROBLEM
                )
        );
    }
}

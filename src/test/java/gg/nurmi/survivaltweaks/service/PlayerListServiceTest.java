package gg.nurmi.survivaltweaks.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerListServiceTest {

    @Test
    void everyAfkAliasClearsTheIdleTimerRatherThanRestartingIt() {
        assertTrue(PlayerListService.isAfkCommand("/afk"));
        assertTrue(PlayerListService.isAfkCommand("/away"));
        assertTrue(PlayerListService.isAfkCommand("/poissa"));
    }

    @Test
    void afkDetectionIgnoresCaseLeadingSpaceAndTrailingArguments() {
        assertTrue(PlayerListService.isAfkCommand("/AFK"));
        assertTrue(PlayerListService.isAfkCommand("   /Afk"));
        assertTrue(PlayerListService.isAfkCommand("/afk back in five"));
    }

    @Test
    void namespacedFormsOfTheCommandAreStillRecognised() {
        assertTrue(PlayerListService.isAfkCommand("/survivaltweaks:afk"));
        assertTrue(PlayerListService.isAfkCommand("/minecraft:away"));
        assertTrue(PlayerListService.isAfkCommand("/SurvivalTweaks:Poissa now"));
    }

    @Test
    void otherCommandsCountAsActivityAndAreNotTreatedAsAfk() {
        assertFalse(PlayerListService.isAfkCommand("/afkzone"));
        assertFalse(PlayerListService.isAfkCommand("/home afk"));
        assertFalse(PlayerListService.isAfkCommand("/survival"));
        assertFalse(PlayerListService.isAfkCommand(""));
        assertFalse(PlayerListService.isAfkCommand("/"));
    }

    @Test
    void brokenServerMetricsNeverReachPlayersAsNanOrNegativeNumbers() {
        assertEquals(0.0, PlayerListService.finiteNonNegative(Double.NaN));
        assertEquals(0.0, PlayerListService.finiteNonNegative(Double.POSITIVE_INFINITY));
        assertEquals(0.0, PlayerListService.finiteNonNegative(Double.NEGATIVE_INFINITY));
        assertEquals(0.0, PlayerListService.finiteNonNegative(-3.5));
    }

    @Test
    void healthyMetricsPassThroughUnchanged() {
        assertEquals(0.0, PlayerListService.finiteNonNegative(0.0));
        assertEquals(19.85, PlayerListService.finiteNonNegative(19.85));
        assertEquals(20.0, PlayerListService.finiteNonNegative(20.0));
    }
}

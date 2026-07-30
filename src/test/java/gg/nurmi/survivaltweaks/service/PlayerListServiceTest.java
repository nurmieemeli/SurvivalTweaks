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

    @Test
    void rowsKeepActivePlayersAheadOfAfkPlayers() {
        assertEquals(0, PlayerListService.rowPriority(false));
        assertEquals(1, PlayerListService.rowPriority(true));
    }

    @Test
    void minecraftDayTimeFormatsAsACompactClock() {
        assertEquals(360, PlayerListService.worldTimeMinutes(0));
        assertEquals("06:00", PlayerListService.formatWorldTime(0));
        assertEquals("12:00", PlayerListService.formatWorldTime(6_000));
        assertEquals("18:00", PlayerListService.formatWorldTime(12_000));
        assertEquals("00:00", PlayerListService.formatWorldTime(18_000));
        assertEquals("06:00", PlayerListService.formatWorldTime(24_000));
        assertEquals("00:00", PlayerListService.formatWorldTime(-6_000));
        assertEquals("06:10", PlayerListService.formatWorldTime(250));
        assertEquals("06:20", PlayerListService.formatWorldTime(416));
    }

    @Test
    void thunderTakesPrecedenceOverRainInWeatherPresentation() {
        assertEquals("player-list.weather.clear", PlayerListService.weatherKey(false, false));
        assertEquals("player-list.weather.rain", PlayerListService.weatherKey(false, true));
        assertEquals("player-list.weather.thunder", PlayerListService.weatherKey(true, true));
        assertEquals("player-list.weather.thunder", PlayerListService.weatherKey(true, false));
    }
}

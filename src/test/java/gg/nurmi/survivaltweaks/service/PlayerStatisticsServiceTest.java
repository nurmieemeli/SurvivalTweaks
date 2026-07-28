package gg.nurmi.survivaltweaks.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerStatisticsServiceTest {

    @Test
    void formatsVanillaTickStatisticsAsReadableDurations() {
        assertEquals("0h 0m", PlayerStatisticsService.formatTicks(0));
        assertEquals("1h 30m", PlayerStatisticsService.formatTicks(20L * 60L * 90L));
        assertEquals("2d 2h", PlayerStatisticsService.formatTicks(20L * 60L * 60L * 50L));
    }

    @Test
    void formatsVanillaCentimeterStatisticsAsReadableDistances() {
        assertEquals("999 m", PlayerStatisticsService.formatDistance(99_900));
        assertEquals("1.2 km", PlayerStatisticsService.formatDistance(123_450));
    }

    @Test
    void formatsVanillaDamageStatisticsAsDamagePoints() {
        assertEquals("12.5", PlayerStatisticsService.formatDamage(125));
        assertEquals("0.0", PlayerStatisticsService.formatDamage(-1));
    }
}

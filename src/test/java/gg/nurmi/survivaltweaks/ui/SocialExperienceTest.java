package gg.nurmi.survivaltweaks.ui;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocialExperienceTest {

    @Test
    void welcomeBackRequiresARealAbsenceAtOrAboveTheThreshold() {
        Instant now = Instant.parse("2026-07-28T12:00:00Z");

        assertTrue(WelcomeBackController.shouldShow(
                true,
                now.minus(Duration.ofHours(6)),
                now,
                Duration.ofHours(6)
        ));
        assertFalse(WelcomeBackController.shouldShow(
                true,
                now.minus(Duration.ofHours(5)),
                now,
                Duration.ofHours(6)
        ));
        assertFalse(WelcomeBackController.shouldShow(
                false,
                now.minus(Duration.ofDays(2)),
                now,
                Duration.ofHours(6)
        ));
        assertFalse(WelcomeBackController.shouldShow(
                true,
                null,
                now,
                Duration.ofHours(6)
        ));
    }
}

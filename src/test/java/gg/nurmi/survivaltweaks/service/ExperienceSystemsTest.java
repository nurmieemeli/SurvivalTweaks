package gg.nurmi.survivaltweaks.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExperienceSystemsTest {

    @Test
    void sleepThresholdRoundsUpAndNeverRequiresZeroActivePlayers() {
        assertEquals(0, SleepVoteService.requiredSleepers(0, 50));
        assertEquals(1, SleepVoteService.requiredSleepers(1, 50));
        assertEquals(2, SleepVoteService.requiredSleepers(3, 50));
        assertEquals(3, SleepVoteService.requiredSleepers(4, 75));
        assertThrows(
                IllegalArgumentException.class,
                () -> SleepVoteService.requiredSleepers(2, 0)
        );
    }

    @Test
    void mentionParsingIsBoundedUniqueAndUsernameAware() {
        assertEquals(
                List.of("Alex", "Steve"),
                MentionService.mentionedNames("@Alex hello @alex and @Steve!", 3)
        );
        assertEquals(
                List.of("One", "Two"),
                MentionService.mentionedNames("@One @Two @Three", 2)
        );
        assertEquals(
                List.of(),
                MentionService.mentionedNames("mail@example.com", 3)
        );
    }

    @Test
    void maintenanceDurationsAcceptExplicitUnitsAndRejectAmbiguity() {
        assertEquals(Duration.ofSeconds(30), MaintenanceService.parseDelay("30s"));
        assertEquals(Duration.ofMinutes(5), MaintenanceService.parseDelay("5M"));
        assertEquals(Duration.ofHours(1), MaintenanceService.parseDelay("1h"));
        assertNull(MaintenanceService.parseDelay("5"));
        assertNull(MaintenanceService.parseDelay("soon"));
    }

    @Test
    void serverListRestartDurationUsesCompactExactUnits() {
        assertEquals("1h", ServerListService.compactDuration(3600));
        assertEquals("5m", ServerListService.compactDuration(300));
        assertEquals("59s", ServerListService.compactDuration(59));
    }

    @Test
    void mailNormalizationRemovesControlsAndCollapsesWhitespace() {
        assertEquals("Hello world", MailService.normalize("  Hello\n\tworld  "));
        assertEquals("", MailService.normalize("\n\t"));
    }
}

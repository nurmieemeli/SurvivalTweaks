package gg.nurmi.survivaltweaks.object;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileTest {

    @Test
    void homeNamesAreUniqueAndCaseInsensitive() {
        Profile profile = new Profile(UUID.randomUUID());

        assertTrue(profile.addHome(home("Mökki")));
        assertFalse(profile.addHome(home("MÖKKI")));
        assertEquals("Mökki", profile.home("mökki").orElseThrow().name());
        assertEquals(1, profile.homes().size());
    }

    @Test
    void removingAHomeUsesTheSameCaseInsensitiveLookup() {
        Profile profile = new Profile(UUID.randomUUID());
        profile.addHome(home("Kaivos"));

        assertTrue(profile.removeHome("KAIVOS"));
        assertTrue(profile.homes().isEmpty());
        assertFalse(profile.removeHome("kaivos"));
    }

    @Test
    void renamesHomesAndMaintainsExperienceState() {
        Profile profile = new Profile(UUID.randomUUID());
        profile.addHome(home("old"));

        assertTrue(profile.renameHome("OLD", "new"));
        assertTrue(profile.home("new").isPresent());
        assertTrue(profile.markHintSeen(OnboardingHint.HOME));
        assertFalse(profile.markHintSeen(OnboardingHint.HOME));
        profile.preferences(PlayerPreferences.DEFAULTS.withReducedEffects(true));
        profile.addNotification(
                PlayerNotification.create(
                        NotificationType.TELEPORT_DECLINED,
                        Instant.parse("2026-07-27T12:00:00Z"),
                        "Alex",
                        ""
                ),
                40
        );

        assertTrue(profile.preferences().reducedEffects());
        assertEquals(1L, profile.unreadNotificationCount());
        assertTrue(profile.markNotificationRead(profile.notifications().getFirst().id()));
        assertEquals(0L, profile.unreadNotificationCount());
    }

    @Test
    void persistsPrivacyAndMailBlockStateInSnapshots() {
        Profile profile = new Profile(UUID.randomUUID());
        UUID blocked = UUID.randomUUID();
        profile.preferences(PlayerPreferences.DEFAULTS
                .withPublicProfile(false)
                .withMail(false));
        profile.lastKnownName("Alex");
        profile.lastSeenAt(Instant.parse("2026-07-28T08:00:00Z"));
        profile.playTimeTicks(12_000);

        assertTrue(profile.blockMailFrom(blocked));
        assertFalse(profile.blockMailFrom(blocked));
        assertTrue(profile.blocksMailFrom(blocked));
        assertFalse(profile.preferences().publicProfileEnabled());
        assertFalse(profile.preferences().mailEnabled());
        assertEquals("Alex", profile.snapshot().lastKnownName());
        assertEquals(12_000L, profile.snapshot().playTimeTicks());
        assertTrue(profile.snapshot().blockedMailSenders().contains(blocked));
        assertTrue(profile.unblockMailFrom(blocked));
    }

    private Home home(String name) {
        return new Home(name, UUID.randomUUID(), "world", 1.0, 2.0, 3.0, 4.0f, 5.0f);
    }
}

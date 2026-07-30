package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.HomeArrivalStyle;
import gg.nurmi.survivaltweaks.object.HomeCategory;
import gg.nurmi.survivaltweaks.object.LanguagePreference;
import gg.nurmi.survivaltweaks.object.NotificationType;
import gg.nurmi.survivaltweaks.object.OnboardingHint;
import gg.nurmi.survivaltweaks.object.PlayerNotification;
import gg.nurmi.survivaltweaks.object.PlayerPreferences;
import gg.nurmi.survivaltweaks.object.Profile;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileStoreTest {

    @TempDir
    Path directory;

    private final Logger logger = Logger.getLogger(ProfileStoreTest.class.getName());

    @Test
    void savesAndLoadsTheCurrentSchema() throws IOException {
        UUID uniqueId = UUID.randomUUID();
        ProfileStore store = new ProfileStore(directory, logger);
        Profile profile = new Profile(uniqueId);
        UUID worldId = UUID.randomUUID();
        profile.addHome(new Home(
                "Mökki",
                worldId,
                "world_nether",
                1.25,
                64.0,
                -8.5,
                90.0f,
                12.0f,
                Material.RED_BED,
                "Nether hub",
                true,
                2,
                HomeCategory.BASE,
                HomeArrivalStyle.PORTAL
        ));
        PlayerPreferences preferences = new PlayerPreferences(
                false,
                true,
                false,
                true,
                true,
                false,
                false,
                true,
                false,
                true,
                LanguagePreference.FINNISH
        );
        UUID actorId = UUID.randomUUID();
        PlayerNotification notification = PlayerNotification.create(
                NotificationType.LOCK_ACCESS_DENIED,
                Instant.parse("2026-07-27T12:00:00Z"),
                actorId,
                "Alex",
                "Workshop"
        ).withRead(true);
        UUID blockedSender = UUID.randomUUID();
        profile.preferences(preferences);
        profile.lastKnownName("Emeli");
        profile.lastSeenAt(Instant.parse("2026-07-28T09:30:00Z"));
        profile.playTimeTicks(72_000L);
        profile.blockMailFrom(blockedSender);
        profile.markHintSeen(OnboardingHint.HOME);
        profile.markHintSeen(OnboardingHint.LOCK_CONTROL);
        profile.markHintSeen(OnboardingHint.VANILLA_NETHER_COORDINATES);
        profile.notifications(java.util.List.of(notification));

        store.save(profile.snapshot());
        Profile loaded = store.load(uniqueId);

        assertEquals(profile.homes(), loaded.homes());
        assertTrue(Files.readString(directory.resolve(uniqueId + ".yml"))
                .contains("schema-version: 6"));
        assertEquals(Material.RED_BED, loaded.homes().getFirst().icon());
        assertEquals("Nether hub", loaded.homes().getFirst().description());
        assertTrue(loaded.homes().getFirst().favorite());
        assertEquals(HomeCategory.BASE, loaded.homes().getFirst().category());
        assertEquals(HomeArrivalStyle.PORTAL, loaded.homes().getFirst().arrivalStyle());
        assertEquals(preferences, loaded.preferences());
        assertEquals(profile.seenHints(), loaded.seenHints());
        assertEquals(java.util.List.of(notification), loaded.notifications());
        assertEquals(actorId, loaded.notifications().getFirst().actorId());
        assertEquals("Emeli", loaded.lastKnownName());
        assertEquals(
                Instant.parse("2026-07-28T09:30:00Z"),
                loaded.lastSeenAt().orElseThrow()
        );
        assertEquals(72_000L, loaded.playTimeTicks());
        assertTrue(loaded.blocksMailFrom(blockedSender));
        assertEquals(worldId, loaded.home("Mökki").orElseThrow().worldId());
    }

    @Test
    void readsTheLegacyHomeSection() throws IOException {
        UUID uniqueId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        Files.writeString(
                directory.resolve(uniqueId + ".yml"),
                """
                home:
                  kaivos:
                    world: world
                    x: 10.0
                    y: 20.0
                    z: 30.0
                    yaw: 40.0
                    pitch: 50.0
                """,
                StandardCharsets.UTF_8
        );

        Profile loaded = new ProfileStore(directory, logger, world -> worldId).load(uniqueId);

        Home home = loaded.home("KAIVOS").orElseThrow();
        assertEquals("world", home.worldName());
        assertEquals(worldId, home.worldId());
        assertEquals(10.0, home.x());
        assertEquals(50.0f, home.pitch());
        assertTrue(loaded.migrationRequired());
    }
}

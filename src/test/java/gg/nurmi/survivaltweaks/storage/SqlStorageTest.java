package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.object.ContainerLockSnapshot;
import gg.nurmi.survivaltweaks.object.DeathMarker;
import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.HomeArrivalStyle;
import gg.nurmi.survivaltweaks.object.HomeCategory;
import gg.nurmi.survivaltweaks.object.LanguagePreference;
import gg.nurmi.survivaltweaks.object.LockAccessMode;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnAssignment;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnLocation;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnState;
import gg.nurmi.survivaltweaks.object.NotificationType;
import gg.nurmi.survivaltweaks.object.OnboardingHint;
import gg.nurmi.survivaltweaks.object.PlayerNotification;
import gg.nurmi.survivaltweaks.object.PlayerPreferences;
import gg.nurmi.survivaltweaks.object.ProfileSnapshot;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlStorageTest {

    @TempDir
    Path directory;

    private final Logger logger = Logger.getLogger(SqlStorageTest.class.getName());

    @Test
    void roundTripsEveryAggregateAndProducesAStablePortableSnapshot() throws IOException {
        UUID instanceId = UUID.randomUUID();
        StorageSnapshot expected = richSnapshot();
        try (SqlStorage source = sqlite("source.db");
             SqlStorage destination = sqlite("destination.db")) {
            source.replaceAll(expected, instanceId);

            StorageSnapshot exported = source.exportSnapshot();
            assertEquals(expected, exported);
            assertTrue(source.verify().healthy());
            assertEquals(instanceId, source.instanceId());

            destination.replaceAll(exported, instanceId);
            StorageSnapshot copied = destination.exportSnapshot();
            assertEquals(exported.counts(), copied.counts());
            assertEquals(
                    StorageChecksum.calculate(exported),
                    StorageChecksum.calculate(copied)
            );
            assertTrue(destination.verify().healthy());
        }
    }

    @Test
    void failedAggregateWriteRollsBackWithoutPartialChanges() throws IOException {
        UUID worldId = UUID.randomUUID();
        BlockKey sharedBlock = new BlockKey(worldId, 10, 64, 20);
        ContainerLockSnapshot original = lock(UUID.randomUUID(), sharedBlock);
        try (SqlStorage storage = sqlite("rollback.db")) {
            storage.saveLocks(List.of(original));
            ContainerLockSnapshot conflicting = lock(UUID.randomUUID(), sharedBlock);

            assertThrows(
                    IOException.class,
                    () -> storage.saveLocks(List.of(original, conflicting))
            );
            assertEquals(List.of(original), storage.loadLocks());
            assertTrue(storage.verify().healthy());
        }
    }

    @Test
    void checksumUsesTheMillisecondPrecisionProvidedByTheSqlSchema() throws IOException {
        UUID playerId = UUID.randomUUID();
        Instant precise = Instant.parse("2026-07-30T12:00:00.123456789Z");
        ProfileSnapshot profile = new ProfileSnapshot(
                playerId,
                List.of(),
                PlayerPreferences.DEFAULTS,
                Set.of(),
                List.of(new PlayerNotification(
                        UUID.randomUUID(),
                        NotificationType.MAIL,
                        precise,
                        null,
                        "",
                        "Precision",
                        false
                )),
                "Emeli",
                precise,
                1,
                Set.of()
        );
        StorageSnapshot source = new StorageSnapshot(
                List.of(profile),
                List.of(),
                List.of(),
                NewPlayerSpawnState.EMPTY
        );
        try (SqlStorage storage = sqlite("precision.db")) {
            storage.replaceAll(source, UUID.randomUUID());
            StorageSnapshot roundTripped = storage.exportSnapshot();

            assertEquals(
                    StorageChecksum.calculate(source),
                    StorageChecksum.calculate(roundTripped)
            );
            assertEquals(
                    Instant.parse("2026-07-30T12:00:00.123Z"),
                    roundTripped.profiles().getFirst().lastSeenAt()
            );
        }
    }

    private SqlStorage sqlite(String filename) {
        return new SqlStorage(
                new StorageConfiguration(
                        StorageBackend.SQLITE,
                        directory.resolve(filename),
                        "",
                        5432,
                        "",
                        "",
                        "",
                        false,
                        1,
                        5_000
                ),
                logger
        );
    }

    private static StorageSnapshot richSnapshot() {
        UUID playerId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID blockedId = UUID.randomUUID();
        UUID sharedId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-30T12:00:00Z");
        Home home = new Home(
                "Workshop",
                worldId,
                "world",
                12.25,
                72.0,
                -40.5,
                90.0f,
                4.0f,
                Material.CRAFTING_TABLE,
                "Main base",
                true,
                2,
                HomeCategory.BASE,
                HomeArrivalStyle.SPARKLE,
                Set.of(sharedId)
        );
        PlayerPreferences preferences = new PlayerPreferences(
                false, true, false, true, true, false, true, false, true, false,
                LanguagePreference.FINNISH
        );
        PlayerNotification notification = new PlayerNotification(
                UUID.randomUUID(),
                NotificationType.MAIL,
                now,
                actorId,
                "Alex",
                "Meet at spawn",
                true
        );
        ProfileSnapshot profile = new ProfileSnapshot(
                playerId,
                List.of(home),
                preferences,
                Set.of(OnboardingHint.HOME, OnboardingHint.LOCK_CONTROL),
                List.of(notification),
                "Emeli",
                now,
                72_000,
                Set.of(blockedId)
        );
        BlockKey block = new BlockKey(worldId, 12, 72, -41);
        ContainerLockSnapshot lock = new ContainerLockSnapshot(
                UUID.randomUUID(),
                playerId,
                Set.of(block),
                Set.of(sharedId),
                "Workshop",
                LockAccessMode.DEPOSIT_ONLY,
                true
        );
        DeathMarker death = new DeathMarker(
                playerId,
                worldId,
                "world",
                100.5,
                62.0,
                -30.5,
                now,
                now.plusSeconds(3_600),
                "SKELETON"
        );
        NewPlayerSpawnLocation available =
                new NewPlayerSpawnLocation(worldId, "world", 500, 80, 500, 45.0f);
        NewPlayerSpawnLocation assigned =
                new NewPlayerSpawnLocation(worldId, "world", -500, 75, -500, 180.0f);
        NewPlayerSpawnLocation retired =
                new NewPlayerSpawnLocation(worldId, "world", 750, 70, -750, 0.0f);
        NewPlayerSpawnState spawns = new NewPlayerSpawnState(
                List.of(available),
                Map.of(playerId, new NewPlayerSpawnAssignment(playerId, assigned, true)),
                List.of(retired),
                Set.of(playerId)
        );
        return new StorageSnapshot(
                List.of(profile),
                List.of(lock),
                List.of(death),
                spawns
        );
    }

    private static ContainerLockSnapshot lock(UUID id, BlockKey block) {
        return new ContainerLockSnapshot(
                id,
                UUID.randomUUID(),
                Set.of(block),
                Set.of(),
                "",
                LockAccessMode.TRUSTED,
                false
        );
    }
}

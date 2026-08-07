package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.object.ContainerLockSnapshot;
import gg.nurmi.survivaltweaks.object.DeathMarker;
import gg.nurmi.survivaltweaks.object.LockAccessMode;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnState;
import gg.nurmi.survivaltweaks.object.PlayerPreferences;
import gg.nurmi.survivaltweaks.object.ProfileSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlStorageScaleTest {

    private static final int PROFILE_COUNT = 2_000;
    private static final int LOCK_COUNT = 1_000;
    private static final int DEATH_MARKER_COUNT = 1_000;

    @TempDir
    Path directory;

    @Test
    void roundTripsAndVerifiesRepresentativeLargeDataset() {
        assertTimeout(Duration.ofSeconds(30), () -> {
            UUID worldId = UUID.randomUUID();
            Instant now = Instant.parse("2026-08-01T00:00:00Z");
            List<ProfileSnapshot> profiles = new ArrayList<>(PROFILE_COUNT);
            List<DeathMarker> deaths = new ArrayList<>(DEATH_MARKER_COUNT);
            for (int index = 0; index < PROFILE_COUNT; index++) {
                UUID playerId = new UUID(1, index + 1L);
                profiles.add(new ProfileSnapshot(
                        playerId,
                        List.of(),
                        PlayerPreferences.DEFAULTS,
                        Set.of(),
                        List.of(),
                        "Player" + index,
                        now.plusSeconds(index),
                        index * 20L,
                        Set.of(),
                        Set.of()
                ));
                if (index < DEATH_MARKER_COUNT) {
                    deaths.add(new DeathMarker(
                            playerId,
                            worldId,
                            "world",
                            index,
                            64,
                            -index,
                            now,
                            now.plus(Duration.ofDays(1)),
                            "FALL"
                    ));
                }
            }
            List<ContainerLockSnapshot> locks = new ArrayList<>(LOCK_COUNT);
            for (int index = 0; index < LOCK_COUNT; index++) {
                locks.add(new ContainerLockSnapshot(
                        new UUID(2, index + 1L),
                        new UUID(1, index + 1L),
                        Set.of(new BlockKey(worldId, index, 64, index)),
                        Set.of(),
                        "Lock " + index,
                        LockAccessMode.TRUSTED,
                        false
                ));
            }
            StorageSnapshot expected = new StorageSnapshot(
                    profiles,
                    locks,
                    deaths,
                    NewPlayerSpawnState.EMPTY
            );
            StorageConfiguration configuration = new StorageConfiguration(
                    StorageBackend.SQLITE,
                    directory.resolve("scale.db"),
                    "",
                    5432,
                    "",
                    "survivaltweaks",
                    "",
                    "",
                    false,
                    "disable",
                    1,
                    5_000,
                    30,
                    30
            );
            try (SqlStorage storage = new SqlStorage(
                    configuration,
                    Logger.getLogger(SqlStorageScaleTest.class.getName())
            )) {
                storage.replaceAll(expected, UUID.randomUUID());
                StorageSnapshot actual = storage.exportSnapshot();

                assertEquals(expected.counts(), actual.counts());
                assertEquals(
                        StorageChecksum.calculate(expected),
                        StorageChecksum.calculate(actual)
                );
                assertTrue(storage.verify().healthy());
            }
        });
    }
}

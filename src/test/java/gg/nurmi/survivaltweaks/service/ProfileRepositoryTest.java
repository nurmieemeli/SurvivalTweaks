package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.storage.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Logger;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileRepositoryTest {

    @TempDir
    Path directory;

    @Test
    void shutdownFlushesTheLatestSnapshotInOrder() {
        Logger logger = Logger.getLogger(ProfileRepositoryTest.class.getName());
        ProfileStore store = new ProfileStore(directory, logger);
        ProfileRepository repository = new ProfileRepository(store, logger);
        UUID uniqueId = UUID.randomUUID();
        Profile profile = repository.load(uniqueId);

        profile.addHome(new Home("home", UUID.randomUUID(), "world", 1, 2, 3, 4, 5));
        repository.save(profile);
        profile.addHome(new Home("kaivos", UUID.randomUUID(), "world", 6, 7, 8, 9, 10));
        repository.close();

        assertEquals(2, store.load(uniqueId).homes().size());
    }

    @Test
    void loadingLegacyDataQueuesItsUuidMigration() throws IOException {
        UUID uniqueId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        Files.writeString(directory.resolve(uniqueId + ".yml"), """
                home:
                  home:
                    world: world
                    x: 1
                    y: 2
                    z: 3
                    yaw: 4
                    pitch: 5
                """);
        Logger logger = Logger.getLogger(ProfileRepositoryTest.class.getName());
        ProfileStore store = new ProfileStore(directory, logger, name -> worldId);
        ProfileRepository repository = new ProfileRepository(store, logger);

        repository.load(uniqueId);
        repository.close();

        String migrated = Files.readString(directory.resolve(uniqueId + ".yml"));
        assertTrue(migrated.contains("schema-version: 6"));
        assertTrue(migrated.contains(worldId.toString()));
    }

    @Test
    void flushWaitsForQueuedSnapshotsWithoutClosingRepository() {
        Logger logger = Logger.getLogger(ProfileRepositoryTest.class.getName());
        ProfileStore store = new ProfileStore(directory, logger);
        ProfileRepository repository = new ProfileRepository(store, logger);
        UUID uniqueId = UUID.randomUUID();
        Profile profile = repository.load(uniqueId);
        profile.addHome(new Home("home", UUID.randomUUID(), "world", 1, 2, 3, 4, 5));
        repository.save(profile);

        assertTrue(repository.flush(Duration.ofSeconds(2)));
        assertEquals(1, store.load(uniqueId).homes().size());
        repository.close();
    }

    @Test
    void disconnectedProfilesAreSavedAndEvictedFromMemory() {
        Logger logger = Logger.getLogger(ProfileRepositoryTest.class.getName());
        ProfileStore store = new ProfileStore(directory, logger);
        ProfileRepository repository = new ProfileRepository(store, logger);
        UUID uniqueId = UUID.randomUUID();
        Profile profile = repository.load(uniqueId);
        profile.addHome(new Home("home", UUID.randomUUID(), "world", 1, 2, 3, 4, 5));

        repository.playerDisconnected(uniqueId);

        assertTrue(repository.flush(Duration.ofSeconds(2)));
        assertEquals(0, repository.cachedProfileCount());
        assertEquals(1, store.load(uniqueId).homes().size());
        repository.close();
    }

    @Test
    void periodicEvictionRetainsOnlineProfilesOnly() {
        Logger logger = Logger.getLogger(ProfileRepositoryTest.class.getName());
        ProfileRepository repository = new ProfileRepository(new ProfileStore(directory, logger), logger);
        UUID online = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        repository.load(online);
        repository.load(offline);

        repository.evictOffline(java.util.Set.of(online));

        assertTrue(repository.flush(Duration.ofSeconds(2)));
        assertEquals(1, repository.cachedProfileCount());
        repository.close();
    }
}

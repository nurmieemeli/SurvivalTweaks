package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.LanguagePreference;
import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.object.ProfileSnapshot;
import gg.nurmi.survivaltweaks.storage.ProfileStore;
import gg.nurmi.survivaltweaks.storage.ProfileDataStore;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileRepositoryTest {

    @TempDir
    Path directory;

    @Test
    void flushWaitsForAnEarlierFailedWriteWhileAnotherProfileCanCommit() throws Exception {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        ProfileStore store = spy(new ProfileStore(directory, logger));
        UUID blocked = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        AtomicBoolean fail = new AtomicBoolean(true);
        CountDownLatch attempted = new CountDownLatch(1);
        CountDownLatch otherSaved = new CountDownLatch(1);
        doAnswer(invocation -> {
            ProfileSnapshot snapshot = invocation.getArgument(0);
            if (snapshot.uniqueId().equals(blocked) && fail.get()) {
                attempted.countDown();
                throw new IOException("one profile is unavailable");
            }
            invocation.callRealMethod();
            if (snapshot.uniqueId().equals(other)) {
                otherSaved.countDown();
            }
            return null;
        }).when(store).save(any(ProfileSnapshot.class));

        try (ProfileRepository repository = new ProfileRepository(store, logger)) {
            try {
                Profile blockedProfile = repository.load(blocked);
                blockedProfile.lastKnownName("first attempt");
                repository.save(blockedProfile);
                assertTrue(attempted.await(5, TimeUnit.SECONDS));

                Profile otherProfile = repository.load(other);
                otherProfile.lastKnownName("other player");
                repository.save(otherProfile);
                assertTrue(otherSaved.await(5, TimeUnit.SECONDS));

                blockedProfile.lastKnownName("coalesced replacement");
                repository.save(blockedProfile);
                assertFalse(repository.flush(Duration.ofMillis(100)));
            } finally {
                fail.set(false);
            }
            assertTrue(repository.flush(Duration.ofSeconds(5)));
            assertEquals("coalesced replacement", store.load(blocked).lastKnownName());
        }
    }

    @Test
    void trustPreloadFailureStopsRepositoryConstruction() {
        ProfileDataStore store = new ProfileDataStore() {
            @Override
            public Profile load(UUID uniqueId) {
                return new Profile(uniqueId);
            }

            @Override
            public java.util.Map<UUID, java.util.Set<UUID>> loadAllGlobalTrusts()
                    throws IOException {
                throw new IOException("database unavailable");
            }

            @Override
            public void save(gg.nurmi.survivaltweaks.object.ProfileSnapshot snapshot) {
            }
        };

        assertThrows(
                IllegalStateException.class,
                () -> new ProfileRepository(store, Logger.getLogger("trust-preload-test"))
        );
    }

    @Test
    void languageBecomesActiveOnlyAfterSaveAndPreservesOtherPreferenceChanges() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        Logger logger = Logger.getLogger(ProfileRepositoryTest.class.getName());
        ProfileStore store = spy(new ProfileStore(directory, logger));
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch allowSave = new CountDownLatch(1);
        doAnswer(invocation -> {
            ProfileSnapshot snapshot = invocation.getArgument(0);
            assertEquals(LanguagePreference.FINNISH, snapshot.preferences().language());
            saving.countDown();
            assertTrue(allowSave.await(5, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(store).save(any(ProfileSnapshot.class));

        try (ProfileRepository repository = new ProfileRepository(store, logger)) {
            PlayerExperienceService experience = new PlayerExperienceService(repository);
            experience.prime(playerId);
            var saved = experience.cycleLanguage(playerId).orElseThrow();
            try {
                assertTrue(saving.await(5, TimeUnit.SECONDS));
                assertFalse(saved.isDone());
                assertEquals(LanguagePreference.AUTO, experience.preferences(player).language());
                assertTrue(experience.cycleLanguage(playerId).isEmpty());
                experience.update(player, preferences -> preferences.withSounds(false));
            } finally {
                allowSave.countDown();
            }
            saved.get(5, TimeUnit.SECONDS);
            assertEquals(LanguagePreference.FINNISH, experience.preferences(player).language());
            assertFalse(experience.preferences(player).soundsEnabled());
            assertTrue(repository.flush(Duration.ofSeconds(5)));
        }
        var reloaded = store.load(playerId).preferences();
        assertEquals(LanguagePreference.FINNISH, reloaded.language());
        assertFalse(reloaded.soundsEnabled());
    }

    @Test
    void recoveryRetriesWithoutChangingTheLiveLanguageUntilSaveSucceeds() throws Exception {
        UUID playerId = UUID.randomUUID();
        Logger logger = Logger.getLogger(ProfileRepositoryTest.class.getName());
        ProfileStore store = new ProfileStore(directory, logger);
        Profile profile = store.load(playerId);
        profile.preferences(profile.preferences().withSounds(false));
        store.save(profile.snapshot());
        Path recovery = directory.resolve("pending-languages.properties");
        Files.writeString(recovery, playerId + "=FINNISH\n");

        ProfileStore retrying = spy(store);
        AtomicBoolean locked = new AtomicBoolean(true);
        CountDownLatch attempted = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (locked.get()) {
                attempted.countDown();
                throw new IOException("database unavailable");
            }
            return invocation.callRealMethod();
        }).when(retrying).save(any(ProfileSnapshot.class));

        try (ProfileRepository repository = new ProfileRepository(retrying, logger, new PersistenceMonitor(), recovery)) {
            repository.retryPendingLanguages();
            try {
                assertTrue(attempted.await(5, TimeUnit.SECONDS));
                assertTrue(Files.isRegularFile(recovery));
                assertEquals(LanguagePreference.AUTO, repository.load(playerId).preferences().language());
                assertEquals(LanguagePreference.AUTO, store.load(playerId).preferences().language());
            } finally {
                locked.set(false);
            }
            assertTrue(repository.flush(Duration.ofSeconds(5)));
            var preferences = repository.load(playerId).preferences();
            assertEquals(LanguagePreference.FINNISH, preferences.language());
            assertFalse(preferences.soundsEnabled());
        }
        assertFalse(Files.exists(recovery));
        assertEquals(LanguagePreference.FINNISH, store.load(playerId).preferences().language());
    }

    @Test
    void recoveryRetriesWhenTheFileCannotBeRead() throws Exception {
        UUID playerId = UUID.randomUUID();
        Logger logger = mock(Logger.class);
        ProfileStore store = new ProfileStore(directory, logger);
        Path recovery = Files.createDirectory(directory.resolve("pending-languages.properties"));
        CountDownLatch readFailed = new CountDownLatch(1);
        CountDownLatch allowRetry = new CountDownLatch(1);
        doAnswer(invocation -> {
            readFailed.countDown();
            assertTrue(allowRetry.await(5, TimeUnit.SECONDS));
            return null;
        }).when(logger).log(
                eq(Level.WARNING), eq("Could not load pending language changes. Retrying."), any(IOException.class)
        );

        try (ProfileRepository repository = new ProfileRepository(store, logger, new PersistenceMonitor(), recovery)) {
            PlayerExperienceService experience = new PlayerExperienceService(repository);
            repository.retryPendingLanguages();
            try {
                assertTrue(readFailed.await(5, TimeUnit.SECONDS));
                assertEquals(LanguagePreference.AUTO, experience.preferences(playerId).language());
                assertTrue(experience.cycleLanguage(playerId).isEmpty());
                Files.delete(recovery);
                Files.writeString(recovery, playerId + "=FINNISH\n");
            } finally {
                allowRetry.countDown();
            }
            verify(logger, timeout(6_000)).info("Recovered pending language change for " + playerId + ".");
            assertTrue(repository.flush(Duration.ofSeconds(5)));
            assertEquals(LanguagePreference.FINNISH, experience.preferences(playerId).language());
            assertFalse(Files.exists(recovery));
        }
    }

    @Test
    void malformedRecoveryPreservesOriginalAndReplaysOtherEntries() throws Exception {
        Logger logger = Logger.getAnonymousLogger();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Path recovery = directory.resolve(ProfileRepository.PENDING_LANGUAGES_FILENAME);
        String damaged = first + "=FINNISH\n" + "broken=\\uZZZZ\n"
                + "invalid-uuid=FINNISH\n" + second + "=ENGLISH\n";
        Files.writeString(recovery, damaged);
        ProfileStore store = new ProfileStore(directory.resolve("profiles"), logger);

        try (ProfileRepository repository = new ProfileRepository(store, logger, new PersistenceMonitor(), recovery)) {
            repository.retryPendingLanguages();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (Files.exists(recovery) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(Files.exists(recovery));
            assertTrue(repository.languageRecoveryReady());
            assertEquals(LanguagePreference.FINNISH, store.load(first).preferences().language());
            assertEquals(LanguagePreference.ENGLISH, store.load(second).preferences().language());
            try (var files = Files.list(directory)) {
                Path original = files.filter(path -> path.getFileName().toString().startsWith("pending-languages-invalid-"))
                        .findFirst().orElseThrow();
                assertEquals(damaged, Files.readString(original));
            }
        }
    }

    @Test
    void loadingAProfileRepairsItsGlobalTrustIndex() {
        UUID ownerId = UUID.randomUUID();
        UUID trustedId = UUID.randomUUID();
        ProfileDataStore store = new ProfileDataStore() {
            @Override
            public Profile load(UUID uniqueId) {
                Profile profile = new Profile(uniqueId);
                profile.trustPlayer(trustedId);
                return profile;
            }

            @Override
            public java.util.Map<UUID, java.util.Set<UUID>> loadAllGlobalTrusts() {
                return java.util.Map.of();
            }

            @Override
            public void save(gg.nurmi.survivaltweaks.object.ProfileSnapshot snapshot) {
            }
        };
        ProfileRepository repository = new ProfileRepository(
                store,
                Logger.getLogger("trust-load-test")
        );

        repository.load(ownerId);

        assertTrue(repository.isTrusted(ownerId, trustedId));
        repository.close();
    }

    @Test
    void transientSaveFailureRetriesAfterAnIdenticalSaveRequest() throws InterruptedException {
        UUID uniqueId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch failSave = new CountDownLatch(1);
        ProfileDataStore store = new ProfileDataStore() {
            @Override
            public Profile load(UUID ignored) {
                return new Profile(uniqueId);
            }

            @Override
            public java.util.Map<UUID, java.util.Set<UUID>> loadAllGlobalTrusts() {
                return java.util.Map.of();
            }

            @Override
            public void save(gg.nurmi.survivaltweaks.object.ProfileSnapshot snapshot)
                    throws IOException {
                if (attempts.incrementAndGet() == 1) {
                    saving.countDown();
                    try {
                        assertTrue(failSave.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException(exception);
                    }
                    throw new IOException("temporary outage");
                }
            }
        };
        try (ProfileRepository repository = new ProfileRepository(
                store,
                Logger.getLogger(ProfileRepositoryTest.class.getName())
        )) {
            Profile profile = repository.load(uniqueId);
            profile.addHome(new Home("home", UUID.randomUUID(), "world", 1, 2, 3, 4, 5));
            repository.save(profile);
            try {
                assertTrue(saving.await(5, TimeUnit.SECONDS));
                repository.save(profile);
            } finally {
                failSave.countDown();
            }
            assertTrue(repository.flush(Duration.ofSeconds(3)));
        }
        assertEquals(2, attempts.get());
    }

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

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
import gg.nurmi.survivaltweaks.service.DeathRecoveryService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import gg.nurmi.survivaltweaks.service.PersistenceMonitor;
import gg.nurmi.survivaltweaks.service.PlayerExperienceService;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlStorageTest {

    private static final UUID FINNISH_PLAYER = new UUID(0, 1);
    private static final UUID ENGLISH_PLAYER = new UUID(0, 2);
    private static final String RECOVERY_DATABASE = "language-recovery.db";

    @TempDir
    Path directory;

    private final Logger logger = Logger.getLogger(SqlStorageTest.class.getName());

    @ParameterizedTest
    @ValueSource(strings = {"0", "2", "invalid"})
    void unsupportedSchemaIsRejectedBeforeChangingTheDatabase(String version) throws Exception {
        Path database = directory.resolve("unsupported.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE st_meta(meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL)");
            statement.execute("INSERT INTO st_meta VALUES('schema-version', '" + version + "')");
        }
        byte[] original = Files.readAllBytes(database);

        assertThrows(IllegalStateException.class, () -> sqlite("unsupported.db"));

        assertArrayEquals(original, Files.readAllBytes(database));
        for (String suffix : List.of("-wal", "-shm", "-journal")) {
            assertFalse(Files.exists(Path.of(database + suffix)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void conflictingSqliteRowsCannotBeVerifiedOrExported(boolean conflictingHomes) throws Exception {
        String file = "conflicting.db";
        UUID world = UUID.randomUUID();
        try (SqlStorage storage = sqlite(file)) {
            storage.ensureInstanceId(UUID.randomUUID());
            storage.save(new ProfileSnapshot(ENGLISH_PLAYER,
                    List.of(new Home("Mine", world, "world", 12, 65, 14, 0, 0))));
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve(file));
                 var statement = connection.createStatement()) {
                if (conflictingHomes) {
                    statement.executeUpdate("""
                            INSERT INTO st_homes
                            SELECT player_id, 'MINE', position + 1, 'mine', world_id, world_name, 55, y, z,
                                   yaw, pitch, icon, description, favorite, sort_order, category, arrival_style
                            FROM st_homes WHERE home_key = 'mine'
                            """);
                } else {
                    statement.executeUpdate("""
                            INSERT INTO st_spawn_locations VALUES
                                ('assignment', 0, '%s', 1, '%s', 'world', 64, 65, 14, 0),
                                ('assignment', 1, '%s', 0, '%s', 'world', 90, 65, 14, 0)
                            """.formatted(ENGLISH_PLAYER, world, ENGLISH_PLAYER, world));
                }
            }

            String reason = conflictingHomes ? "Conflicting home rows" : "Conflicting spawn assignments";
            var verification = storage.verify();
            assertFalse(verification.healthy());
            assertTrue(verification.problems().stream().anyMatch(problem -> problem.contains(reason)),
                    verification.problems().toString());
            assertTrue(assertThrows(IOException.class, storage::exportSnapshot).getMessage().contains(reason));
        }
    }

    @Test
    void forcedExitAfterOneLanguageCommitReplaysOnlyTheRemainingPlayer() throws Exception {
        Path recovery = directory.resolve(ProfileRepository.PENDING_LANGUAGES_FILENAME);
        Files.writeString(recovery, FINNISH_PLAYER + "=FINNISH\n" + ENGLISH_PLAYER + "=ENGLISH\n");
        try (SqlStorage storage = sqlite(RECOVERY_DATABASE);
             var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve(RECOVERY_DATABASE));
             var statement = connection.createStatement()) {
            storage.save(storage.load(FINNISH_PLAYER).snapshot());
            storage.save(storage.load(ENGLISH_PLAYER).snapshot());
            statement.executeUpdate("""
                    CREATE TRIGGER delay_english BEFORE UPDATE OF language ON st_profiles
                    WHEN NEW.player_id = '%s' AND NEW.language = 'ENGLISH'
                    BEGIN SELECT RAISE(ABORT, 'hold second recovery write'); END
                    """.formatted(ENGLISH_PLAYER));
        }

        Process interrupted = startLanguageRecovery("interrupt");
        try {
            assertTrue(await(() -> {
                String entries = Files.readString(recovery);
                return entries.contains(ENGLISH_PLAYER + "=ENGLISH") && !entries.contains(FINNISH_PLAYER.toString());
            }), () -> processLog("interrupt"));
            assertTrue(interrupted.isAlive(), () -> processLog("interrupt"));
        } finally {
            interrupted.destroyForcibly();
            assertTrue(interrupted.waitFor(10, TimeUnit.SECONDS));
        }

        try (SqlStorage storage = sqlite(RECOVERY_DATABASE);
             var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve(RECOVERY_DATABASE));
             var statement = connection.createStatement()) {
            assertEquals(LanguagePreference.FINNISH, storage.load(FINNISH_PLAYER).preferences().language());
            assertEquals(LanguagePreference.AUTO, storage.load(ENGLISH_PLAYER).preferences().language());
            statement.executeUpdate("DROP TRIGGER delay_english");
        }

        Process restarted = startLanguageRecovery("restart");
        try {
            assertTrue(restarted.waitFor(20, TimeUnit.SECONDS), () -> processLog("restart"));
            assertEquals(0, restarted.exitValue(), () -> processLog("restart"));
        } finally {
            if (restarted.isAlive()) {
                restarted.destroyForcibly();
                assertTrue(restarted.waitFor(10, TimeUnit.SECONDS));
            }
        }
        assertFalse(Files.exists(recovery));
        try (SqlStorage storage = sqlite(RECOVERY_DATABASE)) {
            assertEquals(LanguagePreference.FINNISH, storage.load(FINNISH_PLAYER).preferences().language());
            assertEquals(LanguagePreference.ENGLISH, storage.load(ENGLISH_PLAYER).preferences().language());
        }
    }

    private Process startLanguageRecovery(String phase) throws IOException {
        return new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                LanguageRecoveryProcess.class.getName(), directory.toString(), phase
        ).redirectErrorStream(true).redirectOutput(directory.resolve(phase + ".log").toFile()).start();
    }

    private String processLog(String phase) {
        try {
            return Files.readString(directory.resolve(phase + ".log"));
        } catch (IOException exception) {
            return exception.toString();
        }
    }

    private static boolean await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.call()) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    public static class LanguageRecoveryProcess {

        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            Path recovery = directory.resolve(ProfileRepository.PENDING_LANGUAGES_FILENAME);
            Logger logger = Logger.getAnonymousLogger();
            logger.setLevel(Level.OFF);
            try (SqlStorage storage = sqlite(directory.resolve(RECOVERY_DATABASE), logger);
                 ProfileRepository repository = new ProfileRepository(
                         storage, logger, new PersistenceMonitor(), recovery)) {
                repository.retryPendingLanguages(Runnable::run);
                if (args[1].equals("interrupt")) {
                    new CountDownLatch(1).await();
                }
                if (!await(() -> Files.notExists(recovery)) || !repository.flush(Duration.ofSeconds(5))) {
                    throw new IllegalStateException("Language recovery did not finish");
                }
                PlayerExperienceService experience = new PlayerExperienceService(repository);
                if (experience.preferences(FINNISH_PLAYER).language() != LanguagePreference.FINNISH
                        || experience.preferences(ENGLISH_PLAYER).language() != LanguagePreference.ENGLISH) {
                    throw new IllegalStateException("Wrong language after restart");
                }
            }
        }
    }

    @Test
    void refusesAFileSnapshotWhileAReaderPreventsTheWalCheckpoint() throws Exception {
        String filename = "busy-checkpoint.db";
        try (SqlStorage storage = sqlite(filename);
             var reader = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve(filename));
             var statement = reader.createStatement()) {
            UUID playerId = UUID.randomUUID();
            var profile = storage.load(playerId);
            profile.lastKnownName("before");
            storage.save(profile.snapshot());
            storage.checkpoint();
            reader.setAutoCommit(false);
            try (var result = statement.executeQuery("SELECT count(*) FROM st_profiles")) {
                assertTrue(result.next());
            }
            profile.lastKnownName("after");
            storage.save(profile.snapshot());

            Path database = directory.resolve(filename);
            assertThrows(SqlStorage.CheckpointUnavailableException.class,
                    () -> SqlStorage.checkpointBeforeStartupBackup(database));
            assertThrows(IOException.class, storage::acquireSnapshotLease);
            reader.rollback();
            SqlStorage.checkpointBeforeStartupBackup(database);
            try (var lease = storage.acquireSnapshotLease()) {
                assertEquals("after", storage.load(playerId).lastKnownName());
            }
        }
    }

    @Test
    void replacementOnlySpawnStateIsNotEmptyAndIsCounted() throws IOException {
        UUID playerId = UUID.randomUUID();
        try (SqlStorage storage = sqlite("replacement-only.db")) {
            storage.saveSpawnState(new NewPlayerSpawnState(
                    List.of(), Map.of(), List.of(), Set.of(playerId)
            ));

            assertFalse(storage.isEmpty());
            assertEquals(1, storage.exportSnapshot().counts().replacementSpawns());
        }
    }

    @Test
    void roundTripsEveryAggregateAndProducesAStablePortableSnapshot() throws IOException {
        UUID instanceId = UUID.randomUUID();
        StorageSnapshot expected = richSnapshot();
        try (SqlStorage source = sqlite("source.db");
             SqlStorage destination = sqlite("destination.db")) {
            source.replaceAll(expected, instanceId);

            StorageSnapshot exported = source.exportSnapshot();
            assertEquals(expected, exported);
            ProfileSnapshot exportedProfile = exported.profiles().getFirst();
            assertEquals(exportedProfile, source.load(exportedProfile.uniqueId()).snapshot());
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
            assertTrue(storage.verifyUninitialized().healthy());
        }
    }

    @Test
    void aggregateUpdatesPreserveUnchangedRowsAndApplyKeyLevelDeltas() throws IOException {
        UUID worldId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        ContainerLockSnapshot retained = lock(
                UUID.randomUUID(),
                new BlockKey(worldId, 1, 64, 1)
        );
        ContainerLockSnapshot removed = lock(
                UUID.randomUUID(),
                new BlockKey(worldId, 2, 64, 2)
        );
        ContainerLockSnapshot added = lock(
                UUID.randomUUID(),
                new BlockKey(worldId, 3, 64, 3)
        );
        Instant now = Instant.parse("2026-07-30T12:00:00Z");
        DeathMarker originalDeath = new DeathMarker(
                playerId, worldId, "world", 1, 64, 1, now,
                now.plusSeconds(600), "FALL"
        );
        DeathMarker changedDeath = new DeathMarker(
                playerId, worldId, "world", 5, 70, 5, now.plusSeconds(1),
                now.plusSeconds(1_200), "SKELETON"
        );
        NewPlayerSpawnLocation first =
                new NewPlayerSpawnLocation(worldId, "world", 100, 70, 100, 0);
        NewPlayerSpawnLocation second =
                new NewPlayerSpawnLocation(worldId, "world", 200, 75, 200, 90);

        try (SqlStorage storage = sqlite("delta.db")) {
            storage.saveLocks(List.of(retained, removed));
            storage.saveDeathMarkers(List.of(originalDeath));
            storage.saveSpawnState(new NewPlayerSpawnState(
                    List.of(first), Map.of(), List.of(), Set.of(playerId)
            ));

            storage.saveLocks(List.of(retained, added));
            storage.saveDeathMarkers(List.of(changedDeath));
            storage.saveSpawnState(new NewPlayerSpawnState(
                    List.of(second), Map.of(), List.of(first), Set.of()
            ));

            assertEquals(Set.of(retained, added), Set.copyOf(storage.loadLocks()));
            assertEquals(List.of(changedDeath), storage.loadDeathMarkers());
            assertEquals(
                    new NewPlayerSpawnState(
                            List.of(second), Map.of(), List.of(first), Set.of()
                    ),
                    storage.loadSpawnState()
            );
            assertTrue(storage.verifyUninitialized().healthy());
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
                Set.of(),
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

    @Test
    void checksumAndCountsIncludeProfileWideTrusts() {
        UUID playerId = UUID.randomUUID();
        ProfileSnapshot withoutTrust = new ProfileSnapshot(
                playerId,
                List.of(),
                PlayerPreferences.DEFAULTS,
                Set.of(),
                List.of(),
                "",
                null,
                0,
                Set.of(),
                Set.of()
        );
        ProfileSnapshot withTrust = new ProfileSnapshot(
                playerId,
                List.of(),
                PlayerPreferences.DEFAULTS,
                Set.of(),
                List.of(),
                "",
                null,
                0,
                Set.of(),
                Set.of(UUID.randomUUID())
        );
        StorageSnapshot left = new StorageSnapshot(
                List.of(withoutTrust), List.of(), List.of(), NewPlayerSpawnState.EMPTY
        );
        StorageSnapshot right = new StorageSnapshot(
                List.of(withTrust), List.of(), List.of(), NewPlayerSpawnState.EMPTY
        );

        assertNotEquals(left.counts(), right.counts());
        assertNotEquals(StorageChecksum.calculate(left), StorageChecksum.calculate(right));
        assertEquals(1, right.counts().profileTrusts());
    }

    @Test
    void maintenancePreviewsAndTransactionallyRemovesExpiredDeathMarkers() throws IOException {
        UUID playerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-01T12:00:00Z");
        DeathMarker expired = new DeathMarker(
                playerId, worldId, "world", 1, 64, 1,
                now.minusSeconds(600), now.minusSeconds(1), "FALL"
        );
        DeathMarker active = new DeathMarker(
                UUID.randomUUID(), worldId, "world", 2, 64, 2,
                now, now.plusSeconds(600), "FALL"
        );
        try (SqlStorage storage = sqlite("maintenance.db")) {
            storage.ensureInstanceId(UUID.randomUUID());
            storage.saveDeathMarkers(List.of(expired, active));

            assertEquals(1, storage.maintenancePreview(now, 0).expiredDeathMarkers());
            SqlStorage.MaintenanceResult result = storage.maintain(now, 0);

            assertEquals(1, result.removed().expiredDeathMarkers());
            assertEquals(List.of(active), storage.loadDeathMarkers());
            assertEquals(0, storage.maintenancePreview(now, 0).total());
            assertTrue(storage.verifyUninitialized().healthy());
        }
    }

    @Test
    void maintenanceReconcilesCachedProfilesOnlyAfterCommit() throws Exception {
        UUID playerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-09T12:00:00Z");
        PlayerNotification oldMail = new PlayerNotification(
                UUID.randomUUID(),
                NotificationType.MAIL,
                now.minus(Duration.ofDays(2)),
                null,
                "Alex",
                "Old mail",
                false
        );
        ProfileSnapshot snapshot = new ProfileSnapshot(
                playerId,
                List.of(),
                PlayerPreferences.DEFAULTS,
                Set.of(),
                List.of(oldMail),
                "Before",
                null,
                0,
                Set.of(),
                Set.of()
        );
        try (SqlStorage storage = sqlite("maintenance-cache.db")) {
            storage.ensureInstanceId(UUID.randomUUID());
            storage.save(snapshot);
            try (ProfileRepository profiles = new ProfileRepository(storage, logger)) {
                var cached = profiles.load(playerId);
                try (var connection = java.sql.DriverManager.getConnection(
                        "jdbc:sqlite:" + directory.resolve("maintenance-cache.db"));
                     var statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE st_profiles SET language = 'INVALID'");
                    assertThrows(IOException.class, () -> storage.maintain(now, 1));
                    assertEquals(List.of(oldMail), storage.load(playerId).notifications());
                    assertEquals(List.of(oldMail), cached.notifications());
                    statement.executeUpdate("UPDATE st_profiles SET language = 'AUTO'");
                }

                assertEquals(1, storage.maintain(now, 1).removed().oldMail());
                profiles.reconcileMaintenance(now, 1);
                assertTrue(storage.load(playerId).notifications().isEmpty());

                cached.lastKnownName("After");
                profiles.save(cached);
                assertTrue(profiles.flush(Duration.ofSeconds(2)));
                assertTrue(storage.load(playerId).notifications().isEmpty());
            }
        }
    }

    @Test
    void shutdownKeepsUnexpiredMarkersWithoutUndoingMaintenance() throws IOException {
        Instant now = Instant.parse("2026-07-28T12:00:00Z");
        Instant maintenanceAt = now.plusSeconds(60);
        UUID world = UUID.randomUUID();
        var expired = new DeathMarker(FINNISH_PLAYER, world, "world", 0, 64, 0, now, maintenanceAt, "FALL");
        var active = new DeathMarker(ENGLISH_PLAYER, world, "world", 0, 64, 0, now,
                maintenanceAt.plusSeconds(60), "FALL");
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(now);
        var plugin = mock(JavaPlugin.class);
        var server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(mock(BukkitScheduler.class));

        try (SqlStorage storage = sqlite("maintenance-shutdown.db")) {
            storage.ensureInstanceId(UUID.randomUUID());
            storage.saveDeathMarkers(List.of(expired, active));
            try (var recovery = new DeathRecoveryService(plugin, storage, null, null, clock,
                    null, null, null, null, null, null)) {
                when(clock.instant()).thenReturn(maintenanceAt);
                assertEquals(1, storage.maintain(maintenanceAt, 0).removed().total());
            }
            assertEquals(List.of(active), storage.loadDeathMarkers());
        }
    }

    @Test
    void shuttingDownBeforeCacheReconciliationDoesNotRestorePurgedMail() throws Exception {
        UUID playerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-09T12:00:00Z");
        try (SqlStorage storage = sqlite("maintenance-mail-shutdown.db")) {
            storage.ensureInstanceId(UUID.randomUUID());
            try (ProfileRepository profiles = new ProfileRepository(storage, logger)) {
                var older = new gg.nurmi.survivaltweaks.service.NotificationService(profiles,
                        Clock.fixed(now.minus(Duration.ofDays(2)), java.time.ZoneOffset.UTC));
                var current = new gg.nurmi.survivaltweaks.service.NotificationService(profiles,
                        Clock.fixed(now, java.time.ZoneOffset.UTC));
                assertTrue(older.mail(playerId, UUID.randomUUID(), "Alex", "Expired letter"));
                assertTrue(current.mail(playerId, UUID.randomUUID(), "Sam", "Recent letter"));
                assertTrue(profiles.flush(Duration.ofSeconds(2)));
                assertEquals(1, storage.maintain(now, 1).removed().oldMail());

                assertTrue(current.notify(playerId, NotificationType.DEATH_MARKER_EXPIRED, "", "world"));
            }
            var saved = storage.load(playerId).notifications();
            assertEquals(Set.of("Recent letter", "world"), saved.stream()
                    .map(PlayerNotification::detail).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void profileReadStartedBeforeMaintenanceCannotPublishPurgedMail() throws Exception {
        UUID playerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-09T12:00:00Z");
        var read = new CountDownLatch(1);
        var publish = new CountDownLatch(1);
        try (SqlStorage storage = sqlite("maintenance-in-flight-profile.db")) {
            storage.ensureInstanceId(UUID.randomUUID());
            var initial = storage.load(playerId);
            initial.addNotification(PlayerNotification.create(NotificationType.MAIL,
                    now.minus(Duration.ofDays(2)), UUID.randomUUID(), "Alex", "Expired"), 80);
            initial.addNotification(PlayerNotification.create(NotificationType.MAIL,
                    now, UUID.randomUUID(), "Sam", "Recent"), 80);
            storage.save(initial.snapshot());
            ProfileDataStore store = mock(ProfileDataStore.class, delegatesTo(storage));
            doAnswer(invocation -> {
                var loaded = storage.load(playerId);
                read.countDown();
                assertTrue(publish.await(5, TimeUnit.SECONDS));
                return loaded;
            }).when(store).load(playerId);

            try (var profiles = new ProfileRepository(store, logger)) {
                var late = profiles.loadAsync(playerId);
                try {
                    assertTrue(read.await(5, TimeUnit.SECONDS));
                    assertEquals(1, storage.maintain(now, 1).removed().oldMail());
                    profiles.reconcileMaintenance(now, 1);
                } finally {
                    publish.countDown();
                }
                assertEquals(List.of("Recent"), late.get(5, TimeUnit.SECONDS).notifications().stream()
                        .map(PlayerNotification::detail).toList());
            }
        }
    }

    private SqlStorage sqlite(String filename) {
        return sqlite(directory.resolve(filename), logger);
    }

    private static SqlStorage sqlite(Path database, Logger logger) {
        return new SqlStorage(
                new StorageConfiguration(
                        StorageBackend.SQLITE,
                        database,
                        "",
                        5432,
                        "",
                        "survivaltweaks",
                        "",
                        "",
                        false,
                        "disable",
                        "verify-identity",
                        1,
                        5_000,
                        30,
                        30
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
                Set.of(blockedId),
                Set.of(sharedId)
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

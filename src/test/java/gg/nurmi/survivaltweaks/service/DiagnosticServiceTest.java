package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.object.ContainerLockSnapshot;
import gg.nurmi.survivaltweaks.object.DeathMarker;
import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.ProfileSnapshot;
import gg.nurmi.survivaltweaks.storage.StorageManager;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosticServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-28T08:00:00Z");

    @TempDir
    Path dataFolder;

    @ParameterizedTest
    @ValueSource(strings = {"player-only: [wrong-type]\n", "admin: 42\n"})
    void matchingCatalogKeysCannotHideInvalidValueTypes(String catalog) throws IOException {
        copyResource("config.yml");
        try (var storage = openStorage();
             var service = new DiagnosticService(plugin(), Clock.fixed(NOW, ZoneOffset.UTC), null, null, storage, backups())) {
            Files.writeString(dataFolder.resolve("messages_en.yml"), catalog);
            Files.writeString(dataFolder.resolve("messages_fi.yml"), catalog);
            var report = service.inspect(new DiagnosticService.Snapshot(Set.of(), Set.of(), NOW));
            assertTrue(report.errors() > 0);
            assertTrue(report.issues().stream().anyMatch(issue ->
                    issue.detail().contains("Language catalog validation failed:")
                            && issue.detail().contains(catalog.startsWith("admin") ? "admin must be a section" : "player-only must be text")));
        }
    }

    @Test
    void catalogValidationStillReportsDifferentKeysAndMissingFiles() throws IOException {
        copyResource("config.yml");
        copyResource("messages_en.yml");
        copyResource("messages_fi.yml");
        Path english = dataFolder.resolve("messages_en.yml");
        var customized = YamlConfiguration.loadConfiguration(english.toFile());
        customized.set("custom-message", "Operator message");
        customized.save(english.toFile());
        try (var storage = openStorage();
             var service = new DiagnosticService(plugin(), Clock.fixed(NOW, ZoneOffset.UTC), null, null, storage, backups())) {
            var report = service.inspect(new DiagnosticService.Snapshot(Set.of(), Set.of(), NOW));
            assertTrue(report.issues().stream().anyMatch(issue -> issue.detail().contains("different message keys")));
            Files.delete(english);
            var missing = service.inspect(new DiagnosticService.Snapshot(Set.of(), Set.of(), NOW));
            assertTrue(missing.issues().stream().anyMatch(issue -> issue.detail().contains("language catalogs are missing")));
        }
    }

    @Test
    void healthyDataProducesACleanReport() throws IOException {
        copyResource("config.yml");
        copyResource("messages_en.yml");
        copyResource("messages_fi.yml");
        BackupService backups = backups();

        try (var storage = openStorage();
             var service = new DiagnosticService(plugin(), Clock.fixed(NOW, ZoneOffset.UTC), null, null, storage, backups)) {
            backups.snapshotLeaseFactory(() -> storage.acquireSnapshotLease()::close);
            backups.create("startup");
            var report = service.inspect(new DiagnosticService.Snapshot(Set.of(), Set.of(), NOW));
            assertTrue(report.healthy());
            assertEquals(1, report.backups());
            assertTrue(report.issues().isEmpty());
        }
    }

    @Test
    void reportsMissingWorldsAndExpiredMarkersInSqlite() throws IOException {
        copyResource("config.yml");
        copyResource("messages_en.yml");
        copyResource("messages_fi.yml");
        UUID playerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();

        try (var storage = openStorage();
             var service = new DiagnosticService(plugin(), Clock.fixed(NOW, ZoneOffset.UTC), null, null, storage, backups())) {
            storage.store().save(new ProfileSnapshot(playerId,
                    List.of(new Home("base", worldId, "missing_world", 0, 64, 0, 0, 0))));
            storage.store().saveLocks(List.of(new ContainerLockSnapshot(UUID.randomUUID(), playerId,
                    Set.of(new BlockKey(worldId, 1, 64, 1)), Set.of())));
            storage.store().saveDeathMarkers(List.of(new DeathMarker(playerId, worldId, "missing_world", 0, 64, 0,
                    NOW.minusSeconds(120), NOW.minusSeconds(60), "FALL")));

            var report = service.inspect(new DiagnosticService.Snapshot(Set.of(), Set.of(), NOW));

            assertEquals(0, report.errors());
            assertEquals(1, report.profiles());
            assertEquals(1, report.locks());
            assertEquals(1, report.deathMarkers());
            assertEquals(3L, report.issues().stream().filter(issue -> issue.detail().contains("unloaded world")).count());
            assertTrue(report.issues().stream().anyMatch(issue -> issue.detail().contains("Expired death marker")));
        }
    }

    @Test
    void malformedPasswordIsAbsentFromDoctorReport() throws IOException {
        copyResource("config.yml");
        copyResource("messages_en.yml");
        copyResource("messages_fi.yml");
        Path config = dataFolder.resolve("config.yml");
        String password = "fake-secret";
        Files.writeString(config, Files.readString(config).replaceFirst("(?m)^    password:.*$",
                "    password: \"" + password + "\" garbage"));

        try (var storage = openStorage();
             var service = new DiagnosticService(plugin(), Clock.fixed(NOW, ZoneOffset.UTC), null, null, storage, backups())) {
            var report = service.inspect(new DiagnosticService.Snapshot(Set.of(), Set.of(), NOW));
            assertTrue(report.issues().stream().anyMatch(issue -> issue.detail().contains("Could not parse config.yml at line")));
            assertFalse(report.issues().stream().anyMatch(issue -> issue.detail().contains(password)));
        }
    }

    @Test
    void reportsInvalidSqliteFilenameAndUnrestorableArchive() throws IOException {
        copyResource("config.yml");
        copyResource("messages_en.yml");
        copyResource("messages_fi.yml");
        BackupService backups = backups();
        String archive = backups.create("config-only").orElseThrow().getFileName().toString();
        Path config = dataFolder.resolve("config.yml");
        Files.writeString(config, Files.readString(config).replaceFirst("(?m)^    file:.*$", "    file: ../invalid.db"));

        try (var storage = openStorage();
             var service = new DiagnosticService(plugin(), Clock.fixed(NOW, ZoneOffset.UTC), null, null, storage, backups)) {
            var report = service.inspect(new DiagnosticService.Snapshot(Set.of(), Set.of(), NOW));
            assertEquals(2, report.errors());
            assertTrue(report.issues().stream().anyMatch(issue -> issue.detail().contains("storage.sqlite.file must be a .db filename")));
            assertTrue(report.issues().stream().anyMatch(issue -> issue.detail().contains("Backup " + archive + " is invalid")
                    && issue.detail().contains("no SQLite database or usable legacy player data")));

            Files.writeString(config, Files.readString(config).replace("../invalid.db", "older-copy.db"));
            var stale = service.inspect(new DiagnosticService.Snapshot(Set.of(), Set.of(), NOW));
            assertTrue(stale.issues().stream().anyMatch(issue -> issue.detail().contains("Keep the pinned SQLite filename")));
        }
    }

    @Test
    void closingCancelsQueuedScansAndSuppressesLateCallbacks() throws Exception {
        JavaPlugin plugin = plugin();
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getName()).thenReturn("SurvivalTweaks");
        when(plugin.namespace()).thenReturn("survivaltweaks");
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        doReturn(List.of()).when(server).getWorlds();
        doReturn(List.of()).when(server).getOnlinePlayers();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch blockerStarted = new CountDownLatch(1);
        executor.execute(() -> {
            blockerStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));

        DiagnosticService service = new DiagnosticService(
                plugin, Clock.systemUTC(), executor, null, null, mock(StorageManager.class), backups());
        assertTrue(service.run(report -> {
            throw new AssertionError("Closed diagnostics must not deliver a report");
        }));
        service.close();

        assertFalse(service.run(report -> {
        }));
        verify(scheduler, never()).runTask(
                org.mockito.ArgumentMatchers.eq(plugin),
                org.mockito.ArgumentMatchers.any(Runnable.class)
        );
    }

    private JavaPlugin plugin() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getResource("config.yml")).thenAnswer(ignored -> getClass().getResourceAsStream("/config.yml"));
        return plugin;
    }

    private BackupService backups() {
        return new BackupService(dataFolder, Clock.fixed(NOW, ZoneOffset.UTC), Logger.getAnonymousLogger());
    }

    private StorageManager openStorage() throws IOException {
        return StorageManager.open(new YamlConfiguration(), dataFolder, Logger.getAnonymousLogger(), name -> null);
    }

    private void copyResource(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/" + name)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing test resource " + name);
            }
            Files.copy(input, dataFolder.resolve(name));
        }
    }
}

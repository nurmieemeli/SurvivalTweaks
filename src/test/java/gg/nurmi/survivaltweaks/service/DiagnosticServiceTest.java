package gg.nurmi.survivaltweaks.service;

import org.bukkit.Server;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    void healthyDataProducesACleanReport() throws IOException {
        copyResource("config.yml");
        copyResource("messages_en.yml");
        copyResource("messages_fi.yml");
        new BackupService(
                dataFolder,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Logger.getAnonymousLogger()
        ).create("startup");

        DiagnosticService service = new DiagnosticService(
                dataFolder,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        DiagnosticService.Report report = service.inspect(new DiagnosticService.Snapshot(
                Set.of(),
                Set.of(),
                List.of(),
                NOW
        ));

        assertTrue(report.healthy());
        assertEquals(1, report.backups());
        assertTrue(report.issues().isEmpty());
    }

    @Test
    void reportsMigrationsOverlappingLocksExpiredMarkersAndStaleCompasses() throws IOException {
        copyResource("config.yml");
        copyResource("messages_en.yml");
        copyResource("messages_fi.yml");
        Files.createDirectories(dataFolder.resolve("userdata"));
        UUID playerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        Files.writeString(
                dataFolder.resolve("userdata/" + playerId + ".yml"),
                """
                schema-version: 3
                homes:
                  - name: legacy
                    world-uuid: "%s"
                    world: missing_world
                    x: 0.0
                    y: 64.0
                    z: 0.0
                    yaw: 0.0
                    pitch: 0.0
                """.formatted(worldId)
        );
        UUID firstLock = UUID.randomUUID();
        UUID secondLock = UUID.randomUUID();
        Files.writeString(
                dataFolder.resolve("locked-containers.yml"),
                """
                schema-version: 3
                locks:
                  - id: "%s"
                    owner: "%s"
                    blocks:
                      - { world: "%s", x: 1, y: 64, z: 1 }
                  - id: "%s"
                    owner: "%s"
                    blocks:
                      - { world: "%s", x: 1, y: 64, z: 1 }
                """.formatted(firstLock, playerId, worldId, secondLock, playerId, worldId)
        );
        String marker = NOW.minusSeconds(120).toString();
        Files.writeString(
                dataFolder.resolve("death-markers.yml"),
                """
                schema-version: 1
                markers:
                  - player: "%s"
                    world-uuid: "%s"
                    world: missing_world
                    x: 0.0
                    y: 64.0
                    z: 0.0
                    created-at: "%s"
                    expires-at: "%s"
                """.formatted(playerId, worldId, marker, NOW.minusSeconds(60))
        );
        Files.writeString(
                dataFolder.resolve("new-player-spawns.yml"),
                """
                schema-version: 2
                available:
                  - { world-uuid: "%s", world: missing_world, x: 5, y: 70, z: 5, yaw: 0.0 }
                assignments:
                  - player: "%s"
                    completed: true
                    location: { world-uuid: "%s", world: missing_world, x: 5, y: 70, z: 5, yaw: 0.0 }
                retired: []
                awaiting-replacement: []
                """.formatted(worldId, playerId, worldId)
        );

        DiagnosticService service = new DiagnosticService(
                dataFolder,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        DiagnosticService.Report report = service.inspect(new DiagnosticService.Snapshot(
                Set.of(),
                Set.of(),
                List.of(new DiagnosticService.CompassReference(playerId, playerId.toString(), marker)),
                NOW
        ));

        assertFalse(report.healthy());
        assertTrue(report.errors() >= 1);
        assertTrue(report.warnings() >= 5);
        assertEquals(1, report.profiles());
        assertEquals(2, report.locks());
        assertEquals(1, report.deathMarkers());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.detail().contains("overlap")));
        assertTrue(report.issues().stream().anyMatch(issue -> issue.detail().contains("stale")));
        assertTrue(report.issues().stream().anyMatch(issue -> issue.detail().contains("duplicates location")));
    }

    @Test
    void closingCancelsQueuedScansAndSuppressesLateCallbacks() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getName()).thenReturn("SurvivalTweaks");
        when(plugin.namespace()).thenReturn("survivaltweaks");
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
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

        DiagnosticService service = new DiagnosticService(plugin, Clock.systemUTC(), executor);
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

    private void copyResource(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/" + name)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing test resource " + name);
            }
            Files.copy(input, dataFolder.resolve(name));
        }
    }
}

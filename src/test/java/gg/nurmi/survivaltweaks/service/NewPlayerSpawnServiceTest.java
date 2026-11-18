package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnAssignment;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnLocation;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnState;
import gg.nurmi.survivaltweaks.storage.LegacyYamlFixtures.SpawnFiles;
import gg.nurmi.survivaltweaks.storage.NewPlayerSpawnDataStore;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewPlayerSpawnServiceTest {

    @TempDir
    Path temporary;

    @Test
    @SuppressWarnings("unchecked")
    void sharedChunkTicketStaysUntilTheLastPreparedSpawnIsAllocated() throws Exception {
        UUID worldId = UUID.randomUUID();
        var first = location(worldId, 2, 3);
        var second = location(worldId, 4, 5);
        var store = mock(NewPlayerSpawnDataStore.class);
        when(store.loadSpawnState()).thenReturn(new NewPlayerSpawnState(
                List.of(first, second), Map.of(), List.of(), Set.of()));
        var plugin = mock(JavaPlugin.class);
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        var world = mock(World.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getWorld(worldId)).thenReturn(world);
        when(server.getWorld("world")).thenReturn(world);
        when(server.getTPS()).thenReturn(new double[]{10});
        when(world.getName()).thenReturn("world");
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getChunkAtAsync(0, 0, true)).thenReturn(new CompletableFuture<>());
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        });
        var settings = mock(PluginSettings.class);
        when(settings.newPlayerSpawnEnabled()).thenReturn(true);
        when(settings.newPlayerSpawnWorld()).thenReturn("world");
        when(settings.newPlayerSpawnPreloadLocations()).thenReturn(2);
        when(settings.newPlayerSpawnMinimumTps()).thenReturn(20.0);

        try (var service = new NewPlayerSpawnService(plugin, store, mock(MessageService.class),
                mock(FeedbackService.class), new SettingsService(settings))) {
            var field = NewPlayerSpawnService.class.getDeclaredField("loaded");
            field.setAccessible(true);
            var loaded = (Set<Object>) field.get(service);
            var key = Class.forName(NewPlayerSpawnService.class.getName() + "$LocationKey")
                    .getDeclaredMethod("of", NewPlayerSpawnLocation.class);
            key.setAccessible(true);
            loaded.add(key.invoke(null, first));
            loaded.add(key.invoke(null, second));
            var firstPlayer = mock(Player.class);
            var playerData = mock(PersistentDataContainer.class);
            when(firstPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
            when(firstPlayer.isOnline()).thenReturn(true);
            when(firstPlayer.getPersistentDataContainer()).thenReturn(playerData);
            var secondPlayer = mock(Player.class);
            when(secondPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
            when(secondPlayer.isOnline()).thenReturn(true);
            when(secondPlayer.getPersistentDataContainer()).thenReturn(playerData);

            service.playerJoined(firstPlayer);
            verify(world, never()).removePluginChunkTicket(0, 0, plugin);
            assertEquals(1, service.status().ready());

            service.playerJoined(secondPlayer);
            verify(world).removePluginChunkTicket(0, 0, plugin);
            assertEquals(0, service.status().ready());
        }
    }

    @Test
    void disablingSpawnsInvalidatesPendingPlacementWithoutRetiringTheReservation() throws IOException {
        UUID worldId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        var destination = location(worldId, 8, 8);
        var reservation = new NewPlayerSpawnAssignment(playerId, destination, false);
        var store = mock(NewPlayerSpawnDataStore.class);
        when(store.loadSpawnState()).thenReturn(new NewPlayerSpawnState(
                List.of(), Map.of(playerId, reservation), List.of(), Set.of()));
        var plugin = mock(JavaPlugin.class);
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        var world = mock(World.class);
        var player = mock(Player.class);
        var playerData = mock(PersistentDataContainer.class);
        var chunk = new CompletableFuture<Chunk>();
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.isPrimaryThread()).thenReturn(true);
        when(server.getWorld(worldId)).thenReturn(world);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        });
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(player.getPersistentDataContainer()).thenReturn(playerData);
        when(world.getChunkAtAsync(0, 0, true)).thenReturn(chunk);
        when(world.getHighestBlockYAt(8, 8, HeightMap.MOTION_BLOCKING_NO_LEAVES)).thenReturn(68);
        var enabled = mock(PluginSettings.class);
        when(enabled.newPlayerSpawnEnabled()).thenReturn(true);
        var settings = new SettingsService(enabled);

        try (var service = new NewPlayerSpawnService(plugin, store,
                mock(MessageService.class), mock(FeedbackService.class), settings)) {
            service.playerJoined(player);
            verify(world).getChunkAtAsync(0, 0, true);
            settings.apply(mock(PluginSettings.class));
            service.reconfigure();
            chunk.complete(mock(Chunk.class));

            assertTrue(service.flush(java.time.Duration.ofSeconds(3)));
            verify(world, never()).getHighestBlockYAt(8, 8, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            verify(store, never()).saveSpawnState(any(NewPlayerSpawnState.class));
        }
    }

    @Test
    void lastSavedPlacementCompletesAnInterruptedReservationWithoutTeleporting() throws IOException {
        UUID worldId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        var destination = location(worldId, 8, 8);
        var pending = new NewPlayerSpawnAssignment(playerId, destination, false);
        var store = new SpawnFiles(temporary.resolve("placement.yml"), Logger.getAnonymousLogger());
        store.saveSpawnState(new NewPlayerSpawnState(List.of(), Map.of(playerId, pending), List.of(), Set.of()));
        var plugin = mock(JavaPlugin.class);
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        var player = mock(Player.class);
        var playerData = mock(PersistentDataContainer.class);
        var settings = mock(PluginSettings.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(settings.newPlayerSpawnEnabled()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPlayedBefore()).thenReturn(true);
        when(player.getPersistentDataContainer()).thenReturn(playerData);
        when(player.getRespawnLocation(false)).thenReturn(new Location(null, 120, 81, 120));
        when(playerData.get(new NamespacedKey("survivaltweaks", "first_join_placement"), PersistentDataType.STRING))
                .thenReturn(worldId + "/8/70/8");
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        });

        try (var service = new NewPlayerSpawnService(plugin, store, mock(MessageService.class),
                mock(FeedbackService.class), new SettingsService(settings))) {
            service.playerJoined(player);

            assertTrue(service.flush(java.time.Duration.ofSeconds(3)));
            assertEquals(pending.complete(), store.loadSpawnState().assignments().get(playerId));
            verify(server, never()).getWorld(worldId);
        }
    }

    @Test
    void completedAssignmentRepairsMissingNativeRespawnPointOnJoin() throws IOException {
        UUID worldId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        NewPlayerSpawnLocation assigned = location(worldId, 1_200, -800);
        SpawnFiles store = new SpawnFiles(
                temporary.resolve("respawn-repair.yml"),
                Logger.getAnonymousLogger()
        );
        store.save(new NewPlayerSpawnState(
                List.of(),
                Map.of(
                        playerId,
                        new NewPlayerSpawnAssignment(playerId, assigned, true)
                ),
                List.of(),
                Set.of()
        ));
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        World world = mock(World.class);
        Player player = mock(Player.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getWorld(worldId)).thenReturn(world);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return task;
        });
        when(player.hasPlayedBefore()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(player.getRespawnLocation(false)).thenReturn(null);
        NewPlayerSpawnService service = new NewPlayerSpawnService(
                plugin,
                store,
                mock(MessageService.class),
                mock(FeedbackService.class),
                new SettingsService(PluginSettings.validate(bundledConfig()))
        );

        service.playerJoined(player);

        verify(player).setRespawnLocation(
                new Location(world, 1_200.5, 70, -799.5, 0.0f, 0.0f),
                true
        );
        service.close();
    }

    @Test
    void playerWhoLeavesBeforeThePoolIsReadyCanResumeAfterRestart() {
        UUID playerId = UUID.randomUUID();
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        Player player = mock(Player.class);
        MessageService messages = mock(MessageService.class);
        FeedbackService feedback = mock(FeedbackService.class);
        SettingsService settings = new SettingsService(PluginSettings.validate(bundledConfig()));
        SpawnFiles store = new SpawnFiles(temporary.resolve("pool.yml"), Logger.getAnonymousLogger());
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(server.getTPS()).thenReturn(new double[]{10.0, 10.0, 10.0});
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return mock(BukkitTask.class);
        });
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        try (NewPlayerSpawnService service = new NewPlayerSpawnService(plugin, store, messages, feedback, settings)) {
            service.playerJoined(player);
            service.playerDisconnected(playerId);
        }

        when(player.hasPlayedBefore()).thenReturn(true);
        try (NewPlayerSpawnService restarted = new NewPlayerSpawnService(plugin, store, messages, feedback, settings)) {
            restarted.playerJoined(player);
            assertEquals(1, restarted.status().waitingPlayers());
        }
    }

    @Test
    void separationIncludesPreparedPendingAndCompletedLocations() throws IOException {
        UUID worldId = UUID.randomUUID();
        UUID otherWorldId = UUID.randomUUID();
        UUID pendingPlayer = UUID.randomUUID();
        UUID completedPlayer = UUID.randomUUID();
        NewPlayerSpawnLocation prepared = location(worldId, 0, 0);
        NewPlayerSpawnLocation pending = location(worldId, 1_000, 1_000);
        NewPlayerSpawnLocation completed = location(worldId, -1_000, -1_000);
        SpawnFiles store = new SpawnFiles(
                temporary.resolve("new-player-spawns.yml"),
                Logger.getAnonymousLogger()
        );
        NewPlayerSpawnLocation retired = location(worldId, 2_000, -2_000);
        store.save(new NewPlayerSpawnState(
                List.of(prepared),
                Map.of(
                        pendingPlayer,
                        new NewPlayerSpawnAssignment(pendingPlayer, pending, false),
                        completedPlayer,
                        new NewPlayerSpawnAssignment(completedPlayer, completed, true)
                ),
                List.of(retired),
                Set.of()
        ));
        NewPlayerSpawnService service = new NewPlayerSpawnService(
                mock(JavaPlugin.class),
                store,
                mock(MessageService.class),
                mock(FeedbackService.class),
                mock(SettingsService.class)
        );

        assertFalse(service.isFarEnough(location(worldId, 100, 100), 256));
        assertFalse(service.isFarEnough(location(worldId, 1_100, 1_100), 256));
        assertFalse(service.isFarEnough(location(worldId, -1_100, -1_100), 256));
        assertFalse(service.isFarEnough(location(worldId, 2_100, -2_100), 256));
        assertTrue(service.isFarEnough(location(worldId, 3_000, 3_000), 256));
        assertTrue(service.isFarEnough(location(otherWorldId, 0, 0), 256));
    }

    @Test
    void zeroMinimumSeparationAllowsNeighborsButNeverReusesCoordinates() throws IOException {
        UUID worldId = UUID.randomUUID();
        var prepared = location(worldId, 25, 25);
        var playerId = UUID.randomUUID();
        var reserved = location(worldId, 26, 25);
        var retired = location(worldId, 27, 25);
        var store = new SpawnFiles(temporary.resolve("zero-distance.yml"), Logger.getAnonymousLogger());
        store.save(new NewPlayerSpawnState(List.of(prepared),
                Map.of(playerId, new NewPlayerSpawnAssignment(playerId, reserved, false)), List.of(retired), Set.of()));
        try (var service = new NewPlayerSpawnService(mock(JavaPlugin.class), store, mock(MessageService.class),
                mock(FeedbackService.class), mock(SettingsService.class))) {
            assertFalse(service.isFarEnough(prepared, 0));
            assertFalse(service.isFarEnough(reserved, 0));
            assertFalse(service.isFarEnough(retired, 0));
            assertTrue(service.isFarEnough(location(worldId, 28, 25), 0));
            assertTrue(service.isFarEnough(location(UUID.randomUUID(), 25, 25), 0));
        }
    }

    @Test
    void manualRefillPausesBelowTheConfiguredTpsThreshold() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(server.getTPS()).thenReturn(new double[]{10.0, 10.0, 10.0});
        when(server.getScheduler()).thenReturn(scheduler);
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getName()).thenReturn("world");
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenReturn(task);
        when(task.isCancelled()).thenReturn(false);
        NewPlayerSpawnService service = new NewPlayerSpawnService(
                plugin,
                new SpawnFiles(
                        temporary.resolve("tps-new-player-spawns.yml"),
                        Logger.getAnonymousLogger()
                ),
                mock(MessageService.class),
                mock(FeedbackService.class),
                new SettingsService(PluginSettings.validate(bundledConfig()))
        );

        assertEquals(NewPlayerSpawnService.RefillResult.TPS_PAUSED, service.requestRefill());
        assertEquals(1, service.status().tpsPausesThisRun());
        assertEquals(10.0, service.status().tps());
    }

    private NewPlayerSpawnLocation location(UUID worldId, int x, int z) {
        return new NewPlayerSpawnLocation(worldId, "world", x, 70, z, 0.0f);
    }

    private YamlConfiguration bundledConfig() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"),
                StandardCharsets.UTF_8
        ));
    }
}

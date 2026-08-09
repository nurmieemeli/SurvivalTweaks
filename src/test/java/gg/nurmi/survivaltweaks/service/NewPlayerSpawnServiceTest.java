package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnAssignment;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnLocation;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnState;
import gg.nurmi.survivaltweaks.storage.NewPlayerSpawnStore;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewPlayerSpawnServiceTest {

    @TempDir
    Path temporary;

    @Test
    void completedAssignmentRepairsMissingNativeRespawnPointOnJoin() throws IOException {
        UUID worldId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        NewPlayerSpawnLocation assigned = location(worldId, 1_200, -800);
        NewPlayerSpawnStore store = new NewPlayerSpawnStore(
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
    void separationIncludesPreparedPendingAndCompletedLocations() throws IOException {
        UUID worldId = UUID.randomUUID();
        UUID otherWorldId = UUID.randomUUID();
        UUID pendingPlayer = UUID.randomUUID();
        UUID completedPlayer = UUID.randomUUID();
        NewPlayerSpawnLocation prepared = location(worldId, 0, 0);
        NewPlayerSpawnLocation pending = location(worldId, 1_000, 1_000);
        NewPlayerSpawnLocation completed = location(worldId, -1_000, -1_000);
        NewPlayerSpawnStore store = new NewPlayerSpawnStore(
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
                new NewPlayerSpawnStore(
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

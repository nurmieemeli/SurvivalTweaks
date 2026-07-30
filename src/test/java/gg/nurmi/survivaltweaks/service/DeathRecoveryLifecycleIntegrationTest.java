package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.storage.DeathMarkerStore;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeathRecoveryLifecycleIntegrationTest {

    @TempDir
    Path temporary;

    @Test
    void deathMarkerSurvivesRespawnRejoinShutdownAndServiceRestart() {
        UUID playerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("world");
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getSize()).thenReturn(0);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(new Location(world, 12.5, 70.0, -4.5));
        when(player.getInventory()).thenReturn(inventory);
        when(player.isOnline()).thenReturn(true);

        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask expiryTask = mock(BukkitTask.class);
        when(plugin.getName()).thenReturn("SurvivalTweaks");
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                .thenReturn(expiryTask);
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong()))
                .thenAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return mock(BukkitTask.class);
                });

        YamlConfiguration yaml = bundledConfig();
        yaml.set("death-recovery.floating-guide.enabled", false);
        SettingsService settings = new SettingsService(PluginSettings.validate(yaml));
        DeathMarkerStore store = new DeathMarkerStore(
                temporary.resolve("death-markers.yml"),
                Logger.getAnonymousLogger()
        );
        NotificationService notifications = mock(NotificationService.class);
        OnboardingService onboarding = mock(OnboardingService.class);
        DeathRecoveryService service = new DeathRecoveryService(
                plugin,
                store,
                mock(MessageService.class),
                settings,
                Clock.fixed(Instant.parse("2026-07-30T05:00:00Z"), ZoneOffset.UTC),
                mock(FeedbackService.class),
                notifications,
                onboarding
        );

        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(player);
        when(death.getDrops()).thenReturn(new ArrayList<>());
        service.onDeath(death);
        assertTrue(service.hasActiveMarker(playerId));

        PlayerRespawnEvent respawn = mock(PlayerRespawnEvent.class);
        when(respawn.getPlayer()).thenReturn(player);
        service.onRespawn(respawn);
        PlayerJoinEvent rejoin = mock(PlayerJoinEvent.class);
        when(rejoin.getPlayer()).thenReturn(player);
        service.onJoin(rejoin);
        service.close();

        verify(expiryTask).cancel();
        DeathRecoveryService restarted = new DeathRecoveryService(
                plugin,
                store,
                mock(MessageService.class),
                settings,
                Clock.fixed(Instant.parse("2026-07-30T05:01:00Z"), ZoneOffset.UTC),
                mock(FeedbackService.class),
                notifications,
                onboarding
        );
        assertTrue(restarted.hasActiveMarker(playerId));
        restarted.close();
    }

    private YamlConfiguration bundledConfig() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"),
                StandardCharsets.UTF_8
        ));
    }
}

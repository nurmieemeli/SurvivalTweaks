package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SafeTeleportServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T12:00:00Z");
    private static final Component SUCCESS = Component.text("teleported");

    private JavaPlugin plugin;
    private Server server;
    private BukkitScheduler scheduler;
    private MessageService messages;
    private FeedbackService feedback;
    private MutableClock clock;
    private Player player;
    private World originWorld;
    private Location origin;
    private BukkitTask warmupTask;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        server = mock(Server.class);
        scheduler = mock(BukkitScheduler.class);
        messages = mock(MessageService.class);
        feedback = mock(FeedbackService.class);
        clock = new MutableClock(NOW);
        player = mock(Player.class);
        originWorld = mock(World.class);
        origin = new Location(originWorld, 0.5, 64, 0.5);
        warmupTask = mock(BukkitTask.class);
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);

        UUID playerId = UUID.randomUUID();
        when(plugin.getServer()).thenReturn(server);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(logger);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.isPrimaryThread()).thenReturn(true);
        when(server.getPlayer(playerId)).thenReturn(player);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("TestPlayer");
        when(player.getLocation()).thenReturn(origin);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(SafeTeleportService.INSTANT_PERMISSION)).thenReturn(false);
        when(originWorld.getUID()).thenReturn(UUID.randomUUID());
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong()))
                .thenReturn(warmupTask);
    }

    @Test
    void successfulWarmupTeleportsAndStartsCooldown() {
        World destinationWorld = safeWorld();
        Location destination = new Location(destinationWorld, 100.5, 70, -20.5);
        when(player.teleportAsync(
                any(Location.class),
                any(PlayerTeleportEvent.TeleportCause.class)
        )).thenReturn(
                CompletableFuture.completedFuture(true)
        );
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ofSeconds(30), 0);
        assertTrue(service.findSafeDestination(destination).isPresent());

        assertTrue(service.begin(player, destination::clone, SUCCESS));
        assertTrue(service.isPending(player.getUniqueId()));
        verify(scheduler).runTaskLater(eq(plugin), any(Runnable.class), eq(60L));

        scheduledWarmup().run();

        assertFalse(service.isPending(player.getUniqueId()));
        verify(player).teleportAsync(
                any(Location.class),
                any(PlayerTeleportEvent.TeleportCause.class)
        );
        verify(player).sendMessage(SUCCESS);
        assertFalse(service.ensureAvailable(player));
        verify(messages).send(eq(player), eq("teleport.safety.cooldown"), any());

        clock.advance(Duration.ofSeconds(30));
        assertTrue(service.ensureAvailable(player));
    }

    @Test
    void movementAndDamageCancelWarmup() {
        SafeTeleportService movementService = service(Duration.ofSeconds(3), Duration.ZERO, 0);
        assertTrue(movementService.begin(player, origin::clone, SUCCESS));

        movementService.handleMove(player, new Location(originWorld, 1.5, 64, 0.5));

        assertFalse(movementService.isPending(player.getUniqueId()));
        verify(warmupTask).cancel();
        verify(messages).send(player, "teleport.safety.cancelled-move");

        SafeTeleportService damageService = service(Duration.ofSeconds(3), Duration.ZERO, 0);
        assertTrue(damageService.begin(player, origin::clone, SUCCESS));

        damageService.handleDamage(player);

        assertFalse(damageService.isPending(player.getUniqueId()));
        verify(messages).send(player, "teleport.safety.cancelled-damage");
    }

    @Test
    void unsafeDestinationDoesNotTeleport() {
        World unsafeWorld = unsafeWorld();
        Location destination = new Location(unsafeWorld, 10.5, 64, 10.5);
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 1);
        assertTrue(service.begin(player, destination::clone, SUCCESS));

        scheduledWarmup().run();

        assertFalse(service.isPending(player.getUniqueId()));
        verify(player, never()).teleportAsync(
                any(Location.class),
                any(PlayerTeleportEvent.TeleportCause.class)
        );
        verify(messages).send(player, "teleport.safety.unsafe");
    }

    @Test
    void unavailableDestinationDoesNotTeleport() {
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);
        assertTrue(service.begin(player, () -> null, SUCCESS));

        scheduledWarmup().run();

        assertFalse(service.isPending(player.getUniqueId()));
        verify(player, never()).teleportAsync(
                any(Location.class),
                any(PlayerTeleportEvent.TeleportCause.class)
        );
        verify(messages).send(player, "teleport.safety.unavailable");
    }

    @Test
    void destinationFailureClearsPendingState() {
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);
        assertTrue(service.begin(player, () -> {
            throw new IllegalStateException("destination failed");
        }, SUCCESS));

        scheduledWarmup().run();

        assertFalse(service.isPending(player.getUniqueId()));
        verify(player, never()).teleportAsync(
                any(Location.class),
                any(PlayerTeleportEvent.TeleportCause.class)
        );
        verify(messages).send(player, "teleport.safety.failed");
    }

    @Test
    void crossWorldDestinationIsUsed() {
        World destinationWorld = safeWorld();
        Location destination = new Location(destinationWorld, 8.5, 72, 9.5);
        when(player.teleportAsync(
                any(Location.class),
                any(PlayerTeleportEvent.TeleportCause.class)
        )).thenReturn(
                CompletableFuture.completedFuture(true)
        );
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);
        assertTrue(service.begin(player, destination::clone, SUCCESS));

        scheduledWarmup().run();

        ArgumentCaptor<Location> teleported = ArgumentCaptor.forClass(Location.class);
        verify(player).teleportAsync(
                teleported.capture(),
                any(PlayerTeleportEvent.TeleportCause.class)
        );
        assertSame(destinationWorld, teleported.getValue().getWorld());
    }

    @Test
    void cancellationDuringChunkLoadPreventsLateTeleport() {
        World destinationWorld = safeWorld();
        CompletableFuture<Chunk> chunkLoad = new CompletableFuture<>();
        when(destinationWorld.getChunkAtAsync(anyInt(), anyInt())).thenReturn(chunkLoad);
        Location destination = new Location(destinationWorld, 8.5, 72, 9.5);
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);
        assertTrue(service.begin(player, destination::clone, SUCCESS));

        scheduledWarmup().run();
        assertTrue(service.isPending(player.getUniqueId()));

        service.handleMove(player, new Location(originWorld, 1.5, 64, 0.5));
        chunkLoad.complete(mock(Chunk.class));

        assertFalse(service.isPending(player.getUniqueId()));
        verify(player, never()).teleportAsync(
                any(Location.class),
                any(PlayerTeleportEvent.TeleportCause.class)
        );
    }

    private SafeTeleportService service(Duration warmup, Duration cooldown, int searchRadius) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("teleport.warmup-seconds", warmup.toSeconds());
        config.set("teleport.cooldown-seconds", cooldown.toSeconds());
        config.set("teleport.safe-search-radius", searchRadius);
        config.set("ui.action-bar-enabled", false);
        return new SafeTeleportService(
                plugin,
                messages,
                feedback,
                clock,
                new SettingsService(PluginSettings.load(config, Logger.getAnonymousLogger())),
                mock(ActionBarService.class)
        );
    }

    private Runnable scheduledWarmup() {
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskLater(eq(plugin), action.capture(), anyLong());
        return action.getValue();
    }

    private World safeWorld() {
        World world = mock(World.class);
        WorldBorder border = mock(WorldBorder.class);
        Block feet = mock(Block.class);
        Block head = mock(Block.class);
        Block floor = mock(Block.class);

        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getWorldBorder()).thenReturn(border);
        when(border.isInside(any(Location.class))).thenReturn(true);
        when(world.getBlockAt(any(Location.class))).thenReturn(feet);
        when(world.getChunkAtAsync(anyInt(), anyInt())).thenReturn(
                CompletableFuture.completedFuture(mock(Chunk.class))
        );
        when(feet.isPassable()).thenReturn(true);
        when(feet.isLiquid()).thenReturn(false);
        when(feet.getType()).thenReturn(Material.AIR);
        when(feet.getRelative(0, 1, 0)).thenReturn(head);
        when(feet.getRelative(0, -1, 0)).thenReturn(floor);
        when(head.isPassable()).thenReturn(true);
        when(head.isLiquid()).thenReturn(false);
        when(head.getType()).thenReturn(Material.AIR);
        when(floor.isSolid()).thenReturn(true);
        when(floor.getType()).thenReturn(Material.STONE);
        return world;
    }

    private World unsafeWorld() {
        World world = mock(World.class);
        WorldBorder border = mock(WorldBorder.class);
        Block blocked = mock(Block.class);

        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getWorldBorder()).thenReturn(border);
        when(border.isInside(any(Location.class))).thenReturn(true);
        when(world.getBlockAt(any(Location.class))).thenReturn(blocked);
        when(world.getChunkAtAsync(anyInt(), anyInt())).thenReturn(
                CompletableFuture.completedFuture(mock(Chunk.class))
        );
        when(blocked.isPassable()).thenReturn(false);
        when(blocked.getType()).thenReturn(Material.STONE);
        return world;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

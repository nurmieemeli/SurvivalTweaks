package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.ui.SurvivalTweaksMenu;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import static gg.nurmi.survivaltweaks.service.SafeTeleportService.DEFAULT_LANDING_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private SettingsService settingsService;

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
        when(player.getPose()).thenReturn(Pose.STANDING);
        when(player.getWidth()).thenReturn(0.6);
        when(player.getHeight()).thenReturn(1.8);
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
        when(player.teleport(
                any(Location.class),
                any(PlayerTeleportEvent.TeleportCause.class)
        )).thenReturn(true);
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ofSeconds(30), 0);
        assertTrue(service.findSafeDestination(destination, 0, DEFAULT_LANDING_SIZE).isPresent());

        assertTrue(service.begin(player, destination::clone, SUCCESS));
        assertTrue(service.isPending(player.getUniqueId()));
        verify(scheduler).runTaskLater(eq(plugin), any(Runnable.class), eq(60L));

        scheduledWarmup().run();

        assertFalse(service.isPending(player.getUniqueId()));
        verify(player).teleport(
                any(Location.class),
                any(PlayerTeleportEvent.TeleportCause.class)
        );
        verify(player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
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
    void anOpenContainerPreventsTeleportingAtAll() {
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);
        service.containerOpened(player);

        assertFalse(service.begin(player, origin::clone, SUCCESS));

        assertFalse(service.isPending(player.getUniqueId()));
        verify(messages).send(player, "teleport.safety.container-open");
        verify(scheduler, never()).runTaskLater(eq(plugin), any(Runnable.class), anyLong());
    }

    @Test
    void aContainerAlreadyOpenWhenTheServiceStartsPreventsTeleporting() {
        InventoryView view = mock(InventoryView.class);
        Inventory inventory = mock(Inventory.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(inventory);

        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);

        assertFalse(service.begin(player, origin::clone, SUCCESS));
        verify(messages).send(player, "teleport.safety.container-open");
    }

    @Test
    void aSurvivalTweaksMenuDoesNotCountAsAnOpenContainer() {
        InventoryView view = mock(InventoryView.class);
        Inventory inventory = mock(Inventory.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(inventory);
        SurvivalTweaksMenu menu = mock(SurvivalTweaksMenu.class);
        when(inventory.getHolder(false)).thenReturn(menu);

        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);

        assertTrue(service.begin(player, origin::clone, SUCCESS));
        assertTrue(service.isPending(player.getUniqueId()));
    }

    @Test
    void closingTheContainerAllowsTeleportingAgain() {
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);
        service.containerOpened(player);
        assertFalse(service.begin(player, origin::clone, SUCCESS));

        service.containerClosed(player.getUniqueId());

        assertTrue(service.begin(player, origin::clone, SUCCESS));
        assertTrue(service.isPending(player.getUniqueId()));
    }

    @Test
    void openingAContainerDuringWarmupCancelsTheTeleport() {
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);
        assertTrue(service.begin(player, origin::clone, SUCCESS));

        service.containerOpened(player);

        assertFalse(service.isPending(player.getUniqueId()));
        verify(warmupTask).cancel();
        verify(messages).send(player, "teleport.safety.cancelled-inventory");
    }

    @Test
    void aContainerOpenedDuringWarmupStillBlocksTheCutover() {
        World destinationWorld = safeWorld();
        Location destination = new Location(destinationWorld, 100.5, 70, -20.5);
        SafeTeleportService service = service(
                Duration.ofSeconds(3),
                Duration.ZERO,
                0,
                false
        );
        assertTrue(service.begin(player, destination::clone, SUCCESS));

        service.containerOpened(player);
        enableContainerGuard();
        scheduledWarmup().run();

        assertFalse(service.isPending(player.getUniqueId()));
        verify(player, never()).teleport(
                any(Location.class),
                any(PlayerTeleportEvent.TeleportCause.class)
        );
        verify(messages).send(player, "teleport.safety.cancelled-inventory");
    }

    @Test
    void disconnectingForgetsAnOpenContainer() {
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);
        service.containerOpened(player);

        service.playerDisconnected(player.getUniqueId());

        assertFalse(service.hasContainerOpen(player.getUniqueId()));
        assertTrue(service.begin(player, origin::clone, SUCCESS));
    }

    @Test
    void theContainerGuardCanBeDisabled() {
        SafeTeleportService service = service(
                Duration.ofSeconds(3),
                Duration.ZERO,
                0,
                false
        );
        service.containerOpened(player);

        assertTrue(service.begin(player, origin::clone, SUCCESS));

        assertTrue(service.isPending(player.getUniqueId()));
        verify(messages, never()).send(player, "teleport.safety.container-open");
    }

    @Test
    void verticalSearchPrefersTheHeightNearestTheRequestedOne() {
        // Only three blocks down and one block up are stand-able.
        World world = layeredWorld(java.util.Set.of(67, 71));
        Location requested = new Location(world, 100.5, 70, -20.5);
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);

        Location resolved = service.findSafeDestination(requested, 0, DEFAULT_LANDING_SIZE).orElseThrow();

        // Scanning the vertical range from its bottom would have dropped the
        // player at 67, far below the block they actually asked for.
        assertEquals(71, resolved.getBlockY());
    }

    @Test
    void verticalSearchPrefersTheLowerCandidateAtEqualDistance() {
        World world = layeredWorld(java.util.Set.of(69, 71));
        Location requested = new Location(world, 100.5, 70, -20.5);
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);

        assertEquals(69, service.findSafeDestination(requested, 0, DEFAULT_LANDING_SIZE).orElseThrow().getBlockY());
    }

    @ParameterizedTest
    @EnumSource(value = Material.class, names = {"END_PORTAL", "END_GATEWAY", "KELP", "KELP_PLANT",
            "SEAGRASS", "TALL_SEAGRASS", "BUBBLE_COLUMN"})
    void passableHazardsAreNotSafeLandings(Material hazard) {
        World world = safeWorld();
        Location requested = new Location(world, 100.5, 70, -20.5);
        when(world.getBlockAt(requested).getType()).thenReturn(hazard);
        assertTrue(service(Duration.ofSeconds(3), Duration.ZERO, 0)
                .findSafeDestination(requested, 0, DEFAULT_LANDING_SIZE).isEmpty());
    }

    @Test
    void waterloggedHeadBlockIsNotASafeLanding() {
        World world = safeWorld();
        Location requested = new Location(world, 100.5, 70, -20.5);
        var waterlogged = mock(Waterlogged.class);
        when(waterlogged.isWaterlogged()).thenReturn(true);
        when(world.getBlockAt(requested).getRelative(0, 1, 0).getBlockData()).thenReturn(waterlogged);
        assertTrue(service(Duration.ofSeconds(3), Duration.ZERO, 0)
                .findSafeDestination(requested, 0, DEFAULT_LANDING_SIZE).isEmpty());
    }

    @Test
    void aLandingOverlappingLavaIsCenteredOnDryGround() {
        World world = safeWorld();
        Location requested = new Location(world, 100.9, 70, -20.5);
        Block lava = mock(Block.class);
        when(lava.isPassable()).thenReturn(true);
        when(lava.isLiquid()).thenReturn(true);
        when(world.getBlockAt(requested).getRelative(1, 0, 0)).thenReturn(lava);

        Location landing = service(Duration.ofSeconds(3), Duration.ZERO, 0)
                .findSafeDestination(requested, 0, DEFAULT_LANDING_SIZE).orElseThrow();

        assertEquals(100.5, landing.getX());
        assertEquals(70, landing.getBlockY());
    }

    @Test
    void anOpenTrapdoorOutsideTheFootprintCannotSupportALanding() {
        World world = safeWorld();
        Location requested = new Location(world, 100.5, 70, -20.5);
        Block floor = world.getBlockAt(requested).getRelative(0, -1, 0);
        when(floor.getType()).thenReturn(Material.OAK_TRAPDOOR);
        floorShape(floor, new BoundingBox(0, 0, 0.8125, 1, 1, 1));

        assertTrue(service(Duration.ofSeconds(3), Duration.ZERO, 0)
                .findSafeDestination(requested, 0, DEFAULT_LANDING_SIZE).isEmpty());
    }

    @Test
    void aRadiusReloadDoesNotExpandAnInFlightSearchBeyondItsLoadedChunks() {
        World world = unsafeWorld();
        Location requested = new Location(world, 15.5, 70, 8.5);
        CompletableFuture<Chunk> chunk = new CompletableFuture<>();
        when(world.getChunkAtAsync(0, 0)).thenReturn(chunk);
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);
        assertTrue(service.begin(player, requested::clone, SUCCESS));
        scheduledWarmup().run();
        verify(world).getChunkAtAsync(0, 0);

        YamlConfiguration config = new YamlConfiguration();
        config.set("teleport.safe-search-radius", 2);
        settingsService.apply(PluginSettings.load(config, Logger.getAnonymousLogger()));
        Block blocked = world.getBlockAt(requested);
        when(world.getBlockAt(any(Location.class))).thenAnswer(invocation -> {
            assertEquals(0, invocation.getArgument(0, Location.class).getBlockX() >> 4);
            return blocked;
        });

        chunk.complete(mock(Chunk.class));
        verify(messages).send(player, "teleport.safety.unsafe");
    }

    @Test
    void aPlayerGrowingDuringChunkLoadingPreloadsTheLargerFootprint() {
        World world = unsafeWorld();
        Location requested = new Location(world, 14.75, 70, 8.5);
        CompletableFuture<Chunk> initialChunk = new CompletableFuture<>();
        when(world.getChunkAtAsync(0, 0)).thenReturn(initialChunk);
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);
        assertTrue(service.begin(player, requested::clone, SUCCESS));
        scheduledWarmup().run();
        verify(world, never()).getChunkAtAsync(1, 0);

        when(player.getWidth()).thenReturn(3.0);
        when(player.getHeight()).thenReturn(9.0);
        initialChunk.complete(mock(Chunk.class));

        verify(world).getChunkAtAsync(1, 0);
        verify(messages).send(player, "teleport.safety.unsafe");
    }

    @Test
    void unsafeDestinationDoesNotTeleport() {
        World unsafeWorld = unsafeWorld();
        Location destination = new Location(unsafeWorld, 10.5, 64, 10.5);
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 1);
        assertTrue(service.begin(player, destination::clone, SUCCESS));

        scheduledWarmup().run();

        assertFalse(service.isPending(player.getUniqueId()));
        verify(player, never()).teleport(
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
        verify(player, never()).teleport(
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
        verify(player, never()).teleport(
                any(Location.class),
                any(PlayerTeleportEvent.TeleportCause.class)
        );
        verify(messages).send(player, "teleport.safety.failed");
    }

    @Test
    void crossWorldDestinationIsUsed() {
        World destinationWorld = safeWorld();
        Location destination = new Location(destinationWorld, 8.5, 72, 9.5);
        when(player.teleport(
                any(Location.class),
                any(PlayerTeleportEvent.TeleportCause.class)
        )).thenReturn(true);
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);
        assertTrue(service.begin(player, destination::clone, SUCCESS));

        scheduledWarmup().run();

        ArgumentCaptor<Location> teleported = ArgumentCaptor.forClass(Location.class);
        verify(player).teleport(
                teleported.capture(),
                any(PlayerTeleportEvent.TeleportCause.class)
        );
        assertSame(destinationWorld, teleported.getValue().getWorld());
    }

    @Test
    void retriesAtTheCurrentTargetBlockAndCancelsIfItMovesAgain() {
        World world = safeWorld();
        CompletableFuture<Chunk> firstChunk = new CompletableFuture<>();
        CompletableFuture<Chunk> secondChunk = new CompletableFuture<>();
        when(world.getChunkAtAsync(0, 0)).thenReturn(firstChunk);
        when(world.getChunkAtAsync(7, 0)).thenReturn(secondChunk);
        Location target = new Location(world, 8.5, 72, 9.5);
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);
        assertTrue(service.begin(player, () -> target, SUCCESS));
        scheduledWarmup().run();

        target.setX(112.5);
        firstChunk.complete(mock(Chunk.class));
        verify(world).getChunkAtAsync(7, 0);
        assertTrue(service.isPending(player.getUniqueId()));

        target.setX(160.5);
        secondChunk.complete(mock(Chunk.class));
        assertFalse(service.isPending(player.getUniqueId()));
        verify(world, never()).getChunkAtAsync(10, 0);
        verify(player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        verify(messages).send(player, "teleport.safety.destination-moved");
    }

    @Test
    void teleportsToTheNewTargetWhenItStopsMovingAfterOneChunkLoad() {
        World world = safeWorld();
        CompletableFuture<Chunk> firstChunk = new CompletableFuture<>();
        when(world.getChunkAtAsync(0, 0)).thenReturn(firstChunk);
        Location target = new Location(world, 8.5, 72, 9.5);
        when(player.teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class)))
                .thenReturn(true);
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);
        assertTrue(service.begin(player, target::clone, SUCCESS));
        scheduledWarmup().run();

        target.setX(112.5);
        firstChunk.complete(mock(Chunk.class));

        ArgumentCaptor<Location> destination = ArgumentCaptor.forClass(Location.class);
        verify(player).teleport(destination.capture(), any(PlayerTeleportEvent.TeleportCause.class));
        assertEquals(112.5, destination.getValue().getX());
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
        verify(player, never()).teleport(
                any(Location.class),
                any(PlayerTeleportEvent.TeleportCause.class)
        );
    }

    @Test
    void damageDuringChunkLoadingStillCancelsBeforeThePlayerMoves() {
        World world = safeWorld();
        CompletableFuture<Chunk> chunk = new CompletableFuture<>();
        when(world.getChunkAtAsync(0, 0)).thenReturn(chunk);
        SafeTeleportService service = service(Duration.ofSeconds(3), Duration.ZERO, 0);
        assertTrue(service.begin(player, () -> new Location(world, 8.5, 70, 8.5), SUCCESS));
        scheduledWarmup().run();
        assertTrue(service.isPending(player.getUniqueId()));

        service.handleDamage(player);
        chunk.complete(mock(Chunk.class));

        assertFalse(service.isPending(player.getUniqueId()));
        verify(player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        verify(messages).send(player, "teleport.safety.cancelled-damage");
    }

    private void enableContainerGuard() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("teleport.cancel-on-inventory-open", true);
        settingsService.apply(PluginSettings.load(config, Logger.getAnonymousLogger()));
    }

    private SafeTeleportService service(Duration warmup, Duration cooldown, int searchRadius) {
        return service(warmup, cooldown, searchRadius, true);
    }

    private SafeTeleportService service(
            Duration warmup,
            Duration cooldown,
            int searchRadius,
            boolean cancelOnInventoryOpen
    ) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("teleport.warmup-seconds", warmup.toSeconds());
        config.set("teleport.cooldown-seconds", cooldown.toSeconds());
        config.set("teleport.safe-search-radius", searchRadius);
        config.set("teleport.cancel-on-inventory-open", cancelOnInventoryOpen);
        config.set("ui.action-bar-enabled", false);
        settingsService = new SettingsService(
                PluginSettings.load(config, Logger.getAnonymousLogger())
        );
        return new SafeTeleportService(
                plugin,
                messages,
                feedback,
                clock,
                settingsService,
                mock(ActionBarService.class)
        );
    }

    private Runnable scheduledWarmup() {
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskLater(eq(plugin), action.capture(), anyLong());
        return action.getValue();
    }

    /** A world whose columns are only stand-able at the given Y levels. */
    private World layeredWorld(java.util.Set<Integer> standableLevels) {
        World world = mock(World.class);
        WorldBorder border = mock(WorldBorder.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getWorldBorder()).thenReturn(border);
        when(border.isInside(any(Location.class))).thenReturn(true);
        when(world.getChunkAtAsync(anyInt(), anyInt())).thenReturn(
                CompletableFuture.completedFuture(mock(Chunk.class))
        );
        when(world.getBlockAt(any(Location.class))).thenAnswer(invocation -> {
            Location queried = invocation.getArgument(0);
            return column(standableLevels.contains(queried.getBlockY()));
        });
        return world;
    }

    private Block column(boolean standable) {
        Block feet = mock(Block.class);
        Block head = mock(Block.class);
        Block floor = mock(Block.class);
        when(feet.isPassable()).thenReturn(standable);
        when(feet.isLiquid()).thenReturn(false);
        when(feet.getType()).thenReturn(Material.AIR);
        when(feet.getRelative(0, 1, 0)).thenReturn(head);
        when(feet.getRelative(0, -1, 0)).thenReturn(floor);
        when(head.isPassable()).thenReturn(true);
        when(head.isLiquid()).thenReturn(false);
        when(head.getType()).thenReturn(Material.AIR);
        when(floor.isSolid()).thenReturn(true);
        when(floor.getType()).thenReturn(Material.STONE);
        floorShape(floor, new BoundingBox(0, 0, 0, 1, 1, 1));
        return feet;
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
        floorShape(floor, new BoundingBox(0, 0, 0, 1, 1, 1));
        return world;
    }

    private void floorShape(Block floor, BoundingBox box) {
        VoxelShape shape = mock(VoxelShape.class);
        when(floor.getCollisionShape()).thenReturn(shape);
        when(shape.getBoundingBoxes()).thenReturn(java.util.List.of(box));
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

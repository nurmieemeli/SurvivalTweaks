package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnAssignment;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnLocation;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnState;
import gg.nurmi.survivaltweaks.storage.NewPlayerSpawnStore;
import net.kyori.adventure.title.Title;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NewPlayerSpawnService implements Listener, AutoCloseable {

    private static final long RETRY_DELAY_TICKS = 40L;
    private static final long GENERATION_RETRY_DELAY_TICKS = 200L;
    private static final int MAX_TELEPORT_ATTEMPTS = 3;
    private static final Set<Material> DANGEROUS_GROUND = Set.of(
            Material.LAVA,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.CACTUS,
            Material.MAGMA_BLOCK,
            Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH,
            Material.POWDER_SNOW,
            Material.WITHER_ROSE,
            Material.NETHER_PORTAL,
            Material.END_PORTAL,
            Material.END_GATEWAY
    );

    private final JavaPlugin plugin;
    private final NewPlayerSpawnStore store;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final SettingsService settings;
    private final TickWorkBudget workBudget;
    private final TaskFailureIsolation failures;
    private final CoalescingSnapshotWriter<NewPlayerSpawnState> writer;
    private final List<NewPlayerSpawnLocation> available = new ArrayList<>();
    private final Map<UUID, NewPlayerSpawnAssignment> assignments = new LinkedHashMap<>();
    private final List<NewPlayerSpawnLocation> retired = new ArrayList<>();
    private final Set<UUID> awaitingReplacement = new HashSet<>();
    private final Map<Rejection, Long> rejections = new EnumMap<>(Rejection.class);
    private final Set<LocationKey> loaded = new HashSet<>();
    private final Set<UUID> waitingPlayers = new HashSet<>();
    private final Set<UUID> teleportingPlayers = new HashSet<>();
    private final Map<UUID, Integer> teleportAttempts = new HashMap<>();
    private boolean generationInProgress;
    private int failedSurfaceAttempts;
    private long generationVersion;
    private boolean closed;
    private boolean warnedMissingWorld;
    private boolean poolReadyLogged;
    private boolean validationInProgress;
    private long generatedLocations;
    private long replacementAssignments;
    private long tpsPauses;
    private BukkitTask scheduledRefill;

    public NewPlayerSpawnService(
            JavaPlugin plugin,
            NewPlayerSpawnStore store,
            MessageService messages,
            FeedbackService feedback,
            SettingsService settings
    ) {
        this(plugin, store, messages, feedback, settings, null, null);
    }

    public NewPlayerSpawnService(
            JavaPlugin plugin,
            NewPlayerSpawnStore store,
            MessageService messages,
            FeedbackService feedback,
            SettingsService settings,
            TickWorkBudget workBudget,
            TaskFailureIsolation failures
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.store = Objects.requireNonNull(store, "store");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.workBudget = workBudget;
        this.failures = failures;
        this.writer = new CoalescingSnapshotWriter<>(
                "SurvivalTweaks-new-player-spawn-writer",
                "new-player spawn state",
                store::save,
                plugin.getLogger() == null
                        ? Logger.getLogger(NewPlayerSpawnService.class.getName())
                        : plugin.getLogger()
        );
        NewPlayerSpawnState state = store.load();
        available.addAll(state.available());
        assignments.putAll(state.assignments());
        retired.addAll(state.retired());
        awaitingReplacement.addAll(state.awaitingReplacement());
        Objects.requireNonNull(PoolStatus.class.getName());
        Objects.requireNonNull(RefillResult.class.getName());
        Objects.requireNonNull(ClearResult.class.getName());
        Objects.requireNonNull(ValidationReport.class.getName());
        Objects.requireNonNull(Rejection.class.getName());
        Objects.requireNonNull(LocationKey.class.getName());
    }

    public void start() {
        plugin.getServer().getScheduler().runTask(plugin, this::refresh);
    }

    public void playerJoined(Player player) {
        boolean newPlayer = !player.hasPlayedBefore();
        UUID playerId = player.getUniqueId();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (closed || !player.isOnline() || !settings.current().newPlayerSpawnEnabled()) {
                return;
            }
            NewPlayerSpawnAssignment existing = assignments.get(playerId);
            if (existing != null) {
                if (!existing.completed()) {
                    teleportReserved(player, existing);
                }
                return;
            }
            if (!newPlayer && !awaitingReplacement.contains(playerId)) {
                return;
            }
            waitingPlayers.add(playerId);
            if (!allocate(player)) {
                messages.send(player, "new-player-spawn.preparing");
                refill();
            }
        });
    }

    public void playerDisconnected(UUID playerId) {
        waitingPlayers.remove(playerId);
        teleportingPlayers.remove(playerId);
        teleportAttempts.remove(playerId);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!settings.current().newPlayerSpawnEnabled()) {
            return;
        }
        if (event.isBedSpawn() || event.isAnchorSpawn()) {
            return;
        }
        Player player = event.getPlayer();
        NewPlayerSpawnAssignment assignment = assignments.get(player.getUniqueId());
        if (assignment == null) {
            return;
        }
        resolveWorld(assignment.location()).ifPresent(world -> {
            Location loc = findSafeRespawnLocation(world, assignment.location());
            if (loc != null) {
                event.setRespawnLocation(loc);
            }
        });
    }

    private Location findSafeRespawnLocation(World world, NewPlayerSpawnLocation spawnLoc) {
        Location base = spawnLoc.toLocation(world);
        if (isSafeSpawnLocation(world, base.getBlockX(), base.getBlockY(), base.getBlockZ())) {
            return base;
        }
        for (int r = 1; r <= 16; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    int x = spawnLoc.x() + dx;
                    int z = spawnLoc.z() + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                    if (isSafeSpawnLocation(world, x, y + 1, z)) {
                        return new Location(
                                world,
                                x + 0.5,
                                y + 1,
                                z + 0.5,
                                spawnLoc.yaw(),
                                0.0f
                        );
                    }
                }
            }
        }
        return base;
    }

    private boolean isSafeSpawnLocation(World world, int x, int y, int z) {
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block ground = world.getBlockAt(x, y - 1, z);
        return feet.isPassable() && head.isPassable() && ground.getType().isSolid() && !DANGEROUS_GROUND.contains(ground.getType());
    }

    public void reconfigure() {
        if (closed) {
            return;
        }
        generationVersion++;
        generationInProgress = false;
        failedSurfaceAttempts = 0;
        poolReadyLogged = false;
        cancelScheduledRefill();
        releaseTickets();
        loaded.clear();
        refresh();
    }

    public int availableLocations() {
        return available.size();
    }

    public int completedAssignments() {
        return Math.toIntExact(assignments.values().stream()
                .filter(NewPlayerSpawnAssignment::completed)
                .count());
    }

    public PoolStatus status() {
        PluginSettings current = settings.current();
        int ready = Math.toIntExact(available.stream()
                .filter(location -> loaded.contains(LocationKey.of(location)))
                .count());
        int pending = awaitingReplacement.size() + Math.toIntExact(assignments.values().stream()
                .filter(assignment -> !assignment.completed())
                .count());
        return new PoolStatus(
                current.newPlayerSpawnEnabled(),
                configuredWorld().map(World::getName).orElse(current.newPlayerSpawnWorld()),
                available.size(),
                ready,
                current.newPlayerSpawnPreloadLocations(),
                pending,
                completedAssignments(),
                retired.size(),
                waitingPlayers.size(),
                generationInProgress,
                validationInProgress,
                currentTps(),
                generatedLocations,
                replacementAssignments,
                tpsPauses,
                Map.copyOf(rejections)
        );
    }

    public RefillResult requestRefill() {
        PluginSettings current = settings.current();
        if (!current.newPlayerSpawnEnabled()) {
            return RefillResult.DISABLED;
        }
        if (available.size() >= current.newPlayerSpawnPreloadLocations()) {
            return RefillResult.FULL;
        }
        if (generationInProgress) {
            return RefillResult.ALREADY_RUNNING;
        }
        if (configuredWorld().isEmpty()) {
            return RefillResult.WORLD_UNAVAILABLE;
        }
        if (currentTps() < current.newPlayerSpawnMinimumTps()) {
            refill();
            return RefillResult.TPS_PAUSED;
        }
        cancelScheduledRefill();
        refill();
        return RefillResult.STARTED;
    }

    public ClearResult clearPrepared() {
        List<NewPlayerSpawnLocation> clearedLocations = List.copyOf(available);
        int cleared = clearedLocations.size();
        generationVersion++;
        generationInProgress = false;
        failedSurfaceAttempts = 0;
        poolReadyLogged = false;
        cancelScheduledRefill();
        releaseTickets();
        available.clear();
        loaded.clear();
        if (!saveState()) {
            available.addAll(clearedLocations);
            refresh();
            return new ClearResult(0, false);
        }
        refill();
        return new ClearResult(cleared, true);
    }

    public boolean validate(Consumer<ValidationReport> completion) {
        Objects.requireNonNull(completion, "completion");
        if (validationInProgress) {
            return false;
        }
        validationInProgress = true;
        long version = generationVersion;
        List<ValidationTarget> targets = new ArrayList<>();
        available.forEach(location -> targets.add(new ValidationTarget(location, true)));
        assignments.values().stream()
                .filter(assignment -> !assignment.completed())
                .forEach(assignment -> targets.add(new ValidationTarget(assignment.location(), false)));
        List<CompletableFuture<LoadedValidationTarget>> loads = targets.stream()
                .map(target -> {
                    World world = resolveWorld(target.location()).orElse(null);
                    if (world == null) {
                        return CompletableFuture.completedFuture(
                                new LoadedValidationTarget(target, null, null)
                        );
                    }
                    return loadLandingChunks(
                            world,
                            target.location().x(),
                            target.location().z(),
                            settings.current().newPlayerSpawnLandingRadius()
                    ).handle((ignored, error) -> new LoadedValidationTarget(target, world, error));
                })
                .toList();
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, aggregateError) -> runNextTick(() -> {
                    validationInProgress = false;
                    if (version != generationVersion) {
                        completion.accept(new ValidationReport(
                                targets.size(),
                                0,
                                0,
                                0,
                                aggregateError == null ? 0 : 1,
                                true
                        ));
                        return;
                    }
                    int removed = 0;
                    int invalidPending = 0;
                    int unavailable = 0;
                    int errors = aggregateError == null ? 0 : 1;
                    List<NewPlayerSpawnLocation> removedLocations = new ArrayList<>();
                    PluginSettings current = settings.current();
                    for (CompletableFuture<LoadedValidationTarget> load : loads) {
                        LoadedValidationTarget loadedTarget = load.join();
                        if (loadedTarget.world() == null) {
                            unavailable++;
                            continue;
                        }
                        if (loadedTarget.error() != null) {
                            errors++;
                            continue;
                        }
                        Rejection rejection = validateLanding(
                                loadedTarget.world(),
                                loadedTarget.target().location(),
                                current
                        );
                        if (rejection == null) {
                            continue;
                        }
                        rejected(rejection);
                        if (loadedTarget.target().prepared()
                                && available.remove(loadedTarget.target().location())) {
                            releaseTicket(loadedTarget.target().location());
                            retired.add(loadedTarget.target().location());
                            removedLocations.add(loadedTarget.target().location());
                            removed++;
                        } else if (!loadedTarget.target().prepared()) {
                            invalidPending++;
                        }
                    }
                    if (removed > 0) {
                        if (saveState()) {
                            refill();
                        } else {
                            retired.removeAll(removedLocations);
                            available.addAll(removedLocations);
                            removed = 0;
                            errors++;
                            refresh();
                        }
                    }
                    completion.accept(new ValidationReport(
                            targets.size(),
                            removed,
                            invalidPending,
                            unavailable,
                            errors,
                            false
                    ));
                }));
        return true;
    }

    private void refresh() {
        if (closed || !settings.current().newPlayerSpawnEnabled()) {
            return;
        }
        Optional<World> configuredWorld = configuredWorld();
        if (configuredWorld.isEmpty()) {
            scheduleRefillRetry();
            return;
        }
        World world = configuredWorld.orElseThrow();
        warnedMissingWorld = false;
        PluginSettings current = settings.current();
        List<NewPlayerSpawnLocation> stale = available.stream()
                .filter(location -> !isCurrentPreparedLocation(location, world, current))
                .toList();
        if (!stale.isEmpty()) {
            available.removeAll(stale);
            saveState();
        }
        if (available.size() > current.newPlayerSpawnPreloadLocations()) {
            List<NewPlayerSpawnLocation> surplus = List.copyOf(
                    available.subList(current.newPlayerSpawnPreloadLocations(), available.size())
            );
            surplus.forEach(this::releaseTicket);
            available.removeAll(surplus);
            saveState();
        }
        long version = generationVersion;
        for (NewPlayerSpawnLocation location : List.copyOf(available)) {
            preloadExisting(world, location, version);
        }
        refill();
    }

    private void preloadExisting(World world, NewPlayerSpawnLocation location, long version) {
        LocationKey key = LocationKey.of(location);
        if (loaded.contains(key)) {
            return;
        }
        loadLandingChunks(world, location.x(), location.z(), settings.current().newPlayerSpawnLandingRadius())
                .whenComplete((ignored, error) -> runOnMainThread(() -> {
                    if (closed || version != generationVersion || !available.contains(location)) {
                        return;
                    }
                    if (error != null) {
                        plugin.getLogger().log(
                                Level.WARNING,
                                "Could not preload a new-player spawn chunk at "
                                        + location.x() + ", " + location.z(),
                                error
                        );
                        return;
                    }
                    Rejection rejection = validateLanding(world, location, settings.current());
                    if (rejection != null) {
                        rejected(rejection);
                        available.remove(location);
                        retired.add(location);
                        saveState();
                        refill();
                        return;
                    }
                    world.addPluginChunkTicket(location.x() >> 4, location.z() >> 4, plugin);
                    loaded.add(key);
                    serveWaitingPlayers();
                    announcePoolReady();
                }));
    }

    private boolean allocate(Player player) {
        NewPlayerSpawnLocation location = available.stream()
                .filter(candidate -> loaded.contains(LocationKey.of(candidate)))
                .findFirst()
                .orElse(null);
        if (location == null) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        NewPlayerSpawnAssignment assignment = new NewPlayerSpawnAssignment(playerId, location, false);
        boolean replacement = awaitingReplacement.remove(playerId);
        available.remove(location);
        poolReadyLogged = false;
        assignments.put(playerId, assignment);
        if (!saveState()) {
            assignments.remove(playerId);
            if (replacement) {
                awaitingReplacement.add(playerId);
            }
            available.add(0, location);
            return false;
        }
        releaseTicket(location);
        waitingPlayers.remove(playerId);
        teleportReserved(player, assignment);
        refill();
        return true;
    }

    private void teleportReserved(Player player, NewPlayerSpawnAssignment assignment) {
        UUID playerId = player.getUniqueId();
        if (!player.isOnline() || !teleportingPlayers.add(playerId)) {
            return;
        }
        World world = resolveWorld(assignment.location()).orElse(null);
        if (world == null) {
            teleportingPlayers.remove(playerId);
            plugin.getLogger().warning(
                    "Replacing the reserved spawn for new player " + playerId
                            + " because its world is unavailable."
            );
            replaceReservation(player, assignment);
            return;
        }
        NewPlayerSpawnLocation destination = assignment.location();
        loadLandingChunks(
                world,
                destination.x(),
                destination.z(),
                settings.current().newPlayerSpawnLandingRadius()
        ).whenComplete((ignored, loadError) -> runOnMainThread(() -> {
                    if (closed || !player.isOnline()) {
                        teleportingPlayers.remove(playerId);
                        return;
                    }
                    if (loadError != null) {
                        failTeleport(player, assignment, loadError);
                        return;
                    }
                    Rejection rejection = validateLanding(world, destination, settings.current());
                    if (rejection != null) {
                        rejected(rejection);
                        plugin.getLogger().warning(
                                "Replacing the reserved spawn for new player " + playerId
                                        + " because it failed " + rejection.description() + "."
                        );
                        teleportingPlayers.remove(playerId);
                        replaceReservation(player, assignment);
                        return;
                    }
                    player.teleportAsync(
                            destination.toLocation(world),
                            PlayerTeleportEvent.TeleportCause.PLUGIN
                    ).whenComplete((successful, teleportError) -> runOnMainThread(() -> {
                        if (closed) {
                            return;
                        }
                        if (teleportError != null || !Boolean.TRUE.equals(successful)) {
                            failTeleport(player, assignment, teleportError);
                            return;
                        }
                        teleportingPlayers.remove(playerId);
                        teleportAttempts.remove(playerId);
                        NewPlayerSpawnAssignment completed = assignment.complete();
                        assignments.put(playerId, completed);
                        saveState();
                        messages.send(player, "new-player-spawn.arrived");
                        player.showTitle(Title.title(
                                messages.component(player, "new-player-spawn.title"),
                                messages.component(player, "new-player-spawn.subtitle"),
                                Title.Times.times(
                                        Duration.ofMillis(250),
                                        Duration.ofMillis(2_500),
                                        Duration.ofMillis(500)
                                )
                        ));
                        feedback.play(player, FeedbackService.NEW_PLAYER_ARRIVAL);
                    }));
                }));
    }

    private void replaceReservation(Player player, NewPlayerSpawnAssignment assignment) {
        UUID playerId = player.getUniqueId();
        NewPlayerSpawnAssignment current = assignments.get(playerId);
        if (current == null || current.completed() || !current.equals(assignment)) {
            return;
        }
        assignments.remove(playerId);
        retired.add(assignment.location());
        awaitingReplacement.add(playerId);
        if (!saveState()) {
            awaitingReplacement.remove(playerId);
            retired.remove(assignment.location());
            assignments.put(playerId, assignment);
            messages.send(player, "new-player-spawn.failed");
            return;
        }
        replacementAssignments++;
        teleportAttempts.remove(playerId);
        waitingPlayers.add(playerId);
        messages.send(player, "new-player-spawn.replacing");
        if (!allocate(player)) {
            refill();
        }
    }

    private void failTeleport(
            Player player,
            NewPlayerSpawnAssignment assignment,
            Throwable error
    ) {
        UUID playerId = player.getUniqueId();
        teleportingPlayers.remove(playerId);
        int attempts = teleportAttempts.merge(playerId, 1, Integer::sum);
        if (error != null) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not place new player " + playerId + " at reserved spawn (attempt "
                            + attempts + "/" + MAX_TELEPORT_ATTEMPTS + ")",
                    error
            );
        }
        if (player.isOnline() && attempts < MAX_TELEPORT_ATTEMPTS) {
            plugin.getServer().getScheduler().runTaskLater(
                    plugin,
                    () -> teleportReserved(player, assignment),
                    RETRY_DELAY_TICKS
            );
        } else if (player.isOnline()) {
            messages.send(player, "new-player-spawn.failed");
        }
    }

    private void refill() {
        PluginSettings current = settings.current();
        if (closed
                || generationInProgress
                || !current.newPlayerSpawnEnabled()
                || available.size() >= current.newPlayerSpawnPreloadLocations()) {
            return;
        }
        if (workBudget != null
                && !workBudget.tryAcquire(TickWorkBudget.Lane.SPAWN_PREPARATION, 8)) {
            scheduleRefill(1L);
            return;
        }
        if (currentTps() < current.newPlayerSpawnMinimumTps()) {
            tpsPauses++;
            scheduleRefill(Math.max(
                    GENERATION_RETRY_DELAY_TICKS,
                    current.newPlayerSpawnGenerationDelayTicks()
            ));
            return;
        }
        Optional<World> configuredWorld = configuredWorld();
        if (configuredWorld.isEmpty()) {
            scheduleRefillRetry();
            return;
        }
        World world = configuredWorld.orElseThrow();
        GenerationCandidate candidate = candidate(world, current).orElse(null);
        if (candidate == null) {
            rejected(Rejection.SEPARATION);
            plugin.getLogger().warning(
                    "Could not find a separated new-player spawn candidate after "
                            + current.newPlayerSpawnMaxGenerationAttempts() + " attempts. "
                            + "Increase the coordinate range or reduce minimum-separation."
            );
            scheduleRefillRetry();
            return;
        }
        generationInProgress = true;
        long version = generationVersion;
        loadLandingChunks(world, candidate.x(), candidate.z(), current.newPlayerSpawnLandingRadius())
                .whenComplete((ignored, error) -> runOnMainThread(() -> {
                    if (version != generationVersion) {
                        return;
                    }
                    generationInProgress = false;
                    if (closed) {
                        return;
                    }
                    if (error != null) {
                        rejected(Rejection.CHUNK_LOAD);
                        plugin.getLogger().log(Level.WARNING, "Could not generate a new-player spawn chunk", error);
                        scheduleRefillRetry();
                        return;
                    }
                    CandidateEvaluation evaluation = evaluateCandidate(world, candidate, current);
                    NewPlayerSpawnLocation location = evaluation.location();
                    if (location != null && isFarEnough(location, current.newPlayerSpawnMinimumSeparation())) {
                        failedSurfaceAttempts = 0;
                        generatedLocations++;
                        available.add(location);
                        world.addPluginChunkTicket(candidate.x() >> 4, candidate.z() >> 4, plugin);
                        loaded.add(LocationKey.of(location));
                        saveState();
                        serveWaitingPlayers();
                        announcePoolReady();
                    } else {
                        rejected(evaluation.rejection() == null
                                ? Rejection.SEPARATION
                                : evaluation.rejection());
                        failedSurfaceAttempts++;
                        if (failedSurfaceAttempts >= current.newPlayerSpawnMaxGenerationAttempts()) {
                            failedSurfaceAttempts = 0;
                            plugin.getLogger().warning(
                                    "Could not prepare a safe new-player spawn after "
                                            + current.newPlayerSpawnMaxGenerationAttempts()
                                            + " generated candidates. Retrying in 10 seconds."
                            );
                            scheduleRefillRetry();
                            return;
                        }
                    }
                    scheduleRefill(current.newPlayerSpawnGenerationDelayTicks());
                }));
    }

    private Optional<GenerationCandidate> candidate(World world, PluginSettings current) {
        int minX = Math.min(current.newPlayerSpawnMinX(), current.newPlayerSpawnMaxX());
        int maxX = Math.max(current.newPlayerSpawnMinX(), current.newPlayerSpawnMaxX());
        int minZ = Math.min(current.newPlayerSpawnMinZ(), current.newPlayerSpawnMaxZ());
        int maxZ = Math.max(current.newPlayerSpawnMinZ(), current.newPlayerSpawnMaxZ());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        UUID worldId = world.getUID();
        Location borderProbe = new Location(world, 0.0, world.getMinHeight() + 1.0, 0.0);
        for (int attempt = 0; attempt < current.newPlayerSpawnMaxGenerationAttempts(); attempt++) {
            int x = random.nextInt(minX, maxX + 1);
            int z = random.nextInt(minZ, maxZ + 1);
            borderProbe.setX(x + 0.5);
            borderProbe.setZ(z + 0.5);
            if (world.getWorldBorder().isInside(borderProbe)
                    && isFarEnough(worldId, x, z, current.newPlayerSpawnMinimumSeparation())) {
                return Optional.of(new GenerationCandidate(x, z, random.nextFloat() * 360.0f));
            }
        }
        return Optional.empty();
    }

    private CandidateEvaluation evaluateCandidate(
            World world,
            GenerationCandidate candidate,
            PluginSettings current
    ) {
        int surfaceY = world.getHighestBlockYAt(
                candidate.x(),
                candidate.z(),
                HeightMap.MOTION_BLOCKING_NO_LEAVES
        );
        NewPlayerSpawnLocation location = new NewPlayerSpawnLocation(
                world.getUID(),
                world.getName(),
                candidate.x(),
                surfaceY + 1,
                candidate.z(),
                candidate.yaw()
        );
        Rejection rejection = validateLanding(world, location, current);
        return rejection == null
                ? new CandidateEvaluation(location, null)
                : new CandidateEvaluation(null, rejection);
    }

    private Rejection validateLanding(
            World world,
            NewPlayerSpawnLocation center,
            PluginSettings current
    ) {
        int centerGroundY = world.getHighestBlockYAt(
                center.x(),
                center.z(),
                HeightMap.MOTION_BLOCKING_NO_LEAVES
        );
        if (centerGroundY + 1 != center.y()) {
            return Rejection.UNSAFE;
        }
        String biome = world.getBiome(center.x(), centerGroundY, center.z())
                .getKey()
                .asString();
        if ((!current.newPlayerSpawnAllowedBiomes().isEmpty()
                && !current.newPlayerSpawnAllowedBiomes().contains(biome))
                || current.newPlayerSpawnBlockedBiomes().contains(biome)) {
            return Rejection.BIOME;
        }
        int radius = current.newPlayerSpawnLandingRadius();
        Location borderProbe = new Location(world, 0.0, center.y(), 0.0);
        for (int deltaX = -radius; deltaX <= radius; deltaX++) {
            for (int deltaZ = -radius; deltaZ <= radius; deltaZ++) {
                int x = center.x() + deltaX;
                int z = center.z() + deltaZ;
                int groundY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (Math.abs(groundY - centerGroundY) > current.newPlayerSpawnMaxHeightVariation()) {
                    return Rejection.SLOPE;
                }
                if (!isSafeExact(world, x, groundY + 1, z, borderProbe)) {
                    return Rejection.UNSAFE;
                }
            }
        }
        return null;
    }

    private boolean isSafeExact(
            World world,
            int x,
            int y,
            int z,
            Location borderProbe
    ) {
        if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 1) {
            return false;
        }
        borderProbe.setX(x + 0.5);
        borderProbe.setY(y);
        borderProbe.setZ(z + 0.5);
        if (!world.getWorldBorder().isInside(borderProbe)) {
            return false;
        }
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block ground = world.getBlockAt(x, y - 1, z);
        return feet.isPassable()
                && head.isPassable()
                && !feet.isLiquid()
                && !head.isLiquid()
                && ground.getType().isSolid()
                && !DANGEROUS_GROUND.contains(ground.getType())
                && !DANGEROUS_GROUND.contains(feet.getType())
                && !DANGEROUS_GROUND.contains(head.getType());
    }

    private CompletableFuture<Void> loadLandingChunks(
            World world,
            int blockX,
            int blockZ,
            int radius
    ) {
        int minChunkX = (blockX - radius) >> 4;
        int maxChunkX = (blockX + radius) >> 4;
        int minChunkZ = (blockZ - radius) >> 4;
        int maxChunkZ = (blockZ + radius) >> 4;
        List<CompletableFuture<org.bukkit.Chunk>> chunks = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunks.add(world.getChunkAtAsync(chunkX, chunkZ, true));
            }
        }
        return CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new));
    }

    boolean isFarEnough(NewPlayerSpawnLocation candidate, int minimumSeparation) {
        return isFarEnough(
                candidate.worldId(),
                candidate.x(),
                candidate.z(),
                minimumSeparation
        );
    }

    private boolean isFarEnough(UUID worldId, int x, int z, int minimumSeparation) {
        long minimumSquared = (long) minimumSeparation * minimumSeparation;
        for (NewPlayerSpawnLocation existing : available) {
            if (isTooClose(existing, worldId, x, z, minimumSquared)) {
                return false;
            }
        }
        for (NewPlayerSpawnAssignment assignment : assignments.values()) {
            if (isTooClose(assignment.location(), worldId, x, z, minimumSquared)) {
                return false;
            }
        }
        for (NewPlayerSpawnLocation existing : retired) {
            if (isTooClose(existing, worldId, x, z, minimumSquared)) {
                return false;
            }
        }
        return true;
    }

    private boolean isTooClose(
            NewPlayerSpawnLocation existing,
            UUID worldId,
            int x,
            int z,
            long minimumSquared
    ) {
        if (!existing.worldId().equals(worldId)) {
            return false;
        }
        long deltaX = (long) existing.x() - x;
        long deltaZ = (long) existing.z() - z;
        return deltaX * deltaX + deltaZ * deltaZ < minimumSquared;
    }

    private void serveWaitingPlayers() {
        for (UUID playerId : List.copyOf(waitingPlayers)) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                waitingPlayers.remove(playerId);
                continue;
            }
            if (!allocate(player)) {
                break;
            }
        }
    }

    private void announcePoolReady() {
        int target = settings.current().newPlayerSpawnPreloadLocations();
        long ready = available.stream()
                .filter(location -> loaded.contains(LocationKey.of(location)))
                .count();
        if (!poolReadyLogged && ready >= target) {
            poolReadyLogged = true;
            plugin.getLogger().info(
                    "New-player spawn pool is ready with " + ready + " preloaded locations."
            );
        }
    }

    private Optional<World> configuredWorld() {
        PluginSettings current = settings.current();
        World world = current.newPlayerSpawnWorld().isBlank()
                ? plugin.getServer().getWorlds().stream()
                .filter(candidate -> candidate.getEnvironment() == World.Environment.NORMAL)
                .findFirst()
                .orElse(null)
                : plugin.getServer().getWorld(current.newPlayerSpawnWorld());
        if (world == null || world.getEnvironment() != World.Environment.NORMAL) {
            if (!warnedMissingWorld) {
                String configured = current.newPlayerSpawnWorld().isBlank()
                        ? "an Overworld"
                        : "Overworld '" + current.newPlayerSpawnWorld() + "'";
                plugin.getLogger().warning(
                        "New-player spawn preloading is waiting for " + configured + " to become available."
                );
                warnedMissingWorld = true;
            }
            return Optional.empty();
        }
        return Optional.of(world);
    }

    private double currentTps() {
        double[] samples = plugin.getServer().getTPS();
        return samples.length == 0 || !Double.isFinite(samples[0])
                ? 20.0
                : Math.max(0.0, Math.min(20.0, samples[0]));
    }

    private void rejected(Rejection rejection) {
        rejections.merge(rejection, 1L, Long::sum);
    }

    private boolean isCurrentPreparedLocation(
            NewPlayerSpawnLocation location,
            World world,
            PluginSettings current
    ) {
        int minX = Math.min(current.newPlayerSpawnMinX(), current.newPlayerSpawnMaxX());
        int maxX = Math.max(current.newPlayerSpawnMinX(), current.newPlayerSpawnMaxX());
        int minZ = Math.min(current.newPlayerSpawnMinZ(), current.newPlayerSpawnMaxZ());
        int maxZ = Math.max(current.newPlayerSpawnMinZ(), current.newPlayerSpawnMaxZ());
        return location.worldId().equals(world.getUID())
                && location.x() >= minX
                && location.x() <= maxX
                && location.z() >= minZ
                && location.z() <= maxZ;
    }

    private Optional<World> resolveWorld(NewPlayerSpawnLocation location) {
        return Optional.ofNullable(plugin.getServer().getWorld(location.worldId()));
    }

    private void scheduleRefillRetry() {
        scheduleRefill(GENERATION_RETRY_DELAY_TICKS);
    }

    private void scheduleRefill(long delayTicks) {
        if (closed || (scheduledRefill != null && !scheduledRefill.isCancelled())) {
            return;
        }
        Runnable refill = () -> {
            scheduledRefill = null;
            refill();
        };
        scheduledRefill = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                failures == null
                        ? refill
                        : failures.guard("new-player spawn preparation", refill),
                Math.max(1L, delayTicks)
        );
    }

    private void cancelScheduledRefill() {
        if (scheduledRefill != null) {
            scheduledRefill.cancel();
            scheduledRefill = null;
        }
    }

    private boolean saveState() {
        writer.submit(new NewPlayerSpawnState(
                available,
                assignments,
                retired,
                awaitingReplacement
        ));
        return true;
    }

    private void releaseTicket(NewPlayerSpawnLocation location) {
        if (!loaded.remove(LocationKey.of(location))) {
            return;
        }
        resolveWorld(location).ifPresent(world -> world.removePluginChunkTicket(
                location.x() >> 4,
                location.z() >> 4,
                plugin
        ));
    }

    private void releaseTickets() {
        for (NewPlayerSpawnLocation location : List.copyOf(available)) {
            releaseTicket(location);
        }
    }

    private void runOnMainThread(Runnable action) {
        if (closed) {
            return;
        }
        if (plugin.getServer().isPrimaryThread()) {
            action.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, action);
        }
    }

    private void runNextTick(Runnable action) {
        if (!closed) {
            plugin.getServer().getScheduler().runTask(plugin, action);
        }
    }

    @Override
    public void close() {
        generationVersion++;
        closed = true;
        cancelScheduledRefill();
        releaseTickets();
        waitingPlayers.clear();
        teleportingPlayers.clear();
        teleportAttempts.clear();
        saveState();
        writer.close();
    }

    private record GenerationCandidate(int x, int z, float yaw) {
    }

    private record CandidateEvaluation(
            NewPlayerSpawnLocation location,
            Rejection rejection
    ) {
    }

    private record ValidationTarget(
            NewPlayerSpawnLocation location,
            boolean prepared
    ) {
    }

    private record LoadedValidationTarget(
            ValidationTarget target,
            World world,
            Throwable error
    ) {
    }

    private record LocationKey(UUID worldId, int x, int z) {

        private static LocationKey of(NewPlayerSpawnLocation location) {
            return new LocationKey(location.worldId(), location.x(), location.z());
        }
    }

    public enum RefillResult {
        STARTED,
        FULL,
        ALREADY_RUNNING,
        TPS_PAUSED,
        DISABLED,
        WORLD_UNAVAILABLE
    }

    public enum Rejection {
        UNSAFE("terrain safety"),
        BIOME("biome rules"),
        SLOPE("landing-area slope"),
        SEPARATION("minimum separation"),
        CHUNK_LOAD("chunk loading");

        private final String description;

        Rejection(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public record PoolStatus(
            boolean enabled,
            String world,
            int available,
            int ready,
            int target,
            int pendingAssignments,
            int completedAssignments,
            int retired,
            int waitingPlayers,
            boolean generating,
            boolean validating,
            double tps,
            long generatedThisRun,
            long replacementsThisRun,
            long tpsPausesThisRun,
            Map<Rejection, Long> rejectionsThisRun
    ) {
    }

    public record ValidationReport(
            int checked,
            int preparedRemoved,
            int invalidPending,
            int unavailableWorlds,
            int errors,
            boolean cancelled
    ) {
    }

    public record ClearResult(int cleared, boolean successful) {
    }
}

package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.ui.SurvivalTweaksMenu;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class SafeTeleportService implements AutoCloseable {

    public static final String INSTANT_PERMISSION = "survivaltweaks.teleport.instant";

    private static final int VERTICAL_SEARCH_DISTANCE = 3;
    private static final Set<Material> HAZARDOUS_BLOCKS = Set.of(
            Material.CACTUS,
            Material.CAMPFIRE,
            Material.FIRE,
            Material.MAGMA_BLOCK,
            Material.NETHER_PORTAL,
            Material.POWDER_SNOW,
            Material.SOUL_CAMPFIRE,
            Material.SOUL_FIRE,
            Material.SWEET_BERRY_BUSH,
            Material.WITHER_ROSE
    );

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final Clock clock;
    private final SettingsService settings;
    private final ActionBarService actionBars;
    private final PlayerExperienceService experience;
    private BukkitTask statusTask;
    private final Map<UUID, PendingTeleport> pending = new HashMap<>();
    private final Map<UUID, Instant> cooldowns = new HashMap<>();
    private final Map<UUID, Long> cooldownActionBarSeconds = new HashMap<>();
    private final Set<UUID> openContainers = new HashSet<>();

    public SafeTeleportService(
            JavaPlugin plugin,
            MessageService messages,
            FeedbackService feedback,
            Clock clock,
            SettingsService settings,
            ActionBarService actionBars
    ) {
        this(plugin, messages, feedback, clock, settings, actionBars, null);
    }

    public SafeTeleportService(
            JavaPlugin plugin,
            MessageService messages,
            FeedbackService feedback,
            Clock clock,
            SettingsService settings,
            ActionBarService actionBars,
            PlayerExperienceService experience
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.feedback = feedback;
        this.clock = clock;
        this.settings = settings;
        this.actionBars = actionBars;
        this.experience = experience;
        Objects.requireNonNull(PendingTeleport.class.getName());
        Objects.requireNonNull(BlockPosition.class.getName());
    }

    /**
     * A container held open across a teleport lets a modified client desynchronise its contents
     * from the server and duplicate the items inside it. Containers are tracked by event rather
     * than polled: {@code InventoryOpenEvent} never fires for a player's own default view, so
     * anything reported here is a real container the player could take items from.
     */
    public boolean hasContainerOpen(UUID uniqueId) {
        return openContainers.contains(uniqueId);
    }

    public boolean ensureAvailable(Player player) {
        if (pending.containsKey(player.getUniqueId())) {
            messages.send(player, "teleport.safety.pending");
            return false;
        }

        if (guardsContainers() && hasRealContainerOpen(player)) {
            messages.send(player, "teleport.safety.container-open");
            return false;
        }

        Instant availableAt = cooldowns.get(player.getUniqueId());
        Instant now = clock.instant();
        if (availableAt != null && availableAt.isAfter(now)) {
            long seconds = secondsUntil(now, availableAt);
            messages.send(
                    player,
                    MessageService.plural("teleport.safety.cooldown", seconds),
                    Placeholder.unparsed("seconds", Long.toString(seconds))
            );
            showCooldown(player, seconds);
            return false;
        }
        UUID playerId = player.getUniqueId();
        cooldowns.remove(playerId);
        cooldownActionBarSeconds.remove(playerId);
        return true;
    }

    public boolean begin(
            Player player,
            Supplier<Location> destination,
            Component successMessage
    ) {
        return begin(
                player,
                destination,
                successMessage,
                messages.component(player, "teleport.destination.generic"),
                FeedbackService.TELEPORT_COMPLETE
        );
    }

    public boolean begin(
            Player player,
            Supplier<Location> destination,
            Component successMessage,
            Component destinationLabel,
            String completionCue
    ) {
        return begin(
                player,
                destination,
                successMessage,
                destinationLabel,
                completionCue,
                settings.current().teleportWarmup()
        );
    }

    public boolean begin(
            Player player,
            Supplier<Location> destination,
            Component successMessage,
            Component destinationLabel,
            String completionCue,
            Duration warmup
    ) {
        if (!ensureAvailable(player)) {
            return false;
        }

        UUID uniqueId = player.getUniqueId();
        PendingTeleport teleport = new PendingTeleport(
                BlockPosition.at(player.getLocation()),
                player.getLocation().clone(),
                destination,
                successMessage,
                destinationLabel == null ? Component.empty() : destinationLabel,
                completionCue == null ? FeedbackService.TELEPORT_COMPLETE : completionCue,
                clock.instant().plus(warmup),
                warmup
        );
        pending.put(uniqueId, teleport);
        ensureStatusTask();

        if (warmup.isZero() || player.hasPermission(INSTANT_PERMISSION)) {
            teleport(player, teleport);
            return true;
        }

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> completeWarmup(uniqueId),
                warmup.toSeconds() * 20L
        );
        teleport.task(task);
        BossBar bossBar = BossBar.bossBar(
                teleport.destinationLabel(),
                1.0f,
                BossBar.Color.PURPLE,
                BossBar.Overlay.PROGRESS
        );
        teleport.bossBar(bossBar);
        if (settings.current().actionBarEnabled()
                && (experience == null || experience.actionBars(player))) {
            player.showBossBar(bossBar);
        }
        messages.send(
                player,
                MessageService.plural("teleport.safety.warmup", warmup.toSeconds()),
                Placeholder.unparsed("seconds", Long.toString(warmup.toSeconds()))
        );
        feedback.play(player, FeedbackService.TELEPORT_WARMUP);
        showWarmup(player, teleport, warmup.toSeconds());
        return true;
    }

    public void handleMove(Player player, Location destination) {
        PendingTeleport teleport = pending.get(player.getUniqueId());
        if (teleport != null
                && !teleport.teleporting()
                && !teleport.origin().equals(BlockPosition.at(destination))) {
            cancel(player, "teleport.safety.cancelled-move");
        }
    }

    public void handleDamage(Player player) {
        PendingTeleport teleport = pending.get(player.getUniqueId());
        if (teleport != null && !teleport.teleporting()) {
            cancel(player, "teleport.safety.cancelled-damage");
        }
    }

    public void containerOpened(Player player) {
        openContainers.add(player.getUniqueId());
        PendingTeleport teleport = pending.get(player.getUniqueId());
        if (guardsContainers() && teleport != null && !teleport.teleporting()) {
            cancel(player, "teleport.safety.cancelled-inventory");
        }
    }

    public void containerClosed(UUID uniqueId) {
        openContainers.remove(uniqueId);
    }

    public void playerDisconnected(UUID uniqueId) {
        PendingTeleport teleport = pending.remove(uniqueId);
        if (teleport != null) {
            teleport.cancelTask();
        }
        cooldowns.remove(uniqueId);
        cooldownActionBarSeconds.remove(uniqueId);
        openContainers.remove(uniqueId);
        actionBars.forget(uniqueId);
        stopStatusTaskIfIdle();
    }

    private boolean guardsContainers() {
        return settings.current().cancelTeleportOnInventoryOpen();
    }

    /**
     * Checks the current view as well as event-derived state. Reading the live view closes the
     * small reload window where an inventory opened before plugin enable has no corresponding
     * {@code InventoryOpenEvent} in this service instance.
     */
    private boolean hasRealContainerOpen(Player player) {
        if (hasContainerOpen(player.getUniqueId())) {
            return true;
        }

        InventoryView view = player.getOpenInventory();
        if (view == null) {
            return false;
        }

        Inventory top = view.getTopInventory();
        if (top == null) {
            return false;
        }

        InventoryType type = top.getType();
        if (type != null && ("CRAFTING".equals(type.name()) || "CREATIVE".equals(type.name()))) {
            return false;
        }
        return !(top.getHolder(false) instanceof SurvivalTweaksMenu);
    }

    public boolean isPending(UUID uniqueId) {
        return pending.containsKey(uniqueId);
    }

    @Override
    public void close() {
        for (Map.Entry<UUID, PendingTeleport> entry : pending.entrySet()) {
            PendingTeleport teleport = entry.getValue();
            if (teleport != null) {
                teleport.cancelTask();
                Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null) {
                    teleport.hideBossBar(player);
                }
            }
        }
        pending.clear();
        cooldowns.clear();
        cooldownActionBarSeconds.clear();
        openContainers.clear();
        if (statusTask != null) {
            statusTask.cancel();
            statusTask = null;
        }
    }

    private void completeWarmup(UUID uniqueId) {
        PendingTeleport teleport = pending.get(uniqueId);
        if (teleport == null) {
            return;
        }

        Player player = plugin.getServer().getPlayer(uniqueId);
        if (player != null) {
            teleport(player, teleport);
        } else {
            pending.remove(uniqueId, teleport);
            teleport.cancelTask();
            stopStatusTaskIfIdle();
        }
    }

    private void cancel(Player player, String message) {
        PendingTeleport teleport = pending.remove(player.getUniqueId());
        if (teleport == null) {
            return;
        }
        teleport.cancelTask();
        teleport.hideBossBar(player);
        messages.send(player, message);
        feedback.play(player, FeedbackService.TELEPORT_CANCELLED);
        actionBars.clear(player, ActionBarService.TELEPORT_PRIORITY);
        stopStatusTaskIfIdle();
    }

    private void teleport(Player player, PendingTeleport pendingTeleport) {
        if (!isActive(player, pendingTeleport)) {
            return;
        }

        Location requested;
        try {
            requested = pendingTeleport.destination().get();
        } catch (RuntimeException exception) {
            fail(player, pendingTeleport, "Could not resolve teleport destination", exception);
            return;
        }
        if (requested == null || requested.getWorld() == null) {
            finish(player, pendingTeleport);
            messages.send(player, "teleport.safety.unavailable");
            feedback.play(player, FeedbackService.TELEPORT_CANCELLED);
            return;
        }

        CompletableFuture<Void> chunkLoad;
        try {
            chunkLoad = loadSearchChunks(requested);
        } catch (RuntimeException exception) {
            fail(player, pendingTeleport, "Could not start destination chunk loading", exception);
            return;
        }

        chunkLoad.whenComplete((ignored, loadError) -> runOnMainThread(() -> {
            if (!isActive(player, pendingTeleport)) {
                return;
            }
            if (loadError != null) {
                fail(player, pendingTeleport, "Could not load destination chunks", loadError);
                return;
            }

            Optional<Location> safeDestination;
            try {
                safeDestination = findSafeDestination(requested);
            } catch (RuntimeException exception) {
                fail(player, pendingTeleport, "Could not evaluate teleport destination safety", exception);
                return;
            }
            if (safeDestination.isEmpty()) {
                finish(player, pendingTeleport);
                messages.send(player, "teleport.safety.unsafe");
                feedback.play(player, FeedbackService.TELEPORT_CANCELLED);
                return;
            }

            if (guardsContainers() && hasRealContainerOpen(player)) {
                cancel(player, "teleport.safety.cancelled-inventory");
                return;
            }

            pendingTeleport.teleporting(true);
            CompletableFuture<Boolean> result;
            try {
                result = player.teleportAsync(
                        safeDestination.orElseThrow(),
                        PlayerTeleportEvent.TeleportCause.COMMAND
                );
            } catch (RuntimeException exception) {
                fail(player, pendingTeleport, "Could not start player teleport", exception);
                return;
            }

            result.whenComplete((successful, teleportError) -> runOnMainThread(() -> {
                if (!isActive(player, pendingTeleport)) {
                    return;
                }
                if (teleportError != null || !successful) {
                    fail(player, pendingTeleport, "Player teleport did not complete", teleportError);
                    return;
                }
                finish(player, pendingTeleport);
                Duration cooldown = settings.current().teleportCooldown();
                if (!cooldown.isZero()) {
                    cooldowns.put(player.getUniqueId(), clock.instant().plus(cooldown));
                    ensureStatusTask();
                }
                player.sendMessage(pendingTeleport.successMessage());
                showArrival(player, pendingTeleport);
            }));
        }));
    }

    private CompletableFuture<Void> loadSearchChunks(Location destination) {
        World world = destination.getWorld();
        int searchRadius = settings.current().safeTeleportSearchRadius();
        int minChunkX = Math.floorDiv(destination.getBlockX() - searchRadius, 16);
        int maxChunkX = Math.floorDiv(destination.getBlockX() + searchRadius, 16);
        int minChunkZ = Math.floorDiv(destination.getBlockZ() - searchRadius, 16);
        int maxChunkZ = Math.floorDiv(destination.getBlockZ() + searchRadius, 16);

        List<CompletableFuture<Chunk>> chunks = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunks.add(world.getChunkAtAsync(chunkX, chunkZ));
            }
        }
        return CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new));
    }

    Optional<Location> findSafeDestination(Location requested) {
        if (isSafe(requested)) {
            return Optional.of(requested.clone());
        }

        World world = requested.getWorld();
        int baseX = requested.getBlockX();
        int baseY = requested.getBlockY();
        int baseZ = requested.getBlockZ();
        int searchRadius = settings.current().safeTeleportSearchRadius();
        Location candidate = requested.clone();
        for (int radius = 0; radius <= searchRadius; radius++) {
            for (int yOffset = -VERTICAL_SEARCH_DISTANCE; yOffset <= VERTICAL_SEARCH_DISTANCE; yOffset++) {
                for (int xOffset = -radius; xOffset <= radius; xOffset++) {
                    for (int zOffset = -radius; zOffset <= radius; zOffset++) {
                        if (radius > 0 && Math.abs(xOffset) != radius && Math.abs(zOffset) != radius) {
                            continue;
                        }
                        candidate.setX(baseX + xOffset + 0.5);
                        candidate.setY(baseY + yOffset);
                        candidate.setZ(baseZ + zOffset + 0.5);
                        if (isSafe(candidate)) {
                            return Optional.of(candidate.clone());
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean isSafe(Location location) {
        World world = location.getWorld();
        if (world == null || location.getBlockY() <= world.getMinHeight()
                || location.getBlockY() + 1 >= world.getMaxHeight()
                || !world.getWorldBorder().isInside(location)) {
            return false;
        }

        Block feet = world.getBlockAt(location);
        Block head = feet.getRelative(0, 1, 0);
        Block floor = feet.getRelative(0, -1, 0);
        return feet.isPassable()
                && !feet.isLiquid()
                && !HAZARDOUS_BLOCKS.contains(feet.getType())
                && head.isPassable()
                && !head.isLiquid()
                && !HAZARDOUS_BLOCKS.contains(head.getType())
                && floor.isSolid()
                && !HAZARDOUS_BLOCKS.contains(floor.getType());
    }

    private long secondsUntil(Instant now, Instant availableAt) {
        long milliseconds = Duration.between(now, availableAt).toMillis();
        return Math.max(1L, (milliseconds + 999L) / 1_000L);
    }

    private boolean isActive(Player player, PendingTeleport teleport) {
        return player.isOnline() && pending.get(player.getUniqueId()) == teleport;
    }

    private void finish(Player player, PendingTeleport teleport) {
        pending.remove(player.getUniqueId(), teleport);
        teleport.cancelTask();
        teleport.hideBossBar(player);
        actionBars.clear(player, ActionBarService.TELEPORT_PRIORITY);
    }

    private void fail(Player player, PendingTeleport teleport, String context, Throwable error) {
        finish(player, teleport);
        if (error != null) {
            plugin.getLogger().log(Level.WARNING, context + " for " + player.getName(), error);
        } else {
            plugin.getLogger().warning(context + " for " + player.getName());
        }
        messages.send(player, "teleport.safety.failed");
        feedback.play(player, FeedbackService.TELEPORT_CANCELLED);
    }

    private void runOnMainThread(Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (plugin.getServer().isPrimaryThread()) {
            action.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, action);
        }
    }

    private void updateActionBars() {
        Instant now = clock.instant();
        pending.forEach((playerId, teleport) -> {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && !teleport.teleporting()) {
                long seconds = secondsUntil(now, teleport.readyAt());
                if (settings.current().actionBarEnabled()) {
                    showWarmup(player, teleport, seconds);
                } else {
                    teleport.lastActionBarSecond(Long.MIN_VALUE);
                    actionBars.clear(player, ActionBarService.TELEPORT_PRIORITY);
                }
                updateWarmupPresentation(player, teleport, seconds, now);
            }
        });
        cooldowns.entrySet().removeIf(entry -> {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (!entry.getValue().isAfter(now)) {
                cooldownActionBarSeconds.remove(entry.getKey());
                if (player != null) {
                    actionBars.clear(player, ActionBarService.TELEPORT_COOLDOWN_PRIORITY);
                }
                return true;
            }
            if (player != null && settings.current().actionBarEnabled()) {
                long seconds = secondsUntil(now, entry.getValue());
                Long previous = cooldownActionBarSeconds.get(entry.getKey());
                if (previous == null || previous != seconds) {
                    showCooldown(player, seconds);
                    cooldownActionBarSeconds.put(entry.getKey(), seconds);
                }
            } else if (player != null) {
                cooldownActionBarSeconds.remove(entry.getKey());
                actionBars.clear(player, ActionBarService.TELEPORT_COOLDOWN_PRIORITY);
            }
            return false;
        });
        stopStatusTaskIfIdle();
    }

    private void showWarmup(Player player, PendingTeleport teleport, long seconds) {
        if (teleport.lastActionBarSecond() == seconds) {
            return;
        }
        if (settings.current().actionBarEnabled()
                && (experience == null || experience.actionBars(player))) {
            actionBars.show(
                    player,
                    messages.component(
                            player,
                            "teleport.actionbar.warmup",
                            Placeholder.unparsed("seconds", Long.toString(seconds))
                    ),
                    ActionBarService.TELEPORT_PRIORITY,
                    Duration.ofMillis(1_250)
            );
            teleport.lastActionBarSecond(seconds);
        }
    }

    private void showCooldown(Player player, long seconds) {
        if (settings.current().actionBarEnabled()
                && (experience == null || experience.actionBars(player))) {
            actionBars.show(
                    player,
                    messages.component(
                            player,
                            "teleport.actionbar.cooldown",
                            Placeholder.unparsed("seconds", Long.toString(seconds))
                    ),
                    ActionBarService.TELEPORT_COOLDOWN_PRIORITY,
                    Duration.ofMillis(1_250)
            );
        }
    }

    private void updateWarmupPresentation(
            Player player,
            PendingTeleport teleport,
            long seconds,
            Instant now
    ) {
        if (teleport.bossBar() != null) {
            long totalMillis = Math.max(1L, teleport.warmup().toMillis());
            long remainingMillis = Math.max(0L, Duration.between(now, teleport.readyAt()).toMillis());
            teleport.bossBar().progress(Math.max(0.0f, Math.min(1.0f, (float) remainingMillis / totalMillis)));
        }
        if (seconds <= 3 && seconds != teleport.lastCountdownSecond()) {
            teleport.lastCountdownSecond(seconds);
            double intensity = 1.0 + ((4L - seconds) * 0.3);
            feedback.play(player, FeedbackService.TELEPORT_COUNTDOWN, intensity);
        }
    }

    private void showArrival(Player player, PendingTeleport teleport) {
        Component title = messages.component(player, "teleport.title.arrived");
        player.showTitle(Title.title(
                title == null ? Component.empty() : title,
                teleport.destinationLabel(),
                Title.Times.times(
                        Duration.ofMillis(200),
                        Duration.ofMillis(1_200),
                        Duration.ofMillis(350)
                )
        ));
        if (!teleport.completionCue().isBlank()) {
            feedback.play(player, teleport.completionCue());
        }
        World originWorld = teleport.originLocation().getWorld();
        if (originWorld != null) {
            java.util.Collection<Player> viewers = originWorld.getNearbyPlayers(
                    teleport.originLocation(),
                    32
            );
            if (viewers != null) {
                viewers.forEach(viewer -> feedback.playAt(
                        viewer,
                        teleport.completionCue().isBlank()
                                ? FeedbackService.TELEPORT_COMPLETE
                                : teleport.completionCue(),
                        teleport.originLocation(),
                        0.7
                ));
            }
        }
        World destWorld = player.getWorld();
        if (destWorld != null) {
            java.util.Collection<Player> viewers = destWorld.getNearbyPlayers(player.getLocation(), 32);
            if (viewers != null) {
                viewers.forEach(viewer -> feedback.playAt(
                        viewer,
                        teleport.completionCue().isBlank()
                                ? FeedbackService.TELEPORT_COMPLETE
                                : teleport.completionCue(),
                        player.getLocation(),
                        1.0
                ));
            }
        }
    }

    private void clearTeleportActionBar(UUID playerId) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null) {
            actionBars.clear(player, ActionBarService.TELEPORT_PRIORITY);
        }
    }

    private void clearCooldownActionBar(UUID playerId) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null) {
            actionBars.clear(player, ActionBarService.TELEPORT_COOLDOWN_PRIORITY);
        }
    }

    private void ensureStatusTask() {
        if (statusTask == null && (!pending.isEmpty() || !cooldowns.isEmpty())) {
            statusTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin,
                    this::updateActionBars,
                    0L,
                    5L
            );
        }
    }

    private void stopStatusTaskIfIdle() {
        if (statusTask != null && pending.isEmpty() && cooldowns.isEmpty()) {
            statusTask.cancel();
            statusTask = null;
        }
    }

    private static final class PendingTeleport {
        private final BlockPosition origin;
        private final Location originLocation;
        private final Supplier<Location> destination;
        private final Component successMessage;
        private final Component destinationLabel;
        private final String completionCue;
        private final Instant readyAt;
        private final Duration warmup;
        private BukkitTask task;
        private BossBar bossBar;
        private long lastCountdownSecond = Long.MIN_VALUE;
        private long lastActionBarSecond = Long.MIN_VALUE;
        private boolean teleporting;

        private PendingTeleport(
                BlockPosition origin,
                Location originLocation,
                Supplier<Location> destination,
                Component successMessage,
                Component destinationLabel,
                String completionCue,
                Instant readyAt,
                Duration warmup
        ) {
            this.origin = origin;
            this.originLocation = originLocation;
            this.destination = destination;
            this.successMessage = successMessage;
            this.destinationLabel = destinationLabel;
            this.completionCue = completionCue;
            this.readyAt = readyAt;
            this.warmup = warmup;
        }

        private BlockPosition origin() {
            return origin;
        }

        private Supplier<Location> destination() {
            return destination;
        }

        private Location originLocation() {
            return originLocation;
        }

        private Component successMessage() {
            return successMessage;
        }

        private Component destinationLabel() {
            return destinationLabel;
        }

        private String completionCue() {
            return completionCue;
        }

        private Instant readyAt() {
            return readyAt;
        }

        private Duration warmup() {
            return warmup;
        }

        private void task(BukkitTask task) {
            this.task = task;
        }

        private BossBar bossBar() {
            return bossBar;
        }

        private void bossBar(BossBar bossBar) {
            this.bossBar = bossBar;
        }

        private long lastCountdownSecond() {
            return lastCountdownSecond;
        }

        private void lastCountdownSecond(long seconds) {
            lastCountdownSecond = seconds;
        }

        private long lastActionBarSecond() {
            return lastActionBarSecond;
        }

        private void lastActionBarSecond(long seconds) {
            lastActionBarSecond = seconds;
        }

        private boolean teleporting() {
            return teleporting;
        }

        private void teleporting(boolean teleporting) {
            this.teleporting = teleporting;
        }

        private void cancelTask() {
            if (task != null) {
                task.cancel();
            }
        }

        private void hideBossBar(Player player) {
            if (bossBar != null) {
                player.hideBossBar(bossBar);
            }
        }
    }

    private record BlockPosition(UUID worldId, int x, int y, int z) {

        private static BlockPosition at(Location location) {
            return new BlockPosition(
                    location.getWorld().getUID(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );
        }
    }
}

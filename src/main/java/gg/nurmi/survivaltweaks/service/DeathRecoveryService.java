package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.DeathMarker;
import gg.nurmi.survivaltweaks.object.NotificationType;
import gg.nurmi.survivaltweaks.object.OnboardingHint;
import gg.nurmi.survivaltweaks.storage.DeathMarkerStore;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DeathRecoveryService
        implements Listener, CommandExecutor, TabCompleter, AutoCloseable {

    private static final long GUIDE_UPDATE_TICKS = 2L;
    private static final double REACHED_DISTANCE = 3.0;
    private static final double GUIDE_POSITION_EPSILON_SQUARED = 0.01;
    private static final float GUIDE_ROTATION_EPSILON = 2.0f;

    private final JavaPlugin plugin;
    private final DeathMarkerStore store;
    private final MessageService messages;
    private final SettingsService settings;
    private final Clock clock;
    private final FeedbackService feedback;
    private final NotificationService notifications;
    private final OnboardingService onboarding;
    private final PerformanceGovernor governor;
    private final TickWorkBudget workBudget;
    private final TaskFailureIsolation failures;
    private final CoalescingSnapshotWriter<List<DeathMarker>> writer;
    private final NamespacedKey legacyCompassOwnerKey;
    private final Map<UUID, DeathMarker> markers = new HashMap<>();
    private final Set<UUID> floatingGuidePlayers = new HashSet<>();
    private final Set<UUID> pendingGuideRefreshes = new HashSet<>();
    private final Map<UUID, TextDisplay> floatingGuides = new HashMap<>();
    private final Map<UUID, GuideTextState> floatingGuideTextStates = new HashMap<>();
    private final Map<UUID, GuidePosition> floatingGuidePositions = new HashMap<>();
    private BukkitTask expiryTask;
    private BukkitTask guideTask;
    private long guideUpdates;

    public DeathRecoveryService(
            JavaPlugin plugin,
            DeathMarkerStore store,
            MessageService messages,
            SettingsService settings,
            Clock clock,
            FeedbackService feedback,
            NotificationService notifications,
            OnboardingService onboarding
    ) {
        this(
                plugin,
                store,
                messages,
                settings,
                clock,
                feedback,
                notifications,
                onboarding,
                null,
                null,
                null
        );
    }

    public DeathRecoveryService(
            JavaPlugin plugin,
            DeathMarkerStore store,
            MessageService messages,
            SettingsService settings,
            Clock clock,
            FeedbackService feedback,
            NotificationService notifications,
            OnboardingService onboarding,
            PerformanceGovernor governor,
            TickWorkBudget workBudget,
            TaskFailureIsolation failures
    ) {
        this.plugin = plugin;
        this.store = store;
        this.messages = messages;
        this.settings = settings;
        this.clock = clock;
        this.feedback = feedback;
        this.notifications = notifications;
        this.onboarding = onboarding;
        this.governor = governor;
        this.workBudget = workBudget;
        this.failures = failures;
        this.writer = new CoalescingSnapshotWriter<>(
                "SurvivalTweaks-death-marker-writer",
                "death markers",
                store::save,
                plugin.getLogger() == null
                        ? Logger.getLogger(DeathRecoveryService.class.getName())
                        : plugin.getLogger()
        );
        this.legacyCompassOwnerKey = new NamespacedKey(
                "survivaltweaks",
                "death-compass-owner"
        );
        store.load().forEach(marker -> {
            if (!marker.expired(clock.instant())) {
                markers.put(marker.playerId(), marker);
            }
        });
        ensureExpiryTask();
        if (!markers.isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getServer().getOnlinePlayers().forEach(player -> {
                        removeLegacyRecoveryCompasses(player);
                        enableFloatingGuide(player, false);
                    })
            );
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!settings.current().deathRecoveryEnabled()) {
            return;
        }
        Player player = event.getEntity();
        Instant now = clock.instant();
        String cause = player.getLastDamageCause() == null
                ? "UNKNOWN"
                : player.getLastDamageCause().getCause().name();
        DeathMarker marker = DeathMarker.at(
                player.getUniqueId(),
                player.getLocation(),
                now,
                now.plus(settings.current().deathMarkerLifetime()),
                cause
        );
        event.getDrops().removeIf(item -> isLegacyRecoveryCompass(item, player.getUniqueId()));
        removeLegacyRecoveryCompasses(player);
        removeFloatingGuideDisplay(player.getUniqueId());
        markers.put(player.getUniqueId(), marker);
        ensureExpiryTask();
        persist();
        sendLocation(player, marker, "death-recovery.recorded");
        onboarding.show(player, OnboardingHint.DEATH_RECOVERY);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!settings.current().deathRecoveryEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> {
                    Player player = event.getPlayer();
                    if (!player.isOnline()) {
                        return;
                    }
                    removeLegacyRecoveryCompasses(player);
                    if (settings.current().deathFloatingGuideEnabled()) {
                        enableFloatingGuide(player, false);
                    }
                },
                1L
        );
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player player = event.getPlayer();
            if (!player.isOnline()) {
                return;
            }
            removeLegacyRecoveryCompasses(player);
            if (settings.current().deathRecoveryEnabled()
                    && settings.current().deathFloatingGuideEnabled()) {
                enableFloatingGuide(player, false);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGuideChunkTransition(PlayerMoveEvent event) {
        if (sameChunk(event.getFrom(), event.getTo())) {
            return;
        }
        scheduleGuideRefresh(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuideWorldTransition(PlayerChangedWorldEvent event) {
        scheduleGuideRefresh(event.getPlayer(), true);
    }

    private void scheduleGuideRefresh(Player source, boolean transitionedWorld) {
        UUID playerId = source.getUniqueId();
        if (!floatingGuidePlayers.contains(playerId)
                || !pendingGuideRefreshes.add(playerId)) {
            return;
        }
        Runnable refresh = () -> {
            pendingGuideRefreshes.remove(playerId);
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null || !player.isOnline() || player.isDead()) {
                return;
            }
            if (transitionedWorld) {
                feedback.play(player, FeedbackService.GUIDE_HINT);
            }
            activeMarker(playerId).ifPresent(marker -> updateFloatingGuide(player, marker));
        };
        plugin.getServer().getScheduler().runTask(
                plugin,
                failures == null ? refresh : failures.guard("death guide transition", refresh)
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopFloatingGuide(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments
    ) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return true;
        }
        if (!settings.current().deathRecoveryEnabled()) {
            messages.send(player, "death-recovery.disabled");
            return true;
        }
        if (arguments.length == 1 && arguments[0].equalsIgnoreCase("info")) {
            showInfo(player);
            return true;
        }
        if (arguments.length == 1 && arguments[0].equalsIgnoreCase("dismiss")) {
            dismiss(player);
            return true;
        }
        if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("guide")) {
            if (arguments.length == 1) {
                toggleFloatingGuide(player);
                return true;
            }
            if (arguments.length == 2 && arguments[1].equalsIgnoreCase("on")) {
                enableFloatingGuide(player, true);
                return true;
            }
            if (arguments.length == 2 && arguments[1].equalsIgnoreCase("off")) {
                disableFloatingGuide(player, true);
                return true;
            }
        }
        if (arguments.length != 0) {
            messages.send(player, "death-recovery.usage");
            return true;
        }
        openStatus(player);
        return true;
    }

    public boolean openStatus(Player player) {
        if (!settings.current().deathRecoveryEnabled()) {
            messages.send(player, "death-recovery.disabled");
            return false;
        }
        Optional<DeathMarker> marker = activeMarker(player.getUniqueId());
        if (marker.isEmpty()) {
            messages.send(player, "death-recovery.none");
            return false;
        }
        sendLocation(player, marker.orElseThrow(), "death-recovery.location");
        if (settings.current().deathFloatingGuideEnabled()) {
            enableFloatingGuide(player, false);
            messages.send(player, "death-recovery.guide-prompt-enabled");
        }
        return true;
    }

    public boolean showInfo(Player player) {
        Optional<DeathMarker> active = activeMarker(player.getUniqueId());
        if (active.isEmpty()) {
            messages.send(player, "death-recovery.none");
            return false;
        }

        DeathMarker marker = active.orElseThrow();
        Instant now = clock.instant();
        long remainingSec = Math.max(0, Duration.between(now, marker.expiresAt()).toSeconds());
        long minutes = remainingSec / 60;
        long seconds = remainingSec % 60;

        String distanceKey;
        TagResolver[] distancePlaceholders;
        Optional<Location> resolved = marker.resolve(plugin.getServer());
        if (resolved.isPresent() && player.getWorld().equals(resolved.get().getWorld())) {
            long distance = Math.round(player.getLocation().distance(resolved.get()));
            distanceKey = MessageService.plural("death-recovery.distance.same-world", distance);
            distancePlaceholders = new TagResolver[]{
                    Placeholder.unparsed("distance", Long.toString(distance)),
                    Placeholder.unparsed("arrow", directionArrow(player.getLocation(), resolved.get()))
            };
        } else if (resolved.isPresent()) {
            distanceKey = "death-recovery.distance.different-world";
            distancePlaceholders = new TagResolver[]{
                    Placeholder.unparsed("world", marker.worldName())
            };
        } else {
            distanceKey = "death-recovery.distance.unloaded-world";
            distancePlaceholders = new TagResolver[]{
                    Placeholder.unparsed("world", marker.worldName())
            };
        }

        messages.send(
                player,
                "death-recovery.info",
                Placeholder.unparsed("world", marker.worldName()),
                Placeholder.unparsed("x", Long.toString(Math.round(marker.x()))),
                Placeholder.unparsed("y", Long.toString(Math.round(marker.y()))),
                Placeholder.unparsed("z", Long.toString(Math.round(marker.z()))),
                Placeholder.component("cause", messages.component(player, causeKey(marker.cause()))),
                Placeholder.component("distance", messages.component(player, distanceKey, distancePlaceholders)),
                Placeholder.unparsed("minutes", Long.toString(minutes)),
                Placeholder.unparsed("seconds", Long.toString(seconds))
        );
        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] arguments
    ) {
        if (arguments.length == 1) {
            String prefix = arguments[0].toLowerCase(Locale.ROOT);
            return List.of("info", "guide", "dismiss").stream()
                    .filter(option -> option.startsWith(prefix))
                    .toList();
        }
        if (arguments.length == 2 && arguments[0].equalsIgnoreCase("guide")) {
            String prefix = arguments[1].toLowerCase(Locale.ROOT);
            return List.of("on", "off").stream()
                    .filter(option -> option.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    public boolean hasActiveMarker(UUID playerId) {
        return settings.current().deathRecoveryEnabled()
                && activeMarker(playerId).isPresent();
    }

    @Override
    public void close() {
        if (expiryTask != null) {
            expiryTask.cancel();
            expiryTask = null;
        }
        if (guideTask != null) {
            guideTask.cancel();
            guideTask = null;
        }
        clearFloatingGuides();
        persist();
        writer.close();
        markers.clear();
    }

    private void updateFloatingGuides() {
        guideUpdates++;
        if (!settings.current().deathRecoveryEnabled()
                || !settings.current().deathFloatingGuideEnabled()) {
            clearFloatingGuides();
            return;
        }
        int divisor = governor == null ? 1 : governor.cosmeticDivisor();
        if (guideUpdates % divisor != 0L) {
            return;
        }
        for (UUID playerId : List.copyOf(floatingGuidePlayers)) {
            if (workBudget != null
                    && !workBudget.tryAcquire(TickWorkBudget.Lane.DEATH_GUIDE, 1)) {
                break;
            }
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null || !player.isOnline() || player.isDead()) {
                removeFloatingGuideDisplay(playerId);
                continue;
            }
            Optional<DeathMarker> selected = activeMarker(playerId);
            if (selected.isEmpty()) {
                stopFloatingGuide(playerId);
                continue;
            }
            Runnable update = () -> updateFloatingGuide(player, selected.orElseThrow());
            if (failures == null) {
                update.run();
            } else {
                failures.run("death guide player", update);
            }
        }
    }

    private void updateFloatingGuide(Player player, DeathMarker marker) {
        Optional<Location> resolved = marker.resolve(plugin.getServer());
        if (resolved.isEmpty()) {
            showGuide(
                    player,
                    frontOfPlayer(player),
                    new GuideTextState("death-recovery.guide-world", marker.worldName(), -1L)
            );
            return;
        }

        Location target = resolved.orElseThrow();
        if (!target.getWorld().getUID().equals(player.getWorld().getUID())) {
            Optional<PortalHint> portal = portalHint(player, target);
            if (portal.isEmpty()) {
                showGuide(
                        player,
                        frontOfPlayer(player),
                        new GuideTextState("death-recovery.guide-world", marker.worldName(), -1L)
                );
                return;
            }
            Location portalTarget = new Location(
                    player.getWorld(),
                    portal.orElseThrow().x() + 0.5,
                    player.getEyeLocation().getY(),
                    portal.orElseThrow().z() + 0.5
            );
            long distance = Math.round(horizontalDistance(player.getLocation(), portalTarget));
            showGuide(
                    player,
                    directionalGuideLocation(
                            player.getEyeLocation(),
                            portalTarget,
                            settings.current().deathFloatingGuideOffset()
                    ),
                    new GuideTextState(
                            "death-recovery.guide-portal",
                            "",
                            distance,
                            portal.orElseThrow().x(),
                            portal.orElseThrow().z()
                    )
            );
            return;
        }

        if (player.isDead()) {
            return;
        }

        double preciseDistance = player.getLocation().distance(target);
        if (preciseDistance <= REACHED_DISTANCE) {
            reached(player, marker);
            return;
        }
        long distance = Math.round(preciseDistance);
        boolean showAtDestination = preciseDistance <= settings.current().deathFloatingGuideNearDistance()
                && target.getWorld().isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4);
        Location displayLocation = showAtDestination
                ? target.clone().add(0, 1.8, 0)
                : directionalGuideLocation(
                        player.getEyeLocation(),
                        target.clone().add(0, 1.4, 0),
                        settings.current().deathFloatingGuideOffset()
                );
        showGuide(
                player,
                displayLocation,
                new GuideTextState(
                        showAtDestination
                                ? "death-recovery.guide-destination"
                                : "death-recovery.guide-navigation",
                        "",
                        distance
                )
        );
        if (distance <= 20L
                && guideUpdates % (20L / GUIDE_UPDATE_TICKS) == 0) {
            feedback.playAt(
                    player,
                    "death-nearby",
                    target,
                    Math.max(0.4, (21.0 - distance) / 10.0)
            );
        }
    }

    private void showGuide(Player player, Location location, GuideTextState textState) {
        UUID playerId = player.getUniqueId();
        Location safeLocation = loadedGuideLocation(player, location);
        TextDisplay display = floatingGuides.get(playerId);
        if (display == null
                || !display.isValid()
                || !display.getWorld().getUID().equals(safeLocation.getWorld().getUID())) {
            removeFloatingGuideDisplay(playerId);
            Component text = guideText(player, textState);
            display = safeLocation.getWorld().spawn(safeLocation, TextDisplay.class, entity -> {
                entity.setPersistent(false);
                entity.setVisibleByDefault(false);
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setSilent(true);
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setSeeThrough(true);
                entity.setShadowed(true);
                entity.setDefaultBackground(false);
                entity.setBackgroundColor(Color.fromARGB(112, 0, 0, 0));
                entity.setLineWidth(180);
                entity.setViewRange(4.0f);
                entity.setDisplayWidth(2.5f);
                entity.setDisplayHeight(0.75f);
                entity.setTeleportDuration(Math.toIntExact(GUIDE_UPDATE_TICKS));
                entity.text(text);
            });
            floatingGuides.put(playerId, display);
            floatingGuideTextStates.put(playerId, textState);
            floatingGuidePositions.put(playerId, GuidePosition.at(safeLocation));
            player.showEntity(plugin, display);
            return;
        }
        GuideTextState previousText = floatingGuideTextStates.put(playerId, textState);
        if (!textState.equals(previousText)) {
            display.text(guideText(player, textState));
        }
        GuidePosition position = GuidePosition.at(safeLocation);
        GuidePosition previousPosition = floatingGuidePositions.get(playerId);
        if (previousPosition == null || !position.near(previousPosition)) {
            floatingGuidePositions.put(playerId, position);
            display.teleport(safeLocation);
        }
    }

    private Location loadedGuideLocation(Player player, Location desired) {
        World world = desired.getWorld();
        if (world.isChunkLoaded(desired.getBlockX() >> 4, desired.getBlockZ() >> 4)) {
            return desired;
        }

        Location playerLocation = player.getLocation();
        if (!playerLocation.getWorld().getUID().equals(world.getUID())) {
            return desired;
        }
        int chunkX = playerLocation.getBlockX() >> 4;
        int chunkZ = playerLocation.getBlockZ() >> 4;
        return new Location(
                world,
                clampToChunk(desired.getX(), chunkX),
                desired.getY(),
                clampToChunk(desired.getZ(), chunkZ),
                desired.getYaw(),
                desired.getPitch()
        );
    }

    static boolean sameChunk(Location first, Location second) {
        return first.getWorld().getUID().equals(second.getWorld().getUID())
                && (first.getBlockX() >> 4) == (second.getBlockX() >> 4)
                && (first.getBlockZ() >> 4) == (second.getBlockZ() >> 4);
    }

    static double clampToChunk(double coordinate, int chunkCoordinate) {
        double minimum = chunkCoordinate * 16.0 + 0.25;
        double maximum = minimum + 15.5;
        return Math.max(minimum, Math.min(maximum, coordinate));
    }

    private Component guideText(Player player, GuideTextState state) {
        if (state.distance() < 0L) {
            return messages.component(
                    player,
                    state.key(),
                    Placeholder.unparsed("world", state.detail())
            );
        }
        TagResolver distance = Placeholder.component(
                "distance",
                Component.text(state.distance(), distanceColor(state.distance()))
        );
        if ("death-recovery.guide-portal".equals(state.key())) {
            return messages.component(
                    player,
                    state.key(),
                    distance,
                    Placeholder.unparsed("x", Long.toString(state.x())),
                    Placeholder.unparsed("z", Long.toString(state.z()))
            );
        }
        return messages.component(player, state.key(), distance);
    }

    private void toggleFloatingGuide(Player player) {
        if (floatingGuidePlayers.contains(player.getUniqueId())) {
            disableFloatingGuide(player, true);
        } else {
            enableFloatingGuide(player, true);
        }
    }

    private boolean enableFloatingGuide(Player player, boolean announce) {
        if (!settings.current().deathRecoveryEnabled()
                || !settings.current().deathFloatingGuideEnabled()) {
            if (announce) {
                messages.send(player, "death-recovery.guide-unavailable");
            }
            return false;
        }
        Optional<DeathMarker> marker = activeMarker(player.getUniqueId());
        if (marker.isEmpty()) {
            if (announce) {
                messages.send(player, "death-recovery.none");
            }
            return false;
        }
        floatingGuidePlayers.add(player.getUniqueId());
        ensureGuideTask();
        updateFloatingGuide(player, marker.orElseThrow());
        if (announce) {
            messages.send(player, "death-recovery.guide-enabled");
        }
        return true;
    }

    private void disableFloatingGuide(Player player, boolean announce) {
        boolean active = floatingGuidePlayers.remove(player.getUniqueId());
        removeFloatingGuideDisplay(player.getUniqueId());
        if (announce) {
            messages.send(
                    player,
                    active
                            ? "death-recovery.guide-disabled"
                            : "death-recovery.guide-already-disabled"
            );
        }
    }

    private void stopFloatingGuide(UUID playerId) {
        floatingGuidePlayers.remove(playerId);
        pendingGuideRefreshes.remove(playerId);
        removeFloatingGuideDisplay(playerId);
        stopGuideTaskIfIdle();
    }

    private void removeFloatingGuideDisplay(UUID playerId) {
        floatingGuideTextStates.remove(playerId);
        floatingGuidePositions.remove(playerId);
        TextDisplay display = floatingGuides.remove(playerId);
        if (display != null) {
            display.remove();
        }
    }

    private void clearFloatingGuides() {
        List.copyOf(floatingGuides.values()).forEach(TextDisplay::remove);
        floatingGuides.clear();
        floatingGuideTextStates.clear();
        floatingGuidePositions.clear();
        floatingGuidePlayers.clear();
        pendingGuideRefreshes.clear();
        stopGuideTaskIfIdle();
    }

    private Location frontOfPlayer(Player player) {
        Location eye = player.getEyeLocation();
        return eye.clone().add(
                eye.getDirection().normalize().multiply(settings.current().deathFloatingGuideOffset())
        );
    }

    static Location directionalGuideLocation(Location origin, Location target, double offset) {
        Vector direction = target.toVector().subtract(origin.toVector());
        double lengthSquared = direction.lengthSquared();
        if (lengthSquared < 0.000_001) {
            return origin.clone();
        }
        if (lengthSquared <= offset * offset) {
            return target.clone();
        }
        return origin.clone().add(direction.normalize().multiply(offset));
    }

    private double horizontalDistance(Location first, Location second) {
        double x = second.getX() - first.getX();
        double z = second.getZ() - first.getZ();
        return Math.sqrt(x * x + z * z);
    }

    private NamedTextColor distanceColor(long distance) {
        return distance <= 20L
                ? NamedTextColor.GREEN
                : distance <= 100L ? NamedTextColor.YELLOW : NamedTextColor.WHITE;
    }

    private Optional<DeathMarker> activeMarker(UUID playerId) {
        DeathMarker marker = markers.get(playerId);
        if (marker != null && marker.expired(clock.instant())) {
            markers.remove(playerId);
            notifications.notify(
                    playerId,
                    NotificationType.DEATH_MARKER_EXPIRED,
                    "",
                    marker.worldName()
            );
            stopFloatingGuide(playerId);
            persist();
            marker = null;
            stopExpiryTaskIfIdle();
        }
        return Optional.ofNullable(marker);
    }

    private boolean isLegacyRecoveryCompass(ItemStack item, UUID playerId) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return playerId.toString().equals(item.getItemMeta().getPersistentDataContainer().get(
                legacyCompassOwnerKey,
                PersistentDataType.STRING
        ));
    }

    private void removeLegacyRecoveryCompasses(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (isLegacyRecoveryCompass(item, player.getUniqueId())) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private void sendLocation(Player player, DeathMarker marker, String key) {
        long minutes = Math.max(
                1L,
                Duration.between(clock.instant(), marker.expiresAt()).toMinutes()
        );
        messages.send(
                player,
                MessageService.plural(key, minutes),
                Placeholder.unparsed("world", marker.worldName()),
                Placeholder.unparsed("x", Long.toString(Math.round(marker.x()))),
                Placeholder.unparsed("y", Long.toString(Math.round(marker.y()))),
                Placeholder.unparsed("z", Long.toString(Math.round(marker.z()))),
                Placeholder.unparsed("minutes", Long.toString(minutes))
        );
    }

    private void removeExpired() {
        Instant now = clock.instant();
        java.util.List<DeathMarker> expired = markers.values().stream()
                .filter(marker -> marker.expired(now))
                .toList();
        if (!expired.isEmpty()) {
            expired.forEach(marker -> {
                markers.remove(marker.playerId(), marker);
                notifications.notify(
                        marker.playerId(),
                        NotificationType.DEATH_MARKER_EXPIRED,
                        "",
                        marker.worldName()
                );
                stopFloatingGuide(marker.playerId());
            });
            persist();
            stopExpiryTaskIfIdle();
        }
    }

    private void reached(Player player, DeathMarker marker) {
        if (!markers.remove(player.getUniqueId(), marker)) {
            return;
        }
        persist();
        stopFloatingGuide(player.getUniqueId());
        messages.send(player, "death-recovery.reached");
        feedback.play(player, "death-reached");
        stopExpiryTaskIfIdle();
    }

    private void dismiss(Player player) {
        DeathMarker marker = markers.remove(player.getUniqueId());
        if (marker == null) {
            messages.send(player, "death-recovery.none");
            return;
        }
        persist();
        stopFloatingGuide(player.getUniqueId());
        messages.send(player, "death-recovery.dismissed");
        stopExpiryTaskIfIdle();
    }

    static String causeKey(String cause) {
        if (cause == null || cause.isBlank() || cause.equalsIgnoreCase("UNKNOWN")) {
            return "death-recovery.cause.unknown";
        }
        try {
            return "death-recovery.cause."
                    + org.bukkit.event.entity.EntityDamageEvent.DamageCause.valueOf(
                    cause.toUpperCase(Locale.ROOT)
            ).name().toLowerCase(Locale.ROOT).replace('_', '-');
        } catch (IllegalArgumentException exception) {
            return "death-recovery.cause.unknown";
        }
    }

    static String directionArrow(Location playerLocation, Location target) {
        double dx = target.getX() - playerLocation.getX();
        double dz = target.getZ() - playerLocation.getZ();
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double difference = ((targetYaw - playerLocation.getYaw() + 540.0) % 360.0) - 180.0;
        if (difference >= -22.5 && difference < 22.5) {
            return "↑";
        }
        if (difference >= 22.5 && difference < 67.5) {
            return "↗";
        }
        if (difference >= 67.5 && difference < 112.5) {
            return "→";
        }
        if (difference >= 112.5 && difference < 157.5) {
            return "↘";
        }
        if (difference >= 157.5 || difference < -157.5) {
            return "↓";
        }
        if (difference >= -157.5 && difference < -112.5) {
            return "↙";
        }
        if (difference >= -112.5 && difference < -67.5) {
            return "←";
        }
        return "↖";
    }

    private Optional<PortalHint> portalHint(Player player, Location target) {
        World.Environment current = player.getWorld().getEnvironment();
        World.Environment destination = target.getWorld().getEnvironment();
        if (current == World.Environment.NETHER && destination == World.Environment.NORMAL) {
            return Optional.of(new PortalHint(
                    Math.round(target.getX() / 8.0),
                    Math.round(target.getZ() / 8.0)
            ));
        }
        if (current == World.Environment.NORMAL && destination == World.Environment.NETHER) {
            return Optional.of(new PortalHint(
                    Math.round(target.getX() * 8.0),
                    Math.round(target.getZ() * 8.0)
            ));
        }
        return Optional.empty();
    }

    private void persist() {
        writer.submit(List.copyOf(markers.values()));
    }

    private void ensureExpiryTask() {
        if (expiryTask == null && !markers.isEmpty()) {
            expiryTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin,
                    failures == null
                            ? this::removeExpired
                            : failures.guard("death marker expiry", this::removeExpired),
                    20L,
                    20L
            );
        }
    }

    private void stopExpiryTaskIfIdle() {
        if (markers.isEmpty() && expiryTask != null) {
            expiryTask.cancel();
            expiryTask = null;
        }
    }

    private void ensureGuideTask() {
        if (guideTask == null && !floatingGuidePlayers.isEmpty()) {
            guideTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin,
                    failures == null
                            ? this::updateFloatingGuides
                            : failures.guard("death guide refresh", this::updateFloatingGuides),
                    GUIDE_UPDATE_TICKS,
                    GUIDE_UPDATE_TICKS
            );
        }
    }

    private void stopGuideTaskIfIdle() {
        if (floatingGuidePlayers.isEmpty() && guideTask != null) {
            guideTask.cancel();
            guideTask = null;
        }
    }

    private record PortalHint(long x, long z) {
    }

    private record GuideTextState(String key, String detail, long distance, long x, long z) {

        private GuideTextState(String key, String detail, long distance) {
            this(key, detail, distance, 0L, 0L);
        }
    }

    private record GuidePosition(UUID worldId, double x, double y, double z, float yaw, float pitch) {

        private static GuidePosition at(Location location) {
            return new GuidePosition(
                    location.getWorld().getUID(),
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    location.getYaw(),
                    location.getPitch()
            );
        }

        private boolean near(GuidePosition other) {
            if (!worldId.equals(other.worldId)) {
                return false;
            }
            double deltaX = x - other.x;
            double deltaY = y - other.y;
            double deltaZ = z - other.z;
            return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
                    < GUIDE_POSITION_EPSILON_SQUARED
                    && angleDifference(yaw, other.yaw) < GUIDE_ROTATION_EPSILON
                    && Math.abs(pitch - other.pitch) < GUIDE_ROTATION_EPSILON;
        }

        private static float angleDifference(float first, float second) {
            return Math.abs(((first - second + 540.0f) % 360.0f) - 180.0f);
        }
    }
}

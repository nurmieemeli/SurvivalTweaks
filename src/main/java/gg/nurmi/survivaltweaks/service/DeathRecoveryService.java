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
import org.bukkit.Material;
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
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
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

    private static final long GUIDE_UPDATE_TICKS = 1L;
    private static final double REACHED_DISTANCE = 3.0;

    private final JavaPlugin plugin;
    private final DeathMarkerStore store;
    private final MessageService messages;
    private final SettingsService settings;
    private final ActionBarService actionBars;
    private final Clock clock;
    private final FeedbackService feedback;
    private final PlayerExperienceService experience;
    private final NotificationService notifications;
    private final OnboardingService onboarding;
    private final CoalescingSnapshotWriter<List<DeathMarker>> writer;
    private final NamespacedKey compassOwnerKey;
    private final NamespacedKey compassMarkerKey;
    private final Map<UUID, DeathMarker> markers = new HashMap<>();
    private final Map<UUID, Instant> compassCooldowns = new HashMap<>();
    private final Set<UUID> floatingGuidePlayers = new HashSet<>();
    private final Map<UUID, TextDisplay> floatingGuides = new HashMap<>();
    private final Map<UUID, GuideTextState> floatingGuideTextStates = new HashMap<>();
    private final Map<UUID, GuidePosition> floatingGuidePositions = new HashMap<>();
    private BukkitTask statusTask;
    private BukkitTask guideTask;
    private long guideUpdates;

    public DeathRecoveryService(
            JavaPlugin plugin,
            DeathMarkerStore store,
            MessageService messages,
            SettingsService settings,
            ActionBarService actionBars,
            Clock clock,
            FeedbackService feedback,
            PlayerExperienceService experience,
            NotificationService notifications,
            OnboardingService onboarding
    ) {
        this.plugin = plugin;
        this.store = store;
        this.messages = messages;
        this.settings = settings;
        this.actionBars = actionBars;
        this.clock = clock;
        this.feedback = feedback;
        this.experience = experience;
        this.notifications = notifications;
        this.onboarding = onboarding;
        this.writer = new CoalescingSnapshotWriter<>(
                "SurvivalTweaks-death-marker-writer",
                "death markers",
                store::save,
                plugin.getLogger() == null
                        ? Logger.getLogger(DeathRecoveryService.class.getName())
                        : plugin.getLogger()
        );
        this.compassOwnerKey = new NamespacedKey(plugin, "death-compass-owner");
        this.compassMarkerKey = new NamespacedKey(plugin, "death-compass-marker");
        store.load().forEach(marker -> {
            if (!marker.expired(clock.instant())) {
                markers.put(marker.playerId(), marker);
            }
        });
        ensureStatusTask();
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
        DeathMarker previous = markers.get(player.getUniqueId());
        if (previous != null) {
            event.getDrops().removeIf(item ->
                    isOwnedRecoveryCompass(item, player.getUniqueId()) && matchesMarker(item, previous));
            removeRecoveryCompasses(player, previous);
        }
        removeFloatingGuideDisplay(player.getUniqueId());
        markers.put(player.getUniqueId(), marker);
        ensureStatusTask();
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
                    if (settings.current().deathCompassOnRespawn()
                            && experience.preferences(player).automaticRecoveryCompass()) {
                        giveCompass(player, false);
                    }
                    if (settings.current().deathFloatingGuideEnabled()
                            && (settings.current().deathFloatingGuideAutomaticOnRespawn()
                            || floatingGuidePlayers.contains(player.getUniqueId()))) {
                        enableFloatingGuide(player, false);
                    }
                },
                1L
        );
    }

    @EventHandler
    public void onCompassUse(PlayerInteractEvent event) {
        if (!settings.current().deathRecoveryEnabled()
                || event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        ItemStack item = event.getItem();
        Player player = event.getPlayer();
        Optional<DeathMarker> marker = activeMarker(player.getUniqueId());
        if (!isOwnedRecoveryCompass(item, player.getUniqueId())
                || marker.isEmpty()
                || !matchesMarker(item, marker.orElseThrow())) {
            return;
        }
        event.setCancelled(true);
        toggleFloatingGuide(player);
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
        giveCompass(player, true);
        if (settings.current().deathFloatingGuideEnabled()) {
            messages.send(
                    player,
                    floatingGuidePlayers.contains(player.getUniqueId())
                            ? "death-recovery.guide-prompt-enabled"
                            : "death-recovery.guide-prompt-disabled"
            );
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
        if (statusTask != null) {
            statusTask.cancel();
            statusTask = null;
        }
        if (guideTask != null) {
            guideTask.cancel();
            guideTask = null;
        }
        clearFloatingGuides();
        persist();
        writer.close();
        markers.clear();
        compassCooldowns.clear();
    }

    private void giveCompass(Player player, boolean enforceCooldown) {
        Optional<DeathMarker> selected = activeMarker(player.getUniqueId());
        if (selected.isEmpty()) {
            return;
        }
        if (hasCompass(player)) {
            if (enforceCooldown) {
                messages.send(player, "death-recovery.compass-present");
            }
            return;
        }
        Instant now = clock.instant();
        Instant available = compassCooldowns.get(player.getUniqueId());
        if (enforceCooldown && available != null && available.isAfter(now)) {
            long seconds = secondsUntil(now, available);
            messages.send(
                    player,
                    MessageService.plural("death-recovery.compass-cooldown", seconds),
                    Placeholder.unparsed("seconds", Long.toString(seconds))
            );
            return;
        }
        DeathMarker marker = selected.orElseThrow();
        ItemStack compass = compass(player, marker);
        player.getInventory().addItem(compass).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover)
        );
        compassCooldowns.put(
                player.getUniqueId(),
                now.plus(settings.current().deathCompassCooldown())
        );
        messages.send(player, "death-recovery.compass-given");
    }

    private ItemStack compass(Player player, DeathMarker marker) {
        ItemStack item = new ItemStack(Material.COMPASS);
        CompassMeta meta = (CompassMeta) item.getItemMeta();
        marker.resolve(plugin.getServer()).ifPresent(location -> {
            meta.setLodestone(location);
            meta.setLodestoneTracked(false);
        });
        meta.displayName(messages.component(player, "death-recovery.compass-name"));
        Component locationLore = messages.component(
                player,
                "death-recovery.compass-lore",
                Placeholder.unparsed("world", marker.worldName()),
                Placeholder.unparsed("x", Long.toString(Math.round(marker.x()))),
                Placeholder.unparsed("y", Long.toString(Math.round(marker.y()))),
                Placeholder.unparsed("z", Long.toString(Math.round(marker.z())))
        );
        if (settings.current().deathFloatingGuideEnabled()) {
            meta.lore(List.of(
                    locationLore,
                    messages.component(player, "death-recovery.compass-guide-lore")
            ));
        } else {
            meta.lore(List.of(locationLore));
        }
        meta.getPersistentDataContainer().set(
                compassOwnerKey,
                PersistentDataType.STRING,
                player.getUniqueId().toString()
        );
        meta.getPersistentDataContainer().set(
                compassMarkerKey,
                PersistentDataType.STRING,
                marker.createdAt().toString()
        );
        item.setItemMeta(meta);
        return item;
    }

    private void updateHeldCompasses() {
        if (!settings.current().deathRecoveryEnabled()) {
            return;
        }
        removeExpired();
        if (!settings.current().actionBarEnabled()) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!experience.actionBars(player)) {
                continue;
            }
            ItemStack held = recoveryCompassInHands(player);
            if (held == null) {
                continue;
            }
            Optional<DeathMarker> selected = activeMarker(player.getUniqueId());
            if (selected.isEmpty() || !matchesMarker(held, selected.orElseThrow())) {
                actionBars.show(
                        player,
                        messages.component(player, "death-recovery.actionbar-expired"),
                        ActionBarService.DEATH_MARKER_PRIORITY,
                        Duration.ofMillis(1_250)
                );
                continue;
            }
            DeathMarker marker = selected.orElseThrow();
            Optional<Location> resolved = marker.resolve(plugin.getServer());
            if (resolved.isPresent()
                    && resolved.orElseThrow().getWorld().getUID().equals(player.getWorld().getUID())) {
                if (player.isDead()) {
                    continue;
                }
                Location playerLocation = player.getLocation();
                long distance = Math.round(playerLocation.distance(resolved.orElseThrow()));
                if (distance <= 3L) {
                    reached(player, marker);
                    continue;
                }
                String arrow = directionArrow(playerLocation, resolved.orElseThrow());
                NamedTextColor color = distance <= 20L
                        ? NamedTextColor.GREEN
                        : distance <= 100L ? NamedTextColor.YELLOW : NamedTextColor.WHITE;
                actionBars.show(
                        player,
                        messages.component(
                                player,
                                "death-recovery.actionbar-navigation",
                                Placeholder.unparsed("arrow", arrow),
                                Placeholder.component("distance", Component.text(distance, color))
                        ),
                        ActionBarService.DEATH_MARKER_PRIORITY,
                        Duration.ofMillis(1_250)
                );
                if (distance <= 20L) {
                    feedback.play(player, "death-nearby", Math.max(0.4, (21.0 - distance) / 10.0));
                }
            } else if (resolved.isPresent()) {
                Optional<PortalHint> portal = portalHint(player, resolved.orElseThrow());
                if (portal.isEmpty()) {
                    actionBars.show(
                            player,
                            messages.component(
                                    player,
                                    "death-recovery.actionbar-world",
                                    Placeholder.unparsed("world", marker.worldName()),
                                    Placeholder.unparsed("x", Long.toString(Math.round(marker.x()))),
                                    Placeholder.unparsed("z", Long.toString(Math.round(marker.z())))
                            ),
                            ActionBarService.DEATH_MARKER_PRIORITY,
                            Duration.ofMillis(1_250)
                    );
                    continue;
                }
                PortalHint hint = portal.orElseThrow();
                actionBars.show(
                        player,
                        messages.component(
                                player,
                                "death-recovery.actionbar-portal",
                                Placeholder.unparsed("world", marker.worldName()),
                                Placeholder.unparsed("x", Long.toString(hint.x())),
                                Placeholder.unparsed("z", Long.toString(hint.z()))
                        ),
                        ActionBarService.DEATH_MARKER_PRIORITY,
                        Duration.ofMillis(1_250)
                );
            } else {
                actionBars.show(
                        player,
                        messages.component(
                                player,
                                "death-recovery.actionbar-world",
                                Placeholder.unparsed("world", marker.worldName()),
                                Placeholder.unparsed("x", Long.toString(Math.round(marker.x()))),
                                Placeholder.unparsed("z", Long.toString(Math.round(marker.z())))
                        ),
                        ActionBarService.DEATH_MARKER_PRIORITY,
                        Duration.ofMillis(1_250)
                );
            }
        }
    }

    private void updateFloatingGuides() {
        guideUpdates++;
        if (!settings.current().deathRecoveryEnabled()
                || !settings.current().deathFloatingGuideEnabled()) {
            clearFloatingGuides();
            return;
        }
        for (UUID playerId : List.copyOf(floatingGuidePlayers)) {
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
            updateFloatingGuide(player, selected.orElseThrow());
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
                    new GuideTextState("death-recovery.guide-portal", "", distance)
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
                && guideUpdates % (20L / GUIDE_UPDATE_TICKS) == 0
                && recoveryCompassInHands(player) == null) {
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
        TextDisplay display = floatingGuides.get(playerId);
        if (display == null
                || !display.isValid()
                || !display.getWorld().getUID().equals(location.getWorld().getUID())) {
            removeFloatingGuideDisplay(playerId);
            Component text = guideText(player, textState);
            display = location.getWorld().spawn(location, TextDisplay.class, entity -> {
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
                entity.setTeleportDuration(1);
                entity.text(text);
            });
            floatingGuides.put(playerId, display);
            floatingGuideTextStates.put(playerId, textState);
            floatingGuidePositions.put(playerId, GuidePosition.at(location));
            player.showEntity(plugin, display);
            return;
        }
        GuideTextState previousText = floatingGuideTextStates.put(playerId, textState);
        if (!textState.equals(previousText)) {
            display.text(guideText(player, textState));
        }
        GuidePosition position = GuidePosition.at(location);
        if (!position.equals(floatingGuidePositions.put(playerId, position))) {
            display.teleport(location);
        }
    }

    private Component guideText(Player player, GuideTextState state) {
        if (state.distance() < 0L) {
            return messages.component(
                    player,
                    state.key(),
                    Placeholder.unparsed("world", state.detail())
            );
        }
        return messages.component(
                player,
                state.key(),
                Placeholder.component(
                        "distance",
                        Component.text(state.distance(), distanceColor(state.distance()))
                )
        );
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
            Player online = plugin.getServer().getPlayer(playerId);
            if (online != null) {
                removeRecoveryCompasses(online, marker);
            }
            stopFloatingGuide(playerId);
            persist();
            marker = null;
            stopStatusTaskIfIdle();
        }
        return Optional.ofNullable(marker);
    }

    private boolean hasCompass(Player player) {
        Optional<DeathMarker> marker = activeMarker(player.getUniqueId());
        if (marker.isEmpty()) {
            return false;
        }
        for (ItemStack item : player.getInventory().getContents()) {
            if (isOwnedRecoveryCompass(item, player.getUniqueId())
                    && matchesMarker(item, marker.orElseThrow())) {
                return true;
            }
        }
        return false;
    }

    private boolean isOwnedRecoveryCompass(ItemStack item, UUID playerId) {
        if (item == null || !(item.getItemMeta() instanceof CompassMeta meta)) {
            return false;
        }
        return playerId.toString().equals(meta.getPersistentDataContainer().get(
                compassOwnerKey,
                PersistentDataType.STRING
        ));
    }

    private ItemStack recoveryCompassInHands(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (isOwnedRecoveryCompass(mainHand, player.getUniqueId())) {
            return mainHand;
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        return isOwnedRecoveryCompass(offHand, player.getUniqueId()) ? offHand : null;
    }

    private boolean matchesMarker(ItemStack item, DeathMarker marker) {
        if (!(item.getItemMeta() instanceof CompassMeta meta)) {
            return false;
        }
        return marker.createdAt().toString().equals(meta.getPersistentDataContainer().get(
                compassMarkerKey,
                PersistentDataType.STRING
        ));
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
                Player online = plugin.getServer().getPlayer(marker.playerId());
                if (online != null) {
                    removeRecoveryCompasses(online, marker);
                }
                stopFloatingGuide(marker.playerId());
            });
            persist();
            stopStatusTaskIfIdle();
        }
    }

    private void reached(Player player, DeathMarker marker) {
        if (!markers.remove(player.getUniqueId(), marker)) {
            return;
        }
        persist();
        removeRecoveryCompasses(player, marker);
        stopFloatingGuide(player.getUniqueId());
        actionBars.clear(player, ActionBarService.DEATH_MARKER_PRIORITY);
        messages.send(player, "death-recovery.reached");
        feedback.play(player, "death-reached");
        stopStatusTaskIfIdle();
    }

    private void dismiss(Player player) {
        DeathMarker marker = markers.remove(player.getUniqueId());
        if (marker == null) {
            messages.send(player, "death-recovery.none");
            return;
        }
        persist();
        removeRecoveryCompasses(player, marker);
        stopFloatingGuide(player.getUniqueId());
        actionBars.clear(player, ActionBarService.DEATH_MARKER_PRIORITY);
        messages.send(player, "death-recovery.dismissed");
        stopStatusTaskIfIdle();
    }

    private void removeRecoveryCompasses(Player player, DeathMarker marker) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (isOwnedRecoveryCompass(item, player.getUniqueId()) && matchesMarker(item, marker)) {
                player.getInventory().setItem(slot, null);
            }
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (isOwnedRecoveryCompass(offHand, player.getUniqueId()) && matchesMarker(offHand, marker)) {
            player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        }
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

    private long secondsUntil(Instant now, Instant future) {
        return Math.max(1L, (Duration.between(now, future).toMillis() + 999L) / 1_000L);
    }

    private void persist() {
        writer.submit(List.copyOf(markers.values()));
    }

    private void ensureStatusTask() {
        if (statusTask == null && !markers.isEmpty()) {
            statusTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin,
                    this::updateHeldCompasses,
                    20L,
                    20L
            );
        }
    }

    private void stopStatusTaskIfIdle() {
        if (markers.isEmpty() && statusTask != null) {
            statusTask.cancel();
            statusTask = null;
        }
    }

    private void ensureGuideTask() {
        if (guideTask == null && !floatingGuidePlayers.isEmpty()) {
            guideTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin,
                    this::updateFloatingGuides,
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

    private record GuideTextState(String key, String detail, long distance) {
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
    }
}

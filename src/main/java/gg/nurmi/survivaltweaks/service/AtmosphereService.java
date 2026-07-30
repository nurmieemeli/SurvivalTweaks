package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.SurvivalTweaks;
import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.PlayerPreferences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class AtmosphereService implements Listener, AutoCloseable {

    private static final Set<Material> RARE_MATERIALS = EnumSet.of(
            Material.DIAMOND,
            Material.DIAMOND_BLOCK,
            Material.NETHERITE_INGOT,
            Material.NETHERITE_SCRAP,
            Material.NETHERITE_BLOCK,
            Material.ANCIENT_DEBRIS,
            Material.TOTEM_OF_UNDYING,
            Material.ENCHANTED_GOLDEN_APPLE,
            Material.ELYTRA
    );

    private final SurvivalTweaks plugin;
    private final SettingsService settings;
    private final MessageService messages;
    private final ActionBarService actionBars;
    private final PlayerExperienceService experience;
    private final PerformanceGovernor governor;
    private final TickWorkBudget workBudget;
    private final TaskFailureIsolation failures;
    private final Map<UUID, Long> durabilityAlertCooldowns = new HashMap<>();
    private final Set<BukkitTask> transientTasks = new HashSet<>();
    private BukkitTask ambientTask;
    private int ambientPhase;
    private long ambientCycles;

    public AtmosphereService(
            SurvivalTweaks plugin,
            SettingsService settings,
            MessageService messages,
            ActionBarService actionBars,
            PlayerExperienceService experience
    ) {
        this(plugin, settings, messages, actionBars, experience, null, null, null);
    }

    public AtmosphereService(
            SurvivalTweaks plugin,
            SettingsService settings,
            MessageService messages,
            ActionBarService actionBars,
            PlayerExperienceService experience,
            PerformanceGovernor governor,
            TickWorkBudget workBudget,
            TaskFailureIsolation failures
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.actionBars = actionBars;
        this.experience = experience;
        this.governor = governor;
        this.workBudget = workBudget;
        this.failures = failures;
        startAmbientTask();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        PluginSettings current = settings.current();
        if (!current.atmosphereDurabilityWarning()
                || !current.actionBarEnabled()
                || !experience.actionBars(event.getPlayer())) {
            return;
        }

        ItemStack item = event.getItem();
        short maxDurability = item.getType().getMaxDurability();
        if (maxDurability <= 0) {
            return;
        }

        if (item.getItemMeta() instanceof Damageable damageable) {
            int currentDamage = damageable.getDamage();
            int remaining = maxDurability - (currentDamage + event.getDamage());

            if (remaining > 0 && (remaining <= 15 || remaining * 10 <= maxDurability)) {
                Player player = event.getPlayer();
                long now = System.currentTimeMillis();
                Long lastAlert = durabilityAlertCooldowns.get(player.getUniqueId());
                if (lastAlert == null || (now - lastAlert) > 10_000L) {
                    durabilityAlertCooldowns.put(player.getUniqueId(), now);
                    playSound(player, player.getLocation(), Sound.ITEM_SHIELD_BREAK, 0.6f, 0.7f);
                    actionBars.show(
                            player,
                            messages.component(
                                    player,
                                    "atmosphere.low-durability",
                                    Placeholder.component(
                                            "item",
                                            Component.translatable(item.getType().translationKey())
                                    ),
                                    Placeholder.unparsed("uses", Integer.toString(remaining))
                            ),
                            5,
                            java.time.Duration.ofSeconds(3)
                    );
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        PluginSettings current = settings.current();
        if (event.getEntity() instanceof Player player) {
            if (current.atmosphereLowHealthHeartbeat()) {
                double finalHealth = player.getHealth() - event.getFinalDamage();
                if (finalHealth > 0 && finalHealth <= 6.0) {
                    playSound(
                            player,
                            player.getLocation(),
                            Sound.ENTITY_WARDEN_HEARTBEAT,
                            0.8f,
                            1.2f
                    );
                }
            }

            if (current.atmosphereDrowningGasp()) {
                if (event.getCause() == EntityDamageEvent.DamageCause.DROWNING || event.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION) {
                    playSound(
                            player,
                            player.getLocation(),
                            Sound.ENTITY_PLAYER_HURT_DROWN,
                            0.7f,
                            1.0f
                    );
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvilUse(org.bukkit.event.inventory.InventoryClickEvent event) {
        PluginSettings current = settings.current();
        if (!current.atmosphereAnvilSparks()) {
            return;
        }

        if (event.getInventory() instanceof org.bukkit.inventory.AnvilInventory && event.getRawSlot() == 2 && event.getCurrentItem() != null) {
            if (event.getWhoClicked() instanceof Player player) {
                playSound(player, player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.2f);
                if (particlesEnabled(player)) {
                    player.spawnParticle(
                            Particle.ELECTRIC_SPARK,
                            player.getLocation().add(0, 1.0, 0),
                            particleCount(player, 10),
                            0.3,
                            0.3,
                            0.3,
                            0.05
                    );
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpyglassUse(org.bukkit.event.player.PlayerInteractEvent event) {
        PluginSettings current = settings.current();
        if (!current.atmosphereSpyglassEffects()) {
            return;
        }

        if (event.hasItem()
                && event.getItem().getType() == Material.SPYGLASS
                && (event.getAction() == Action.RIGHT_CLICK_AIR
                || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            Player player = event.getPlayer();
            playSound(player, player.getLocation(), Sound.ITEM_SPYGLASS_USE, 0.8f, 1.2f);
            if (particlesEnabled(player)) {
                player.spawnParticle(
                        Particle.END_ROD,
                        player.getEyeLocation().add(player.getLocation().getDirection().multiply(0.5)),
                        particleCount(player, 2),
                        0.1,
                        0.1,
                        0.1,
                        0.01
                );
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        PluginSettings current = settings.current();
        if (!current.atmosphereRarePickupEffects()) {
            return;
        }

        if (event.getEntity() instanceof Player player) {
            Material type = event.getItem().getItemStack().getType();
            if (isRareMaterial(type)) {
                playSound(
                        player,
                        player.getLocation(),
                        Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                        0.7f,
                        1.5f
                );
                if (particlesEnabled(player)) {
                    player.spawnParticle(
                            Particle.WAX_ON,
                            player.getLocation().add(0, 1, 0),
                            particleCount(player, 10),
                            0.3,
                            0.3,
                            0.3
                    );
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShieldBlock(EntityDamageByEntityEvent event) {
        PluginSettings current = settings.current();
        if (!current.atmosphereShieldBlockEffects()) {
            return;
        }

        if (event.getEntity() instanceof Player player && player.isBlocking()) {
            playSound(player, player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.8f, 1.0f);
            if (particlesEnabled(player)) {
                player.spawnParticle(
                        Particle.ELECTRIC_SPARK,
                        player.getLocation().add(0, 1.2, 0),
                        particleCount(player, 8),
                        0.3,
                        0.3,
                        0.3,
                        0.05
                );
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancementDone(org.bukkit.event.player.PlayerAdvancementDoneEvent event) {
        PluginSettings current = settings.current();
        if (!current.atmosphereAdvancementEffects()) {
            return;
        }

        if (event.getAdvancement().getDisplay() != null) {
            Player player = event.getPlayer();
            playSound(
                    player,
                    player.getLocation(),
                    Sound.UI_TOAST_CHALLENGE_COMPLETE,
                    0.6f,
                    1.2f
            );
            if (particlesEnabled(player)) {
                player.spawnParticle(
                        Particle.TOTEM_OF_UNDYING,
                        player.getLocation().add(0, 2.0, 0),
                        particleCount(player, 16),
                        0.4,
                        0.4,
                        0.4,
                        0.1
                );
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!settings.current().atmosphereDeathSiteWisps()) {
            return;
        }

        Location deathSite = event.getEntity().getLocation().clone().add(0, 1.0, 0);
        AtomicInteger remainingPulses = new AtomicInteger(8);
        scheduleTransientTask(scheduledTask -> {
            if (!settings.current().atmosphereDeathSiteWisps()
                    || remainingPulses.getAndDecrement() <= 0) {
                finishTransientTask(scheduledTask);
                return;
            }
            for (Player viewer : deathSite.getWorld().getPlayers()) {
                if (!particlesEnabled(viewer)
                        || viewer.getLocation().distanceSquared(deathSite) > 64.0 * 64.0) {
                    continue;
                }
                viewer.spawnParticle(
                        Particle.SOUL,
                        deathSite,
                        particleCount(viewer, 4),
                        0.4,
                        0.7,
                        0.4,
                        0.01
                );
                viewer.spawnParticle(
                        Particle.END_ROD,
                        deathSite,
                        particleCount(viewer, 2),
                        0.25,
                        0.5,
                        0.25,
                        0.005
                );
            }
        }, 1L, 10L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        durabilityAlertCooldowns.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLightningStrike(org.bukkit.event.weather.LightningStrikeEvent event) {
        PluginSettings current = settings.current();
        if (!current.atmosphereThunderEffects()) {
            return;
        }

        Location strikeLoc = event.getLightning().getLocation();
        for (Player player : strikeLoc.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(strikeLoc) <= 100 * 100) {
                playSound(
                        player,
                        player.getLocation(),
                        Sound.ENTITY_LIGHTNING_BOLT_THUNDER,
                        0.9f,
                        0.6f
                );
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnderChestInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        PluginSettings current = settings.current();
        if (!current.atmosphereEnderChestEffects()) {
            return;
        }

        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.ENDER_CHEST) {
            Location loc = event.getClickedBlock().getLocation().add(0.5, 1.0, 0.5);
            Player player = event.getPlayer();
            if (particlesEnabled(player)) {
                player.spawnParticle(
                        Particle.PORTAL,
                        loc,
                        particleCount(player, 12),
                        0.2,
                        0.2,
                        0.2,
                        0.5
                );
            }
            playSound(player, loc, Sound.BLOCK_PORTAL_AMBIENT, 0.3f, 1.4f);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArrowShoot(org.bukkit.event.entity.EntityShootBowEvent event) {
        PluginSettings current = settings.current();
        if (!current.atmosphereArrowTrails()) {
            return;
        }

        if (event.getProjectile() instanceof org.bukkit.entity.AbstractArrow arrow && arrow.isCritical()) {
            scheduleTransientTask(scheduledTask -> {
                if (!arrow.isValid() || arrow.isDead() || arrow.isInBlock()) {
                    finishTransientTask(scheduledTask);
                    return;
                }
                Location location = arrow.getLocation();
                for (Player viewer : arrow.getTrackedBy()) {
                    if (!particlesEnabled(viewer)) {
                        continue;
                    }
                    viewer.spawnParticle(
                            Particle.CRIT,
                            location,
                            particleCount(viewer, 2),
                            0.05,
                            0.05,
                            0.05,
                            0.01
                    );
                }
            }, 1L, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSculkShriek(org.bukkit.event.world.GenericGameEvent event) {
        if (event.getEvent() == org.bukkit.GameEvent.SHRIEK
                && event.getEntity() instanceof Player player
                && sculkWarningEnabled(player)) {
            int warningLevel = Math.max(1, Math.min(4, player.getWardenWarningLevel()));
            actionBars.show(
                    player,
                    messages.component(player, "atmosphere.sculk-warning", Placeholder.unparsed("level", String.valueOf(warningLevel))),
                    40,
                    java.time.Duration.ofSeconds(4)
            );
        }
    }

    boolean sculkWarningEnabled(Player player) {
        PluginSettings current = settings.current();
        return current.atmosphereSculkWarningActionbar()
                && current.actionBarEnabled()
                && experience.actionBars(player);
    }

    public boolean isRareMaterial(Material material) {
        return RARE_MATERIALS.contains(material);
    }

    private void startAmbientTask() {
        Runnable ambient = () -> {
            PluginSettings current = settings.current();
            int phase = ambientPhase;
            ambientPhase = (ambientPhase + 1) & 3;
            long cycle = ambientCycles++;
            if (!current.atmosphereAmbientEffects()) {
                return;
            }

            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (Math.floorMod(player.getUniqueId().hashCode(), 4) != phase) {
                    continue;
                }
                int divisor = governor == null ? 1 : governor.cosmeticDivisor();
                if (Math.floorMod(cycle / 4L + player.getUniqueId().hashCode(), divisor) != 0
                        || (workBudget != null
                        && !workBudget.tryAcquire(TickWorkBudget.Lane.ATMOSPHERE, 1))) {
                    continue;
                }
                ThreadLocalRandom random = ThreadLocalRandom.current();
                Location loc = player.getLocation();
                long time = loc.getWorld().getTime();
                PlayerPreferences preferences = experience.preferences(player);
                boolean showParticles = preferences.particlesEnabled();
                Biome biome = loc.getBlock().getBiome();
                String biomeName = biome.key().value();

                // Sprint Dust
                if (current.atmosphereSprintDust() && player.isSprinting() && showParticles) {
                    Material ground = loc.getBlock().getRelative(org.bukkit.block.BlockFace.DOWN).getType();
                    if (ground == Material.SAND || ground == Material.RED_SAND || ground == Material.GRAVEL || ground == Material.SOUL_SAND) {
                        player.spawnParticle(
                                Particle.DUST,
                                loc,
                                particleCount(preferences, 3),
                                0.2,
                                0.0,
                                0.2,
                                new Particle.DustOptions(Color.fromRGB(194, 178, 128), 0.8f)
                        );
                    } else if (ground == Material.SNOW_BLOCK || ground == Material.SNOW) {
                        player.spawnParticle(
                                Particle.SNOWFLAKE,
                                loc,
                                particleCount(preferences, 3),
                                0.2,
                                0.0,
                                0.2,
                                0.01
                        );
                    }
                }

                // Nether Soul Embers
                if (current.atmosphereNetherSoulEmbers()
                        && showParticles
                        && loc.getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER) {
                    if (biomeName.contains("soul") || biomeName.contains("wastes")) {
                        player.spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(
                                random.nextDouble(-3.0, 3.0),
                                random.nextDouble(0.0, 2.0),
                                random.nextDouble(-3.0, 3.0)
                        ), particleCount(preferences, 2), 0.05, 0.05, 0.05, 0.01);
                    }
                }

                // Totem Guardian Glow
                if (current.atmosphereTotemGlow()
                        && showParticles
                        && player.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING
                        && player.getHealth() <= 8.0) {
                    player.spawnParticle(
                            Particle.WAX_ON,
                            loc.clone().add(0, 1.0, 0),
                            particleCount(preferences, 4),
                            0.4,
                            0.1,
                            0.4
                    );
                }

                // Subterranean Cave Echoes
                if (current.atmosphereCaveEchoes()
                        && (loc.getY() <= 0
                        || biomeName.contains("dripstone"))) {
                    if (random.nextDouble() < 0.3) {
                        playSound(
                                player,
                                preferences,
                                loc,
                                Sound.BLOCK_POINTED_DRIPSTONE_DRIP_WATER,
                                0.4f,
                                0.8f
                        );
                        if (showParticles) {
                            player.spawnParticle(
                                    Particle.DRIPPING_DRIPSTONE_WATER,
                                    loc.clone().add(
                                            random.nextDouble(-2.0, 2.0),
                                            3.0,
                                            random.nextDouble(-2.0, 2.0)
                                    ),
                                    1
                            );
                        }
                    }
                }

                // Mountain wind ambient
                if (loc.getY() > 120) {
                    playSound(player, preferences, loc, Sound.ITEM_ELYTRA_FLYING, 0.1f, 0.5f);
                    if (showParticles) {
                        player.spawnParticle(
                                Particle.CLOUD,
                                loc.clone().add(0, 2, 0),
                                particleCount(preferences, 2),
                                3.0,
                                1.0,
                                3.0,
                                0.01
                        );
                    }
                }

                // Deep Dark Sculk Spores
                if (current.atmosphereDeepDarkSpores() && showParticles) {
                    if (biomeName.contains("deep_dark")) {
                        player.spawnParticle(Particle.SCULK_SOUL, loc.clone().add(
                                random.nextDouble(-4.0, 4.0),
                                random.nextDouble(0.0, 2.5),
                                random.nextDouble(-4.0, 4.0)
                        ), particleCount(preferences, 2), 0.05, 0.05, 0.05, 0.01);
                        player.spawnParticle(Particle.SCULK_CHARGE_POP, loc.clone().add(
                                random.nextDouble(-3.0, 3.0),
                                random.nextDouble(0.5, 2.0),
                                random.nextDouble(-3.0, 3.0)
                        ), 1);
                    }
                }

                // Night fireflies in lush biomes
                if (showParticles && time >= 13000 && time <= 23000) {
                    if (biomeName.contains("swamp")
                            || biomeName.contains("forest")
                            || biomeName.contains("jungle")) {
                        player.spawnParticle(Particle.WAX_ON, loc.clone().add(
                                random.nextDouble(-4.0, 4.0),
                                random.nextDouble(0.0, 2.5),
                                random.nextDouble(-4.0, 4.0)
                        ), particleCount(preferences, 3), 0.1, 0.1, 0.1);
                    }
                }
            }
        };
        ambientTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                failures == null ? ambient : failures.guard("ambient atmosphere", ambient),
                10L,
                10L
        );
    }

    private void playSound(
            Player player,
            Location location,
            Sound sound,
            float volume,
            float pitch
    ) {
        PlayerPreferences preferences = experience.preferences(player);
        playSound(player, preferences, location, sound, volume, pitch);
    }

    private void playSound(
            Player player,
            PlayerPreferences preferences,
            Location location,
            Sound sound,
            float volume,
            float pitch
    ) {
        if (!preferences.soundsEnabled()) {
            return;
        }
        float adjustedVolume = preferences.reducedEffects() ? volume * 0.65f : volume;
        player.playSound(location, sound, adjustedVolume, pitch);
    }

    boolean particlesEnabled(Player player) {
        return experience.preferences(player).particlesEnabled();
    }

    int particleCount(Player player, int count) {
        return particleCount(experience.preferences(player), count);
    }

    private int particleCount(PlayerPreferences preferences, int count) {
        double scale = preferences.reducedEffects() ? 0.3 : 1.0;
        if (governor != null) {
            scale *= governor.particleScale();
        }
        return Math.max(1, (int) Math.ceil(count * scale));
    }

    private void scheduleTransientTask(
            Consumer<BukkitTask> action,
            long delayTicks,
            long periodTicks
    ) {
        AtomicReference<BukkitTask> taskReference = new AtomicReference<>();
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                () -> action.accept(taskReference.get()),
                delayTicks,
                periodTicks
        );
        taskReference.set(task);
        if (task != null) {
            transientTasks.add(task);
        }
    }

    private void finishTransientTask(BukkitTask task) {
        if (task == null) {
            return;
        }
        transientTasks.remove(task);
        task.cancel();
    }

    @Override
    public void close() {
        if (ambientTask != null) {
            ambientTask.cancel();
            ambientTask = null;
        }
        for (BukkitTask task : Set.copyOf(transientTasks)) {
            task.cancel();
        }
        transientTasks.clear();
        durabilityAlertCooldowns.clear();
    }
}

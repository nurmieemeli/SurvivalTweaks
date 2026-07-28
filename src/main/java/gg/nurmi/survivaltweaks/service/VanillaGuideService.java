package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.VanillaGuideTopic;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class VanillaGuideService implements Listener {

    private final JavaPlugin plugin;
    private final SettingsService settings;
    private final OnboardingService onboarding;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final Clock clock;
    private final Map<UUID, Instant> lastShown = new HashMap<>();
    private final Map<UUID, PendingHint> pending = new HashMap<>();
    private final Set<UUID> scheduled = new HashSet<>();

    public VanillaGuideService(
            JavaPlugin plugin,
            SettingsService settings,
            OnboardingService onboarding,
            MessageService messages,
            FeedbackService feedback,
            Clock clock
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.onboarding = onboarding;
        this.messages = messages;
        this.feedback = feedback;
        this.clock = clock;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        World.Environment from = event.getFrom().getEnvironment();
        World.Environment to = event.getPlayer().getWorld().getEnvironment();
        if (from == World.Environment.NORMAL && to == World.Environment.NETHER) {
            showNetherCoordinates(event.getPlayer(), false);
        } else if (from == World.Environment.NETHER && to == World.Environment.NORMAL) {
            showNetherCoordinates(event.getPlayer(), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK) {
            return;
        }
        trigger(
                event.getPlayer(),
                VanillaGuideTopic.SLEEP_RULES,
                Placeholder.component(
                        "reason",
                        messages.component(
                                event.getPlayer(),
                                "vanilla-guide.sleep-reason."
                                        + sleepReasonKey(event.getBedEnterResult())
                        )
                )
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }
        Material block = event.getClickedBlock().getType();
        ItemStack item = event.getItem();
        if (block == Material.RESPAWN_ANCHOR
                && item != null
                && item.getType() == Material.GLOWSTONE) {
            trigger(event.getPlayer(), VanillaGuideTopic.RESPAWN_ANCHORS);
        } else if (block == Material.LODESTONE
                && item != null
                && item.getType() == Material.COMPASS) {
            trigger(event.getPlayer(), VanillaGuideTopic.LODESTONES);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof ZombieVillager villager)
                || !villager.hasPotionEffect(PotionEffectType.WEAKNESS)) {
            return;
        }
        ItemStack held = event.getPlayer().getInventory().getItem(event.getHand());
        if (held.getType() == Material.GOLDEN_APPLE) {
            trigger(event.getPlayer(), VanillaGuideTopic.VILLAGER_CURING);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (event.getInventory().getType() == InventoryType.ANVIL) {
            trigger(player, VanillaGuideTopic.ANVILS);
        } else if (event.getInventory().getType() == InventoryType.ENCHANTING) {
            trigger(player, VanillaGuideTopic.ENCHANTING);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        pending.remove(playerId);
        lastShown.remove(playerId);
    }

    private void showNetherCoordinates(Player player, boolean toNether) {
        int x = scaledCoordinate(player.getLocation().getX(), toNether);
        int z = scaledCoordinate(player.getLocation().getZ(), toNether);
        trigger(
                player,
                VanillaGuideTopic.NETHER_COORDINATES,
                Placeholder.component(
                        "destination",
                        messages.component(
                                player,
                                toNether
                                        ? "vanilla-guide.destination.nether"
                                        : "vanilla-guide.destination.overworld"
                        )
                ),
                Placeholder.unparsed("x", Integer.toString(x)),
                Placeholder.unparsed("z", Integer.toString(z))
        );
    }

    private void trigger(Player player, VanillaGuideTopic topic, TagResolver... placeholders) {
        if (!eligible(player, topic) || onboarding.completed(player, topic.hint())) {
            return;
        }
        Instant now = clock.instant();
        Instant eligibleAt = lastShown.getOrDefault(player.getUniqueId(), Instant.MIN)
                .plus(settings.current().vanillaGuideMinimumGap());
        if (!eligibleAt.isAfter(now)) {
            showNow(player, topic, placeholders);
            return;
        }
        pending.putIfAbsent(player.getUniqueId(), new PendingHint(topic, placeholders.clone()));
        if (!scheduled.add(player.getUniqueId())) {
            return;
        }
        long delayMillis = Math.max(1, Duration.between(now, eligibleAt).toMillis());
        long delayTicks = Math.max(1, (delayMillis + 49) / 50);
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> deliverPending(player.getUniqueId()),
                delayTicks
        );
    }

    private void deliverPending(UUID playerId) {
        scheduled.remove(playerId);
        PendingHint hint = pending.remove(playerId);
        Player player = plugin.getServer().getPlayer(playerId);
        if (hint != null && player != null && player.isOnline()) {
            showNow(player, hint.topic(), hint.placeholders());
        }
    }

    private void showNow(Player player, VanillaGuideTopic topic, TagResolver... placeholders) {
        if (!eligible(player, topic) || !onboarding.complete(player, topic.hint())) {
            return;
        }
        Component hint = messages.component(
                player,
                "vanilla-guide.hint." + topic.key(),
                placeholders
        ).clickEvent(ClickEvent.runCommand("/guide"))
                .hoverEvent(HoverEvent.showText(messages.component(
                        player,
                        "vanilla-guide.open-hover"
                )));
        player.sendMessage(hint);
        feedback.play(player, FeedbackService.GUIDE_HINT);
        lastShown.put(player.getUniqueId(), clock.instant());
    }

    private boolean eligible(Player player, VanillaGuideTopic topic) {
        var current = settings.current();
        return player.isOnline()
                && current.journeyEnabled()
                && current.vanillaGuideEnabled()
                && current.vanillaGuideTopics().contains(topic.key())
                && onboarding.guidanceEnabled(player.getUniqueId());
    }

    static int scaledCoordinate(double coordinate, boolean toNether) {
        return (int) Math.floor(toNether ? coordinate / 8.0 : coordinate * 8.0);
    }

    static String sleepReasonKey(PlayerBedEnterEvent.BedEnterResult result) {
        return switch (result) {
            case NOT_POSSIBLE_NOW -> "time";
            case NOT_SAFE -> "monsters";
            case TOO_FAR_AWAY -> "distance";
            case OBSTRUCTED -> "obstructed";
            case NOT_POSSIBLE_HERE, EXPLOSION -> "dimension";
            case OTHER_PROBLEM, OK -> "other";
        };
    }

    private record PendingHint(VanillaGuideTopic topic, TagResolver[] placeholders) {

        private PendingHint {
            placeholders = placeholders.clone();
        }

        @Override
        public TagResolver[] placeholders() {
            return placeholders.clone();
        }
    }
}

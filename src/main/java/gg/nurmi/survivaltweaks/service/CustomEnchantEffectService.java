package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.SurvivalTweaks;
import gg.nurmi.survivaltweaks.object.CustomEnchantment;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.random.RandomGenerator;

public final class CustomEnchantEffectService implements Listener {

    private static final Map<Material, Material> CROP_SEEDS = Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS,
            Material.NETHER_WART, Material.NETHER_WART,
            Material.TORCHFLOWER_CROP, Material.TORCHFLOWER_SEEDS
    );

    private final SurvivalTweaks plugin;
    private final CustomEnchantItemService items;
    private final FeedbackService feedback;
    private final RandomGenerator random;
    private final Function<LivingEntity, ItemStack> headFactory;
    private final Set<UUID> areaMining = new HashSet<>();

    public CustomEnchantEffectService(
            SurvivalTweaks plugin,
            CustomEnchantItemService items,
            FeedbackService feedback
    ) {
        this(
                plugin,
                items,
                feedback,
                RandomGenerator.getDefault(),
                CustomEnchantEffectService::createHead
        );
    }

    CustomEnchantEffectService(
            SurvivalTweaks plugin,
            CustomEnchantItemService items,
            FeedbackService feedback,
            RandomGenerator random
    ) {
        this(plugin, items, feedback, random, CustomEnchantEffectService::createHead);
    }

    CustomEnchantEffectService(
            SurvivalTweaks plugin,
            CustomEnchantItemService items,
            FeedbackService feedback,
            RandomGenerator random,
            Function<LivingEntity, ItemStack> headFactory
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.items = Objects.requireNonNull(items, "items");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.random = Objects.requireNonNull(random, "random");
        this.headFactory = Objects.requireNonNull(headFactory, "headFactory");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!player.isSneaking() || areaMining.contains(player.getUniqueId())) {
            return;
        }

        ItemStack tool = player.getInventory().getItemInMainHand();
        Block origin = event.getBlock();
        Vector direction = player.getEyeLocation().getDirection().clone();
        if (items.has(tool, CustomEnchantment.TUNNELING)) {
            schedulePlane(player, origin, direction, CustomEnchantment.TUNNELING);
        } else if (items.has(tool, CustomEnchantment.EXCAVATION)) {
            schedulePlane(player, origin, direction, CustomEnchantment.EXCAVATION);
        } else if (items.has(tool, CustomEnchantment.CULTIVATION) && mature(origin)) {
            scheduleCultivation(player, origin);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !(event.getDamager() instanceof Projectile projectile)
                || !player.isBlocking()
                || !hasDeflectionShield(player)) {
            return;
        }

        Vector target;
        if (projectile.getShooter() instanceof LivingEntity shooter) {
            target = shooter.getEyeLocation().toVector();
        } else if (projectile.getShooter() instanceof Entity shooter) {
            target = shooter.getLocation().add(0, 0.5, 0).toVector();
        } else {
            target = projectile.getLocation().toVector()
                    .subtract(projectile.getVelocity().clone().normalize().multiply(8));
        }
        Vector reflected = target.subtract(projectile.getLocation().toVector());
        if (reflected.lengthSquared() < 0.01) {
            reflected = projectile.getVelocity().clone().multiply(-1);
        }
        double speed = Math.max(0.8, projectile.getVelocity().length());
        event.setCancelled(true);
        projectile.setShooter(player);
        projectile.setHasLeftShooter(true);
        projectile.setVelocity(reflected.normalize().multiply(speed));
        feedback.play(player, FeedbackService.ENCHANT_DEFLECTION);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Entity attributed = CustomDeathMessageService.resolveKiller(event.getDamageSource());
        if (!(attributed instanceof Player killer)) {
            return;
        }

        ItemStack weapon = killer.getInventory().getItemInMainHand();
        int level = items.level(weapon, CustomEnchantment.BEHEADING);
        if (level <= 0 || random.nextInt(100) >= level * 2) {
            return;
        }

        ItemStack head = headFactory.apply(event.getEntity());
        if (head == null || event.getDrops().stream().anyMatch(drop -> drop.getType() == head.getType())) {
            return;
        }
        event.getDrops().add(head);
        feedback.play(killer, FeedbackService.ENCHANT_BEHEADING);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhysicalInteraction(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL
                || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.FARMLAND) {
            return;
        }
        ItemStack boots = event.getPlayer().getInventory().getBoots();
        if (items.has(boots, CustomEnchantment.SUREFOOTED)) {
            event.setCancelled(true);
        }
    }

    private void schedulePlane(
            Player player,
            Block origin,
            Vector direction,
            CustomEnchantment enchantment
    ) {
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        UUID worldId = origin.getWorld().getUID();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()
                    || !player.isSneaking()
                    || !player.getWorld().getUID().equals(worldId)) {
                return;
            }
            ItemStack tool = player.getInventory().getItemInMainHand();
            if (!items.has(tool, enchantment)) {
                return;
            }

            int broken = 0;
            areaMining.add(player.getUniqueId());
            try {
                for (int first = -1; first <= 1; first++) {
                    for (int second = -1; second <= 1; second++) {
                        if (first == 0 && second == 0) {
                            continue;
                        }
                        Block target = planeBlock(origin.getWorld(), x, y, z, direction, first, second);
                        ItemStack currentTool = player.getInventory().getItemInMainHand();
                        if (!items.has(currentTool, enchantment) || !safeToolTarget(target, currentTool)) {
                            continue;
                        }
                        if (player.breakBlock(target)) {
                            broken++;
                        }
                    }
                }
            } finally {
                areaMining.remove(player.getUniqueId());
            }
            if (broken > 0) {
                feedback.play(player, FeedbackService.ENCHANT_AREA_BREAK, Math.min(2, broken / 4.0));
            }
        });
    }

    private Block planeBlock(
            org.bukkit.World world,
            int x,
            int y,
            int z,
            Vector direction,
            int first,
            int second
    ) {
        double ax = Math.abs(direction.getX());
        double ay = Math.abs(direction.getY());
        double az = Math.abs(direction.getZ());
        if (ay >= ax && ay >= az) {
            return world.getBlockAt(x + first, y, z + second);
        }
        if (ax >= az) {
            return world.getBlockAt(x, y + first, z + second);
        }
        return world.getBlockAt(x + first, y + second, z);
    }

    private boolean safeToolTarget(Block block, ItemStack tool) {
        return !block.isEmpty()
                && !block.isLiquid()
                && block.isPreferredTool(tool)
                && !(block.getState(false) instanceof TileState);
    }

    private void scheduleCultivation(Player player, Block origin) {
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        Material originType = origin.getType();
        UUID worldId = origin.getWorld().getUID();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()
                    || !player.isSneaking()
                    || !player.getWorld().getUID().equals(worldId)
                    || !items.has(
                            player.getInventory().getItemInMainHand(),
                            CustomEnchantment.CULTIVATION
                    )) {
                return;
            }

            int harvested = 0;
            areaMining.add(player.getUniqueId());
            try {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Block crop = origin.getWorld().getBlockAt(x + dx, y, z + dz);
                        if (dx == 0 && dz == 0) {
                            if (crop.isEmpty() && replant(player, crop, originType)) {
                                harvested++;
                            }
                            continue;
                        }
                        if (!mature(crop)) {
                            continue;
                        }
                        Material cropType = crop.getType();
                        Material seed = CROP_SEEDS.get(cropType);
                        if (seed == null || !consume(player, seed)) {
                            continue;
                        }
                        if (!player.breakBlock(crop)) {
                            player.getInventory().addItem(new ItemStack(seed));
                            continue;
                        }
                        plant(crop, cropType);
                        harvested++;
                    }
                }
            } finally {
                areaMining.remove(player.getUniqueId());
            }
            if (harvested > 0) {
                feedback.play(player, FeedbackService.ENCHANT_CULTIVATION);
            }
        });
    }

    private boolean replant(Player player, Block block, Material cropType) {
        Material seed = CROP_SEEDS.get(cropType);
        if (seed == null || !consume(player, seed)) {
            return false;
        }
        plant(block, cropType);
        return true;
    }

    private void plant(Block block, Material cropType) {
        block.setType(cropType, false);
        BlockData data = block.getBlockData();
        if (data instanceof Ageable ageable) {
            ageable.setAge(0);
            block.setBlockData(ageable, false);
        }
    }

    private boolean mature(Block block) {
        return CROP_SEEDS.containsKey(block.getType())
                && block.getBlockData() instanceof Ageable ageable
                && ageable.getAge() >= ageable.getMaximumAge();
    }

    private boolean consume(Player player, Material material) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            if (stack.getAmount() == 1) {
                player.getInventory().setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - 1);
            }
            return true;
        }
        return false;
    }

    private boolean hasDeflectionShield(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        return (main.getType() == Material.SHIELD
                && items.has(main, CustomEnchantment.DEFLECTION))
                || (off.getType() == Material.SHIELD
                && items.has(off, CustomEnchantment.DEFLECTION));
    }

    static Material headMaterial(EntityType type) {
        return switch (type) {
            case CREEPER -> Material.CREEPER_HEAD;
            case PIGLIN, PIGLIN_BRUTE -> Material.PIGLIN_HEAD;
            case SKELETON, STRAY, BOGGED -> Material.SKELETON_SKULL;
            case WITHER_SKELETON -> Material.WITHER_SKELETON_SKULL;
            case ZOMBIE, HUSK, DROWNED, ZOMBIE_VILLAGER -> Material.ZOMBIE_HEAD;
            case PLAYER -> Material.PLAYER_HEAD;
            default -> null;
        };
    }

    private static ItemStack createHead(LivingEntity entity) {
        Material material = headMaterial(entity.getType());
        if (material == null) {
            return null;
        }

        ItemStack head = new ItemStack(material);
        if (entity.getType() == EntityType.PLAYER && entity instanceof Player player) {
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(player);
            head.setItemMeta(meta);
        }
        return head;
    }
}

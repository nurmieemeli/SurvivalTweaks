package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.SurvivalTweaks;
import gg.nurmi.survivaltweaks.object.CustomEnchantment;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomEnchantEffectServiceTest {

    private final SurvivalTweaks plugin = mock(SurvivalTweaks.class);
    private final CustomEnchantItemService items = mock(CustomEnchantItemService.class);
    private final FeedbackService feedback = mock(FeedbackService.class);
    private final RandomGenerator random = mock(RandomGenerator.class);
    private CustomEnchantEffectService service;

    @BeforeEach
    void setUp() {
        service = new CustomEnchantEffectService(plugin, items, feedback, random);
    }

    @Test
    void cultivationRestoresTheBlockAndKeepsTheSeedWhenPlantingIsDenied() {
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        var protections = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPluginManager()).thenReturn(protections);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        });
        doAnswer(invocation -> {
            invocation.<BlockPlaceEvent>getArgument(0).setCancelled(true);
            return null;
        }).when(protections).callEvent(any(BlockPlaceEvent.class));

        var player = mock(Player.class);
        var world = mock(World.class);
        var inventory = mock(PlayerInventory.class);
        var tool = item(Material.DIAMOND_HOE);
        var seed = item(Material.WHEAT_SEEDS);
        when(seed.asOne()).thenReturn(seed);
        var mature = mock(Ageable.class);
        var origin = mock(Block.class);
        var empty = mock(Block.class);
        var neighbor = mock(Block.class);
        var replaced = mock(BlockState.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getBlockAt(anyInt(), eq(0), anyInt())).thenReturn(neighbor);
        when(world.getBlockAt(0, 0, 0)).thenReturn(empty);
        when(neighbor.getType()).thenReturn(Material.AIR);
        when(empty.isEmpty()).thenReturn(true);
        when(empty.getState()).thenReturn(replaced);
        when(empty.getRelative(BlockFace.DOWN)).thenReturn(neighbor);
        when(origin.getWorld()).thenReturn(world);
        when(origin.getType()).thenReturn(Material.WHEAT);
        when(origin.getBlockData()).thenReturn(mature);
        when(mature.getAge()).thenReturn(7);
        when(mature.getMaximumAge()).thenReturn(7);
        when(player.getWorld()).thenReturn(world);
        when(player.getEyeLocation()).thenReturn(new Location(world, 0, 1.6, 0));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.isSneaking()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(tool);
        when(inventory.first(Material.WHEAT_SEEDS)).thenReturn(0);
        when(inventory.getItem(0)).thenReturn(seed);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{seed});
        when(items.has(tool, CustomEnchantment.CULTIVATION)).thenReturn(true);

        service.onBlockBreak(new BlockBreakEvent(origin, player));

        verify(protections).callEvent(any(BlockPlaceEvent.class));
        verify(empty).setType(Material.WHEAT, false);
        verify(replaced).update(true, false);
        verify(inventory, never()).getStorageContents();
    }

    @Test
    void deflectionCreditsTheBlockingPlayerAndReturnsProjectileToShooter() {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        Player player = mock(Player.class);
        Projectile projectile = mock(Projectile.class);
        LivingEntity shooter = mock(LivingEntity.class);
        ItemStack shield = item(Material.SHIELD);
        when(event.getEntity()).thenReturn(player);
        when(event.getDamager()).thenReturn(projectile);
        when(event.getDamage(DamageModifier.BLOCKING)).thenReturn(-4.0);
        when(player.getActiveItem()).thenReturn(shield);
        when(items.has(shield, CustomEnchantment.DEFLECTION)).thenReturn(true);
        when(projectile.getShooter()).thenReturn(shooter);
        when(projectile.getLocation()).thenReturn(new Location(null, 0, 64, 0));
        when(projectile.getVelocity()).thenReturn(new Vector(0, 0, -1));
        when(shooter.getEyeLocation()).thenReturn(new Location(null, 10, 65, 0));

        service.onProjectileDamage(event);

        verify(event).setCancelled(true);
        verify(projectile).setShooter(player);
        ArgumentCaptor<Vector> velocity = ArgumentCaptor.forClass(Vector.class);
        verify(projectile).setVelocity(velocity.capture());
        assertTrue(velocity.getValue().getX() > 0);
        verify(feedback).play(player, FeedbackService.ENCHANT_DEFLECTION);
    }

    @Test
    void deflectionIgnoresAProjectileTheShieldDidNotBlock() {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        Projectile projectile = mock(Projectile.class);
        ItemStack shield = item(Material.SHIELD);
        ItemStack empty = item(Material.AIR);
        lenient().when(player.isBlocking()).thenReturn(true);
        lenient().when(player.getInventory()).thenReturn(inventory);
        lenient().when(inventory.getItemInMainHand()).thenReturn(shield);
        lenient().when(inventory.getItemInOffHand()).thenReturn(empty);
        lenient().when(items.has(shield, CustomEnchantment.DEFLECTION)).thenReturn(true);
        when(event.getEntity()).thenReturn(player);
        when(event.getDamager()).thenReturn(projectile);

        service.onProjectileDamage(event);

        verify(event, never()).setCancelled(true);
        verify(player, never()).getActiveItem();
    }

    @Test
    void deflectionRequiresTheActiveShieldToBeEnchanted() {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        Projectile projectile = mock(Projectile.class);
        ItemStack plainShield = item(Material.SHIELD);
        ItemStack enchantedShield = item(Material.SHIELD);
        lenient().when(player.isBlocking()).thenReturn(true);
        lenient().when(player.getInventory()).thenReturn(inventory);
        lenient().when(inventory.getItemInMainHand()).thenReturn(plainShield);
        lenient().when(inventory.getItemInOffHand()).thenReturn(enchantedShield);
        lenient().when(items.has(enchantedShield, CustomEnchantment.DEFLECTION)).thenReturn(true);
        when(event.getEntity()).thenReturn(player);
        when(event.getDamager()).thenReturn(projectile);
        when(event.getDamage(DamageModifier.BLOCKING)).thenReturn(-4.0);
        when(player.getActiveItem()).thenReturn(plainShield);

        service.onProjectileDamage(event);

        verify(event, never()).setCancelled(true);
        verify(items).has(plainShield, CustomEnchantment.DEFLECTION);
    }

    @Test
    void beheadingAddsAnEligibleMobHeadAtTheConfiguredLevelChance() {
        EntityDeathEvent event = mock(EntityDeathEvent.class);
        DamageSource source = mock(DamageSource.class);
        Player killer = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        LivingEntity zombie = mock(LivingEntity.class);
        ItemStack sword = item(Material.DIAMOND_SWORD);
        ItemStack head = item(Material.ZOMBIE_HEAD);
        List<ItemStack> drops = new ArrayList<>();
        service = new CustomEnchantEffectService(plugin, items, feedback, random, ignored -> head);
        when(event.getDamageSource()).thenReturn(source);
        when(source.getCausingEntity()).thenReturn(killer);
        when(source.getDirectEntity()).thenReturn(killer);
        when(killer.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(sword);
        when(items.level(sword, CustomEnchantment.BEHEADING)).thenReturn(3);
        when(random.nextInt(100)).thenReturn(0);
        when(event.getEntity()).thenReturn(zombie);
        when(zombie.getType()).thenReturn(EntityType.ZOMBIE);
        when(event.getDrops()).thenReturn(drops);

        service.onEntityDeath(event);

        assertEquals(1, drops.size());
        assertEquals(head, drops.getFirst());
        verify(feedback).play(killer, FeedbackService.ENCHANT_BEHEADING);
    }

    @Test
    void anArrowKillDoesNotTriggerTheBeheadingSwordHeldAtImpact() {
        var event = mock(EntityDeathEvent.class);
        var source = mock(DamageSource.class);
        var killer = mock(Player.class);
        var arrow = mock(org.bukkit.entity.Arrow.class);
        var inventory = mock(PlayerInventory.class);
        var sword = item(Material.DIAMOND_SWORD);
        var drops = new ArrayList<ItemStack>();
        service = new CustomEnchantEffectService(plugin, items, feedback, random,
                ignored -> item(Material.ZOMBIE_HEAD));
        when(event.getDamageSource()).thenReturn(source);
        when(event.getDrops()).thenReturn(drops);
        when(source.getCausingEntity()).thenReturn(killer);
        when(source.getDirectEntity()).thenReturn(arrow);
        when(killer.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(sword);
        when(items.level(sword, CustomEnchantment.BEHEADING)).thenReturn(3);
        when(random.nextInt(100)).thenReturn(0);

        service.onEntityDeath(event);

        assertTrue(drops.isEmpty());
    }

    @Test
    void headMappingCoversEveryVanillaHeadFamilyUsedByBeheading() {
        assertEquals(Material.CREEPER_HEAD, CustomEnchantEffectService.headMaterial(EntityType.CREEPER));
        assertEquals(Material.PIGLIN_HEAD, CustomEnchantEffectService.headMaterial(EntityType.PIGLIN));
        assertEquals(Material.SKELETON_SKULL, CustomEnchantEffectService.headMaterial(EntityType.BOGGED));
        assertEquals(
                Material.WITHER_SKELETON_SKULL,
                CustomEnchantEffectService.headMaterial(EntityType.WITHER_SKELETON)
        );
        assertEquals(Material.ZOMBIE_HEAD, CustomEnchantEffectService.headMaterial(EntityType.DROWNED));
        assertEquals(Material.PLAYER_HEAD, CustomEnchantEffectService.headMaterial(EntityType.PLAYER));
    }

    @Test
    void surefootedPreventsFarmlandTrampling() {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        Block farmland = mock(Block.class);
        ItemStack boots = item(Material.DIAMOND_BOOTS);
        when(event.getAction()).thenReturn(Action.PHYSICAL);
        when(event.getClickedBlock()).thenReturn(farmland);
        when(farmland.getType()).thenReturn(Material.FARMLAND);
        when(event.getPlayer()).thenReturn(player);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getBoots()).thenReturn(boots);
        when(items.has(boots, CustomEnchantment.SUREFOOTED)).thenReturn(true);

        service.onPhysicalInteraction(event);

        verify(event).setCancelled(true);
    }

    private ItemStack item(Material material) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        when(item.isEmpty()).thenReturn(material == Material.AIR);
        return item;
    }
}

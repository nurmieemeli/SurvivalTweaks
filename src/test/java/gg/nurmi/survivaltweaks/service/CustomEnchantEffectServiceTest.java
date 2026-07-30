package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.SurvivalTweaks;
import gg.nurmi.survivaltweaks.object.CustomEnchantment;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
    void deflectionCreditsTheBlockingPlayerAndReturnsProjectileToShooter() {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        Projectile projectile = mock(Projectile.class);
        LivingEntity shooter = mock(LivingEntity.class);
        ItemStack shield = item(Material.SHIELD);
        ItemStack empty = item(Material.AIR);
        when(event.getEntity()).thenReturn(player);
        when(event.getDamager()).thenReturn(projectile);
        when(player.isBlocking()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(shield);
        when(inventory.getItemInOffHand()).thenReturn(empty);
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

package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.SurvivalTweaks;
import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

public class BlockRefillServiceTest {

    @Mock
    private SurvivalTweaks plugin;

    @Mock
    private SettingsService settingsService;

    @Mock
    private PluginSettings settings;

    private BlockRefillService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(settingsService.current()).thenReturn(settings);
        service = new BlockRefillService(plugin, settingsService);
    }

    @Test
    void placementRefillsWithAMatchingEmptyBoxAndLeavesTheFilledBoxInStorage() {
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        var player = mock(Player.class);
        var inventory = mock(PlayerInventory.class);
        var event = mock(BlockPlaceEvent.class);
        var placed = mock(ItemStack.class);
        var filled = mock(ItemStack.class);
        var empty = mock(ItemStack.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(settings.hotbarRefillEnabled()).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getHeldItemSlot()).thenReturn(2);
        when(inventory.getItem(10)).thenReturn(filled);
        when(inventory.getItem(11)).thenReturn(empty);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getItemInHand()).thenReturn(placed);
        when(placed.getType()).thenReturn(Material.SHULKER_BOX);
        when(placed.clone()).thenReturn(placed);
        when(filled.getType()).thenReturn(Material.SHULKER_BOX);
        when(filled.getAmount()).thenReturn(1);
        when(filled.clone()).thenReturn(filled);
        when(empty.getType()).thenReturn(Material.SHULKER_BOX);
        when(empty.getAmount()).thenReturn(1);
        when(empty.clone()).thenReturn(empty);
        when(placed.isSimilar(empty)).thenReturn(true);
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(1L))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        });

        service.onBlockPlace(event);

        verify(inventory, never()).setItem(10, null);
        verify(inventory).setItem(11, null);
        verify(inventory).setItem(2, empty);
    }

    @Test
    void testRefillHandSuccess() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);

        ItemStack cobble = mock(ItemStack.class);
        when(cobble.getType()).thenReturn(Material.COBBLESTONE);
        when(cobble.getAmount()).thenReturn(64);
        when(cobble.clone()).thenReturn(cobble);

        when(inventory.getHeldItemSlot()).thenReturn(2);
        when(inventory.getItem(10)).thenReturn(cobble);

        boolean refilled = service.refillHand(player, EquipmentSlot.HAND, Material.COBBLESTONE);

        assertTrue(refilled);
        verify(inventory).setItem(2, cobble);
        verify(inventory).setItem(10, null);
    }

    @Test
    void testRefillHandNoMatchingItem() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);

        ItemStack dirt = mock(ItemStack.class);
        when(dirt.getType()).thenReturn(Material.DIRT);
        when(dirt.getAmount()).thenReturn(64);

        when(inventory.getHeldItemSlot()).thenReturn(2);
        when(inventory.getItem(10)).thenReturn(dirt);

        boolean refilled = service.refillHand(player, EquipmentSlot.HAND, Material.COBBLESTONE);

        assertFalse(refilled);
    }

    @Test
    void doesNotMoveAnotherHotbarOrOffhandStack() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getHeldItemSlot()).thenReturn(2);

        ItemStack hotbarCobble = mock(ItemStack.class);
        when(hotbarCobble.getType()).thenReturn(Material.COBBLESTONE);
        when(hotbarCobble.getAmount()).thenReturn(64);
        when(inventory.getItem(3)).thenReturn(hotbarCobble);
        when(inventory.getItem(40)).thenReturn(hotbarCobble);

        assertFalse(service.refillHand(player, EquipmentSlot.HAND, Material.COBBLESTONE));
        verify(inventory, never()).setItem(2, hotbarCobble);
    }
}

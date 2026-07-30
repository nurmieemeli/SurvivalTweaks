package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.CustomEnchantment;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.loot.LootTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomEnchantAcquisitionServiceTest {

    private final CustomEnchantItemService items = mock(CustomEnchantItemService.class);
    private final MessageService messages = mock(MessageService.class);
    private final FeedbackService feedback = mock(FeedbackService.class);
    private final ActionBarService actionBars = mock(ActionBarService.class);
    private final RandomGenerator random = mock(RandomGenerator.class);
    private CustomEnchantAcquisitionService service;

    @BeforeEach
    void setUp() {
        service = new CustomEnchantAcquisitionService(items, messages, feedback, actionBars, random);
        when(random.nextInt(anyInt())).thenReturn(0);
    }

    @Test
    void enchantingTableAddsOneEligibleHiddenCustomEnchant() {
        EnchantItemEvent event = mock(EnchantItemEvent.class);
        ItemStack pickaxe = item(Material.DIAMOND_PICKAXE);
        Player player = mock(Player.class);
        when(event.getItem()).thenReturn(pickaxe);
        when(event.getExpLevelCost()).thenReturn(30);
        when(event.getEnchanter()).thenReturn(player);
        when(messages.component(player, CustomEnchantment.TUNNELING.nameKey()))
                .thenReturn(Component.text("Tunneling"));
        when(items.apply(pickaxe, CustomEnchantment.TUNNELING, 1, player)).thenReturn(true);

        service.onEnchant(event);

        verify(items).apply(pickaxe, CustomEnchantment.TUNNELING, 1, player);
        verify(event).setItem(pickaxe);
        verify(feedback).play(player, FeedbackService.ENCHANT_DISCOVERED);
    }

    @Test
    void eligibleStructureLootCanReceiveACustomBook() {
        LootGenerateEvent event = mock(LootGenerateEvent.class);
        LootTable table = mock(LootTable.class);
        ItemStack book = item(Material.ENCHANTED_BOOK);
        when(event.getLootTable()).thenReturn(table);
        when(table.getKey()).thenReturn(NamespacedKey.minecraft("chests/abandoned_mineshaft"));
        when(event.getLoot()).thenReturn(new ArrayList<>());
        when(items.createBook(CustomEnchantment.TUNNELING, 1, null)).thenReturn(book);

        service.onLootGenerate(event);

        verify(event).setLoot(any());
    }

    @Test
    void anvilAppliesTheCustomBookAndChargesLevels() {
        PrepareAnvilEvent event = mock(PrepareAnvilEvent.class);
        AnvilInventory inventory = mock(AnvilInventory.class);
        AnvilView view = mock(AnvilView.class);
        Player player = mock(Player.class);
        ItemStack first = item(Material.DIAMOND_PICKAXE);
        ItemStack second = item(Material.ENCHANTED_BOOK);
        ItemStack result = item(Material.DIAMOND_PICKAXE);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getView()).thenReturn(view);
        when(view.getPlayer()).thenReturn(player);
        when(view.getMaximumRepairCost()).thenReturn(40);
        when(inventory.getFirstItem()).thenReturn(first);
        when(inventory.getSecondItem()).thenReturn(second);
        when(first.clone()).thenReturn(result);
        when(items.enchantments(second)).thenReturn(Map.of(CustomEnchantment.TUNNELING, 1));
        when(items.apply(result, CustomEnchantment.TUNNELING, 1, player)).thenReturn(true);

        service.onPrepareAnvil(event);

        verify(event).setResult(result);
        verify(view).setRepairCost(5);
        verify(view, never()).setMaximumRepairCost(anyInt());
    }

    private ItemStack item(Material material) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        when(item.isEmpty()).thenReturn(false);
        return item;
    }
}

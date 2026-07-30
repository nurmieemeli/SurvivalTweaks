package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.SurvivalTweaks;
import gg.nurmi.survivaltweaks.object.CustomEnchantment;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomEnchantItemServiceTest {

    private final SurvivalTweaks plugin = mock(SurvivalTweaks.class);
    private final MessageService messages = new MessageService(
            Map.of("enchantments.tunneling.name", "Tunneling"),
            Map.of(),
            Logger.getAnonymousLogger()
    );
    private final ItemStack item = mock(ItemStack.class);
    private final ItemMeta meta = mock(ItemMeta.class);
    private final PersistentDataContainer data = mock(PersistentDataContainer.class);
    private CustomEnchantItemService service;

    @BeforeEach
    void setUp() {
        when(plugin.getName()).thenReturn("SurvivalTweaks");
        service = new CustomEnchantItemService(plugin, messages);
        when(item.isEmpty()).thenReturn(false);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(meta.hasLore()).thenReturn(false);
        when(meta.lore()).thenReturn(List.of());
    }

    @Test
    void storesAValidatedBoundedLevelAndRefreshesLore() {
        when(item.getType()).thenReturn(Material.DIAMOND_PICKAXE);

        assertTrue(service.apply(item, CustomEnchantment.TUNNELING, 9, null));

        ArgumentCaptor<NamespacedKey> key = ArgumentCaptor.forClass(NamespacedKey.class);
        verify(data).set(key.capture(), eq(PersistentDataType.INTEGER), eq(1));
        assertEquals("survivaltweaks:enchantment_tunneling", key.getValue().asString());
        verify(meta).lore(any());
        verify(meta).setEnchantmentGlintOverride(true);
        verify(item).setItemMeta(meta);
    }

    @Test
    void rejectsAnEnchantmentForTheWrongTool() {
        when(item.getType()).thenReturn(Material.DIAMOND_SHOVEL);

        assertFalse(service.apply(item, CustomEnchantment.TUNNELING, 1, null));
    }

    @Test
    void readsStoredLevelsFromPersistentData() {
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.INTEGER))).thenReturn(1);

        assertEquals(1, service.level(item, CustomEnchantment.TUNNELING));
    }

    @Test
    void romanNumeralsCoverEveryShippedLevel() {
        assertEquals("I", CustomEnchantItemService.roman(1));
        assertEquals("II", CustomEnchantItemService.roman(2));
        assertEquals("III", CustomEnchantItemService.roman(3));
    }
}

package gg.nurmi.survivaltweaks.object;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomEnchantmentTest {

    @Test
    void appliesEveryEnchantmentOnlyToItsIntendedEquipment() {
        assertTrue(CustomEnchantment.TUNNELING.canApply(item(Material.DIAMOND_PICKAXE)));
        assertFalse(CustomEnchantment.TUNNELING.canApply(item(Material.DIAMOND_SHOVEL)));
        assertTrue(CustomEnchantment.EXCAVATION.canApply(item(Material.NETHERITE_SHOVEL)));
        assertTrue(CustomEnchantment.CULTIVATION.canApply(item(Material.IRON_HOE)));
        assertTrue(CustomEnchantment.FELLING.canApply(item(Material.GOLDEN_AXE)));
        assertTrue(CustomEnchantment.BEHEADING.canApply(item(Material.DIAMOND_SWORD)));
        assertTrue(CustomEnchantment.BEHEADING.canApply(item(Material.DIAMOND_AXE)));
        assertTrue(CustomEnchantment.DEFLECTION.canApply(item(Material.SHIELD)));
        assertTrue(CustomEnchantment.SUREFOOTED.canApply(item(Material.LEATHER_BOOTS)));
        assertTrue(CustomEnchantment.DEFLECTION.canApply(item(Material.ENCHANTED_BOOK)));
    }

    @Test
    void beheadingRemainsIncompatibleWithLooting() {
        assertTrue(CustomEnchantment.BEHEADING.conflictsWithNativeKey("minecraft:looting"));
        assertFalse(CustomEnchantment.BEHEADING.conflictsWithNativeKey("minecraft:sharpness"));
        assertFalse(CustomEnchantment.FELLING.conflictsWithNativeKey("minecraft:looting"));
    }

    @Test
    void tableCostsSelectBoundedBeheadingLevels() {
        assertEquals(1, CustomEnchantment.BEHEADING.tableLevel(20));
        assertEquals(2, CustomEnchantment.BEHEADING.tableLevel(25));
        assertEquals(3, CustomEnchantment.BEHEADING.tableLevel(30));
        assertEquals(1, CustomEnchantment.TUNNELING.tableLevel(30));
    }

    private ItemStack item(Material material) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        when(item.isEmpty()).thenReturn(false);
        return item;
    }
}

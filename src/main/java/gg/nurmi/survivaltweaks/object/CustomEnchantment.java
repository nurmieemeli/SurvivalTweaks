package gg.nurmi.survivaltweaks.object;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum CustomEnchantment {
    TUNNELING(1, 24, 8),
    EXCAVATION(1, 18, 10),
    CULTIVATION(1, 14, 12),
    FELLING(1, 24, 7),
    BEHEADING(3, 20, 5),
    DEFLECTION(1, 28, 4),
    SUREFOOTED(1, 14, 10);

    private final String key;
    private final int maxLevel;
    private final int minimumTableCost;
    private final int tableWeight;

    CustomEnchantment(int maxLevel, int minimumTableCost, int tableWeight) {
        this.key = name().toLowerCase(Locale.ROOT).replace('_', '-');
        this.maxLevel = maxLevel;
        this.minimumTableCost = minimumTableCost;
        this.tableWeight = tableWeight;
    }

    public String key() {
        return key;
    }

    public String nameKey() {
        return "enchantments." + key + ".name";
    }

    public String descriptionKey() {
        return "enchantments." + key + ".description";
    }

    public int maxLevel() {
        return maxLevel;
    }

    public int minimumTableCost() {
        return minimumTableCost;
    }

    public int tableWeight() {
        return tableWeight;
    }

    public int tableLevel(int cost) {
        if (maxLevel == 1) {
            return 1;
        }
        if (cost >= 30) {
            return maxLevel;
        }
        if (cost >= 25) {
            return Math.min(2, maxLevel);
        }
        return 1;
    }

    public boolean canApply(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        Material type = item.getType();
        if (type == Material.BOOK || type == Material.ENCHANTED_BOOK) {
            return true;
        }
        return switch (this) {
            case TUNNELING -> isTool(type, "_PICKAXE");
            case EXCAVATION -> isTool(type, "_SHOVEL");
            case CULTIVATION -> isTool(type, "_HOE");
            case FELLING -> isTool(type, "_AXE");
            case BEHEADING -> isTool(type, "_SWORD") || isTool(type, "_AXE");
            case DEFLECTION -> type == Material.SHIELD;
            case SUREFOOTED -> isTool(type, "_BOOTS");
        };
    }

    public boolean conflictsWith(ItemStack item) {
        return this == BEHEADING && item.getEnchantments().keySet().stream()
                .anyMatch(enchantment -> conflictsWithNativeKey(enchantment.getKey().asString()));
    }

    boolean conflictsWithNativeKey(String key) {
        return this == BEHEADING && "minecraft:looting".equals(key);
    }

    public static Optional<CustomEnchantment> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(value -> value.key.equalsIgnoreCase(key)).findFirst();
    }

    private static boolean isTool(Material material, String suffix) {
        return material.name().endsWith(suffix);
    }
}

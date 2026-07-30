package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.SurvivalTweaks;
import gg.nurmi.survivaltweaks.object.CustomEnchantment;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CustomEnchantItemService {

    private static final String LORE_MARKER = "\u2063";

    private final MessageService messages;
    private final Map<CustomEnchantment, NamespacedKey> keys =
            new EnumMap<>(CustomEnchantment.class);

    public CustomEnchantItemService(SurvivalTweaks plugin, MessageService messages) {
        Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");
        for (CustomEnchantment enchantment : CustomEnchantment.values()) {
            keys.put(
                    enchantment,
                    new NamespacedKey(
                            "survivaltweaks",
                            "enchantment_" + enchantment.key().replace('-', '_')
                    )
            );
        }
    }

    public int level(ItemStack item, CustomEnchantment enchantment) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return 0;
        }
        Integer stored = item.getItemMeta().getPersistentDataContainer().get(
                keys.get(enchantment),
                PersistentDataType.INTEGER
        );
        return stored == null ? 0 : Math.max(0, Math.min(enchantment.maxLevel(), stored));
    }

    public boolean has(ItemStack item, CustomEnchantment enchantment) {
        return level(item, enchantment) > 0;
    }

    public Map<CustomEnchantment, Integer> enchantments(ItemStack item) {
        EnumMap<CustomEnchantment, Integer> found = new EnumMap<>(CustomEnchantment.class);
        for (CustomEnchantment enchantment : CustomEnchantment.values()) {
            int level = level(item, enchantment);
            if (level > 0) {
                found.put(enchantment, level);
            }
        }
        return Map.copyOf(found);
    }

    public boolean apply(
            ItemStack item,
            CustomEnchantment enchantment,
            int requestedLevel,
            Audience audience
    ) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(enchantment, "enchantment");
        if (!enchantment.canApply(item) || enchantment.conflictsWith(item)) {
            return false;
        }

        int level = Math.max(1, Math.min(enchantment.maxLevel(), requestedLevel));
        if (item.getType() == Material.BOOK) {
            item.setType(Material.ENCHANTED_BOOK);
        }
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(
                keys.get(enchantment),
                PersistentDataType.INTEGER,
                level
        );
        refreshLore(meta, audience, item.getType() == Material.ENCHANTED_BOOK);
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return true;
    }

    public ItemStack createBook(
            CustomEnchantment enchantment,
            int level,
            Audience audience
    ) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        apply(book, enchantment, level, audience);
        return book;
    }

    public ItemStack removeAll(ItemStack original, Audience audience) {
        if (original == null || original.isEmpty()) {
            return original;
        }
        ItemStack cleaned = original.clone();
        ItemMeta meta = cleaned.getItemMeta();
        for (NamespacedKey key : keys.values()) {
            meta.getPersistentDataContainer().remove(key);
        }
        refreshLore(meta, audience, cleaned.getType() == Material.ENCHANTED_BOOK);
        boolean nativeEnchantments = meta.hasEnchants()
                || (meta instanceof EnchantmentStorageMeta storage && storage.hasStoredEnchants());
        if (!nativeEnchantments) {
            meta.setEnchantmentGlintOverride(null);
        }
        cleaned.setItemMeta(meta);
        if (cleaned.getType() == Material.ENCHANTED_BOOK && !nativeEnchantments) {
            cleaned.setType(Material.BOOK);
        }
        return cleaned;
    }

    public void refresh(ItemStack item, Audience audience) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        refreshLore(meta, audience, item.getType() == Material.ENCHANTED_BOOK);
        item.setItemMeta(meta);
    }

    private void refreshLore(ItemMeta meta, Audience audience, boolean book) {
        List<Component> lore = new ArrayList<>();
        if (meta.hasLore() && meta.lore() != null) {
            meta.lore().stream()
                    .filter(line -> !PlainTextComponentSerializer.plainText()
                            .serialize(line)
                            .startsWith(LORE_MARKER))
                    .forEach(lore::add);
        }

        boolean customEnchantments = false;
        for (CustomEnchantment enchantment : CustomEnchantment.values()) {
            Integer stored = meta.getPersistentDataContainer().get(
                    keys.get(enchantment),
                    PersistentDataType.INTEGER
            );
            if (stored == null || stored <= 0) {
                continue;
            }
            customEnchantments = true;
            int level = Math.min(enchantment.maxLevel(), stored);
            Component name = messages.component(audience, enchantment.nameKey())
                    .colorIfAbsent(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false);
            lore.add(Component.text(LORE_MARKER)
                    .color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(name)
                    .append(Component.text(" " + roman(level), NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
            lore.add(markedLine(
                    messages.component(audience, enchantment.descriptionKey()),
                    NamedTextColor.DARK_GRAY
            ));
            lore.add(markedLine(
                    messages.component(
                            audience,
                            enchantment.requiresSneaking()
                                    ? "enchantments.tooltip.activation-sneak"
                                    : "enchantments.tooltip.activation-always"
                    ),
                    NamedTextColor.DARK_GRAY
            ));
            if (enchantment.conflictsWithLooting()) {
                lore.add(markedLine(
                        messages.component(audience, "enchantments.tooltip.conflict-looting"),
                        NamedTextColor.RED
                ));
            }
        }
        if (customEnchantments) {
            lore.add(markedLine(
                    messages.component(audience, "enchantments.tooltip.sources"),
                    book ? NamedTextColor.DARK_AQUA : NamedTextColor.DARK_GRAY
            ));
        }
        meta.lore(lore.isEmpty() ? null : lore);
    }

    private Component markedLine(Component content, NamedTextColor color) {
        return Component.text(LORE_MARKER)
                .color(color)
                .decoration(TextDecoration.ITALIC, false)
                .append(content.colorIfAbsent(color).decoration(TextDecoration.ITALIC, false));
    }

    static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            default -> Integer.toString(level);
        };
    }
}

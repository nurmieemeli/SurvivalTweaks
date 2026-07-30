package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.CustomEnchantment;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

public final class CustomEnchantAcquisitionService implements Listener {

    private static final Set<String> ENCHANTED_LOOT_TABLES = Set.of(
            "minecraft:chests/abandoned_mineshaft",
            "minecraft:chests/ancient_city",
            "minecraft:chests/end_city_treasure",
            "minecraft:chests/simple_dungeon",
            "minecraft:chests/stronghold_library",
            "minecraft:chests/trial_chambers/reward",
            "minecraft:chests/trial_chambers/reward_ominous"
    );

    private final CustomEnchantItemService items;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final RandomGenerator random;

    public CustomEnchantAcquisitionService(
            CustomEnchantItemService items,
            MessageService messages,
            FeedbackService feedback
    ) {
        this(items, messages, feedback, RandomGenerator.getDefault());
    }

    CustomEnchantAcquisitionService(
            CustomEnchantItemService items,
            MessageService messages,
            FeedbackService feedback,
            RandomGenerator random
    ) {
        this.items = Objects.requireNonNull(items, "items");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.random = Objects.requireNonNull(random, "random");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        ItemStack item = event.getItem();
        int cost = event.getExpLevelCost();
        int chance = Math.min(30, 8 + cost / 2);
        if (random.nextInt(100) >= chance) {
            return;
        }

        boolean addsLooting = event.getEnchantsToAdd().keySet().stream()
                .anyMatch(enchantment -> "minecraft:looting".equals(enchantment.getKey().asString()));
        List<CustomEnchantment> candidates = candidates(item, cost, addsLooting);
        if (candidates.isEmpty()) {
            return;
        }
        CustomEnchantment selected = weighted(candidates);
        int level = selected.tableLevel(cost);
        if (!items.apply(item, selected, level, event.getEnchanter())) {
            return;
        }
        event.setItem(item);
        reveal(event.getEnchanter(), selected, level);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onLootGenerate(LootGenerateEvent event) {
        if (event.isPlugin()
                || !ENCHANTED_LOOT_TABLES.contains(event.getLootTable().getKey().asString())
                || random.nextInt(100) >= 8) {
            return;
        }

        CustomEnchantment enchantment =
                CustomEnchantment.values()[random.nextInt(CustomEnchantment.values().length)];
        int level = randomLevel(enchantment);
        List<ItemStack> loot = new ArrayList<>(event.getLoot());
        loot.add(items.createBook(enchantment, level, null));
        event.setLoot(loot);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onVillagerTrade(VillagerAcquireTradeEvent event) {
        if (!(event.getEntity() instanceof Villager villager)
                || villager.getProfession() != Villager.Profession.LIBRARIAN
                || random.nextInt(100) >= 12) {
            return;
        }

        CustomEnchantment enchantment =
                CustomEnchantment.values()[random.nextInt(CustomEnchantment.values().length)];
        int level = randomLevel(enchantment);
        ItemStack book = items.createBook(enchantment, level, null);
        MerchantRecipe recipe = new MerchantRecipe(book, 0, 12, true, 10, 0.2f);
        recipe.setIngredients(List.of(
                new ItemStack(Material.EMERALD, 10 + level * 8),
                new ItemStack(Material.BOOK)
        ));
        event.setRecipe(recipe);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inventory = event.getInventory();
        ItemStack first = inventory.getFirstItem();
        ItemStack second = inventory.getSecondItem();
        if (first == null || first.isEmpty() || second == null || second.isEmpty()) {
            return;
        }

        Map<CustomEnchantment, Integer> supplied = items.enchantments(second);
        if (supplied.isEmpty()) {
            return;
        }

        ItemStack result = event.getResult() == null ? first.clone() : event.getResult().clone();
        int appliedLevels = 0;
        for (Map.Entry<CustomEnchantment, Integer> entry : supplied.entrySet()) {
            CustomEnchantment enchantment = entry.getKey();
            if (!enchantment.canApply(result) || enchantment.conflictsWith(result)) {
                continue;
            }
            int current = items.level(result, enchantment);
            int suppliedLevel = entry.getValue();
            int combined = current == suppliedLevel
                    ? Math.min(enchantment.maxLevel(), current + 1)
                    : Math.max(current, suppliedLevel);
            if (combined <= current || !items.apply(
                    result,
                    enchantment,
                    combined,
                    event.getView().getPlayer()
            )) {
                continue;
            }
            appliedLevels += combined;
        }
        if (appliedLevels == 0) {
            return;
        }

        String rename = event.getView().getRenameText();
        if (rename != null && !rename.isBlank()) {
            ItemMeta meta = result.getItemMeta();
            meta.customName(Component.text(rename));
            result.setItemMeta(meta);
        }
        event.getView().setMaximumRepairCost(100);
        event.getView().setRepairCost(
                Math.max(1, event.getView().getRepairCost()) + appliedLevels * 4
        );
        event.setResult(result);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareGrindstone(PrepareGrindstoneEvent event) {
        ItemStack result = event.getResult();
        if (result != null && !result.isEmpty()) {
            event.setResult(items.removeAll(result, event.getView().getPlayer()));
            return;
        }

        ItemStack upper = event.getInventory().getUpperItem();
        ItemStack lower = event.getInventory().getLowerItem();
        if (nonEmpty(upper) && nonEmpty(lower)) {
            return;
        }
        ItemStack only = nonEmpty(upper) ? upper : lower;
        if (nonEmpty(only) && !items.enchantments(only).isEmpty()) {
            event.setResult(items.removeAll(only, event.getView().getPlayer()));
        }
    }

    private List<CustomEnchantment> candidates(ItemStack item, int cost, boolean addsLooting) {
        return java.util.Arrays.stream(CustomEnchantment.values())
                .filter(enchantment -> cost >= enchantment.minimumTableCost())
                .filter(enchantment -> enchantment.canApply(item))
                .filter(enchantment -> !enchantment.conflictsWith(item))
                .filter(enchantment -> enchantment != CustomEnchantment.BEHEADING || !addsLooting)
                .filter(enchantment -> items.level(item, enchantment) < enchantment.maxLevel())
                .toList();
    }

    private CustomEnchantment weighted(List<CustomEnchantment> candidates) {
        int total = candidates.stream().mapToInt(CustomEnchantment::tableWeight).sum();
        int roll = random.nextInt(total);
        for (CustomEnchantment enchantment : candidates.stream()
                .sorted(Comparator.comparing(CustomEnchantment::key))
                .toList()) {
            roll -= enchantment.tableWeight();
            if (roll < 0) {
                return enchantment;
            }
        }
        return candidates.getFirst();
    }

    private int randomLevel(CustomEnchantment enchantment) {
        return enchantment.maxLevel() == 1 ? 1 : random.nextInt(enchantment.maxLevel()) + 1;
    }

    private void reveal(Player player, CustomEnchantment enchantment, int level) {
        messages.send(
                player,
                "enchantments.discovered",
                Placeholder.component("enchantment", messages.component(player, enchantment.nameKey())),
                Placeholder.unparsed("level", CustomEnchantItemService.roman(level))
        );
        feedback.play(player, FeedbackService.ENCHANT_DISCOVERED);
    }

    private boolean nonEmpty(ItemStack item) {
        return item != null && !item.isEmpty();
    }
}

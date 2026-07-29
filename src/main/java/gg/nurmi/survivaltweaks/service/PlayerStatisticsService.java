package gg.nurmi.survivaltweaks.service;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;

import java.util.Comparator;
import java.util.List;

public final class PlayerStatisticsService {

    private static final List<Material> TRACKED_TOOLS = List.of(
            Material.WOODEN_PICKAXE,
            Material.STONE_PICKAXE,
            Material.IRON_PICKAXE,
            Material.GOLDEN_PICKAXE,
            Material.DIAMOND_PICKAXE,
            Material.NETHERITE_PICKAXE,
            Material.WOODEN_AXE,
            Material.STONE_AXE,
            Material.IRON_AXE,
            Material.GOLDEN_AXE,
            Material.DIAMOND_AXE,
            Material.NETHERITE_AXE,
            Material.WOODEN_SHOVEL,
            Material.STONE_SHOVEL,
            Material.IRON_SHOVEL,
            Material.GOLDEN_SHOVEL,
            Material.DIAMOND_SHOVEL,
            Material.NETHERITE_SHOVEL,
            Material.WOODEN_HOE,
            Material.STONE_HOE,
            Material.IRON_HOE,
            Material.GOLDEN_HOE,
            Material.DIAMOND_HOE,
            Material.NETHERITE_HOE,
            Material.WOODEN_SWORD,
            Material.STONE_SWORD,
            Material.IRON_SWORD,
            Material.GOLDEN_SWORD,
            Material.DIAMOND_SWORD,
            Material.NETHERITE_SWORD,
            Material.BOW,
            Material.CROSSBOW,
            Material.TRIDENT,
            Material.MACE,
            Material.FISHING_ROD,
            Material.SHEARS
    );

    public Snapshot snapshot(OfflinePlayer player) {
        Overview overview = new Overview(
                value(player, Statistic.PLAY_ONE_MINUTE),
                value(player, Statistic.TOTAL_WORLD_TIME),
                value(player, Statistic.DEATHS),
                value(player, Statistic.MOB_KILLS),
                value(player, Statistic.PLAYER_KILLS),
                value(player, Statistic.JUMP),
                value(player, Statistic.SLEEP_IN_BED)
        );
        Travel travel = new Travel(
                value(player, Statistic.WALK_ONE_CM),
                value(player, Statistic.SPRINT_ONE_CM),
                value(player, Statistic.CROUCH_ONE_CM),
                value(player, Statistic.SWIM_ONE_CM),
                value(player, Statistic.BOAT_ONE_CM),
                value(player, Statistic.MINECART_ONE_CM),
                value(player, Statistic.AVIATE_ONE_CM),
                value(player, Statistic.HORSE_ONE_CM)
        );
        Combat combat = new Combat(
                value(player, Statistic.MOB_KILLS),
                value(player, Statistic.PLAYER_KILLS),
                value(player, Statistic.DEATHS),
                value(player, Statistic.DAMAGE_DEALT),
                value(player, Statistic.DAMAGE_TAKEN),
                value(player, Statistic.DAMAGE_BLOCKED_BY_SHIELD),
                value(player, Statistic.RAID_WIN),
                value(player, Statistic.TARGET_HIT)
        );
        Activities activities = new Activities(
                value(player, Statistic.ANIMALS_BRED),
                value(player, Statistic.FISH_CAUGHT),
                value(player, Statistic.ITEM_ENCHANTED),
                value(player, Statistic.TRADED_WITH_VILLAGER),
                value(player, Statistic.TALKED_TO_VILLAGER),
                value(player, Statistic.CHEST_OPENED),
                value(player, Statistic.ENDERCHEST_OPENED),
                value(player, Statistic.SHULKER_BOX_OPENED)
        );
        List<ToolUse> favoriteTools = TRACKED_TOOLS.stream()
                .map(material -> new ToolUse(
                        material,
                        Math.max(0, player.getStatistic(Statistic.USE_ITEM, material))
                ))
                .filter(tool -> tool.uses() > 0)
                .sorted(Comparator.comparingInt(ToolUse::uses).reversed()
                        .thenComparing(tool -> tool.material().name()))
                .limit(3)
                .toList();
        return new Snapshot(overview, travel, combat, activities, favoriteTools);
    }

    private int value(OfflinePlayer player, Statistic statistic) {
        return Math.max(0, player.getStatistic(statistic));
    }

    public record Snapshot(
            Overview overview,
            Travel travel,
            Combat combat,
            Activities activities,
            List<ToolUse> favoriteTools
    ) {

        public Snapshot {
            favoriteTools = List.copyOf(favoriteTools);
        }
    }

    public record Overview(
            int playTimeTicks,
            int worldTimeTicks,
            int deaths,
            int mobKills,
            int playerKills,
            int jumps,
            int timesSlept
    ) {
    }

    public record Travel(
            int walked,
            int sprinted,
            int crouched,
            int swum,
            int boated,
            int minecart,
            int elytra,
            int horseback
    ) {
    }

    public record Combat(
            int mobKills,
            int playerKills,
            int deaths,
            int damageDealt,
            int damageTaken,
            int damageBlocked,
            int raidsWon,
            int targetsHit
    ) {
    }

    public record Activities(
            int animalsBred,
            int fishCaught,
            int itemsEnchanted,
            int villagerTrades,
            int villagersTalkedTo,
            int chestsOpened,
            int enderChestsOpened,
            int shulkersOpened
    ) {
    }

    public record ToolUse(Material material, int uses) {
    }
}

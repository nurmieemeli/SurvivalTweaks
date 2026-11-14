package gg.nurmi.survivaltweaks.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToIntFunction;

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
        return snapshot(player::getStatistic, material -> player.getStatistic(Statistic.USE_ITEM, material));
    }

    public Optional<Snapshot> offlineSnapshot(Path folder, UUID playerId, String name, int dataVersion)
            throws IOException {
        Path file = folder.resolve(playerId + ".json");
        try {
            return readSnapshot(file, dataVersion);
        } catch (NoSuchFileException exception) {
            if (name != null && name.matches("[A-Za-z0-9_]{1,16}")) {
                try {
                    return readSnapshot(folder.resolve(name + ".json"), dataVersion);
                } catch (NoSuchFileException ignored) {
                    // No statistics have been saved yet.
                }
            }
            return Optional.of(snapshot(stat -> 0, material -> 0));
        }
    }

    private Optional<Snapshot> readSnapshot(Path file, int dataVersion) throws IOException {
        JsonObject document;
        try (var reader = Files.newBufferedReader(file)) {
            document = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException exception) {
            throw new IOException("Could not read the player's statistics file", exception);
        }
        JsonElement version = document.get("DataVersion");
        if (number(version) != dataVersion) {
            return Optional.empty();
        }
        JsonObject stats = section(document, "stats");
        JsonObject custom = section(stats, "minecraft:custom");
        JsonObject items = section(stats, "minecraft:used");
        return Optional.of(snapshot(stat -> number(custom.get(statisticKey(stat))),
                material -> number(items.get(material.getKey().toString()))));
    }

    private static JsonObject section(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static int number(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? Math.max(0, value.getAsInt()) : 0;
    }

    private static String statisticKey(Statistic statistic) {
        return switch (statistic) {
            case PLAY_ONE_MINUTE -> "minecraft:play_time";
            case ITEM_ENCHANTED -> "minecraft:enchant_item";
            case CHEST_OPENED -> "minecraft:open_chest";
            case ENDERCHEST_OPENED -> "minecraft:open_enderchest";
            case SHULKER_BOX_OPENED -> "minecraft:open_shulker_box";
            default -> statistic.getKey().toString();
        };
    }

    private Snapshot snapshot(ToIntFunction<Statistic> custom, ToIntFunction<Material> tools) {
        Overview overview = new Overview(
                value(custom, Statistic.PLAY_ONE_MINUTE),
                value(custom, Statistic.TOTAL_WORLD_TIME),
                value(custom, Statistic.DEATHS),
                value(custom, Statistic.MOB_KILLS),
                value(custom, Statistic.PLAYER_KILLS),
                value(custom, Statistic.JUMP),
                value(custom, Statistic.SLEEP_IN_BED)
        );
        Travel travel = new Travel(
                value(custom, Statistic.WALK_ONE_CM),
                value(custom, Statistic.SPRINT_ONE_CM),
                value(custom, Statistic.CROUCH_ONE_CM),
                value(custom, Statistic.SWIM_ONE_CM),
                value(custom, Statistic.BOAT_ONE_CM),
                value(custom, Statistic.MINECART_ONE_CM),
                value(custom, Statistic.AVIATE_ONE_CM),
                value(custom, Statistic.HORSE_ONE_CM)
        );
        Combat combat = new Combat(
                value(custom, Statistic.MOB_KILLS),
                value(custom, Statistic.PLAYER_KILLS),
                value(custom, Statistic.DEATHS),
                value(custom, Statistic.DAMAGE_DEALT),
                value(custom, Statistic.DAMAGE_TAKEN),
                value(custom, Statistic.DAMAGE_BLOCKED_BY_SHIELD),
                value(custom, Statistic.RAID_WIN),
                value(custom, Statistic.TARGET_HIT)
        );
        Activities activities = new Activities(
                value(custom, Statistic.ANIMALS_BRED),
                value(custom, Statistic.FISH_CAUGHT),
                value(custom, Statistic.ITEM_ENCHANTED),
                value(custom, Statistic.TRADED_WITH_VILLAGER),
                value(custom, Statistic.TALKED_TO_VILLAGER),
                value(custom, Statistic.CHEST_OPENED),
                value(custom, Statistic.ENDERCHEST_OPENED),
                value(custom, Statistic.SHULKER_BOX_OPENED)
        );
        List<ToolUse> favoriteTools = TRACKED_TOOLS.stream()
                .map(material -> new ToolUse(
                        material,
                        Math.max(0, tools.applyAsInt(material))
                ))
                .filter(tool -> tool.uses() > 0)
                .sorted(Comparator.comparingInt(ToolUse::uses).reversed()
                        .thenComparing(tool -> tool.material().name()))
                .limit(3)
                .toList();
        return new Snapshot(overview, travel, combat, activities, favoriteTools);
    }

    private static int value(ToIntFunction<Statistic> custom, Statistic statistic) {
        return Math.max(0, custom.applyAsInt(statistic));
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

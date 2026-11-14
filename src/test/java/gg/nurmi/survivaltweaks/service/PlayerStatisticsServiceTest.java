package gg.nurmi.survivaltweaks.service;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStatisticsServiceTest {
    @TempDir
    Path directory;

    @Test
    void readsCurrentPaperStatisticsFromOneFileAndDefersOlderVersions() throws Exception {
        UUID player = UUID.randomUUID();
        Files.writeString(directory.resolve(player + ".json"), """
                {"DataVersion":5023,"stats":{
                    "minecraft:custom":{
                        "minecraft:play_time":140,"minecraft:open_chest":9,
                        "minecraft:enchant_item":3,"minecraft:walk_one_cm":100
                    },
                    "minecraft:used":{"minecraft:diamond_pickaxe":42}
                }}
                """);
        var service = new PlayerStatisticsService();
        var snapshot = service.offlineSnapshot(directory, player, "Player", 5023).orElseThrow();

        assertEquals(140, snapshot.overview().playTimeTicks());
        assertEquals(100, snapshot.travel().walked());
        assertEquals(9, snapshot.activities().chestsOpened());
        assertEquals(3, snapshot.activities().itemsEnchanted());
        assertEquals(List.of(new PlayerStatisticsService.ToolUse(Material.DIAMOND_PICKAXE, 42)),
                snapshot.favoriteTools());
        assertTrue(service.offlineSnapshot(directory, player, "Player", 5024).isEmpty());
        assertEquals(0, service.offlineSnapshot(directory, UUID.randomUUID(), "NewPlayer", 5023)
                .orElseThrow().overview().playTimeTicks());
    }
}

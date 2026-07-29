package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.SurvivalTweaks;
import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

public class TreeFellerService implements Listener {

    private final SurvivalTweaks plugin;
    private final SettingsService settings;
    private final NamespacedKey PLACED_LOGS_KEY;
    private final Set<UUID> fellingPlayers = new HashSet<>();

    public TreeFellerService(SurvivalTweaks plugin, SettingsService settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.PLACED_LOGS_KEY = new NamespacedKey("survivaltweaks", "placed_logs");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isLog(event.getBlockPlaced().getType())) {
            markPlayerPlaced(event.getBlockPlaced());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        
        if (fellingPlayers.contains(player.getUniqueId())) {
            removePlayerPlaced(block);
            return;
        }

        if (!isLog(block.getType())) {
            return;
        }

        boolean wasPlaced = isPlayerPlaced(block);
        removePlayerPlaced(block);

        PluginSettings currentSettings = settings.current();
        if (!currentSettings.treeFellerEnabled()) {
            return;
        }

        if (currentSettings.treeFellerRequireSneak() && !player.isSneaking()) {
            return;
        }

        Material handType = player.getInventory().getItemInMainHand().getType();
        if (!handType.toString().endsWith("_AXE")) {
            return;
        }

        if (wasPlaced) {
            return;
        }

        fellTree(block, player, currentSettings);
    }

    private void fellTree(Block startBlock, Player player, PluginSettings settings) {
        Material logType = startBlock.getType();
        Queue<Block> queue = new LinkedList<>();
        Set<Block> visited = new HashSet<>();
        List<Block> logsToBreak = new ArrayList<>();

        queue.add(startBlock);
        visited.add(startBlock);

        boolean hasNaturalLeaves = false;

        while (!queue.isEmpty() && logsToBreak.size() < settings.treeFellerMaxBlocks()) {
            Block current = queue.poll();
            logsToBreak.add(current);

            // Search 3x3x3 around
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;

                        Block neighbor = current.getRelative(dx, dy, dz);
                        if (visited.contains(neighbor)) continue;

                        if (neighbor.getType() == logType) {
                            visited.add(neighbor);
                            if (!isPlayerPlaced(neighbor)) {
                                queue.add(neighbor);
                            }
                        } else if (Tag.LEAVES.isTagged(neighbor.getType())) {
                            BlockData data = neighbor.getBlockData();
                            if (data instanceof Leaves leaves) {
                                if (!leaves.isPersistent()) {
                                    hasNaturalLeaves = true;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!hasNaturalLeaves) {
            return;
        }

        logsToBreak.sort((b1, b2) -> Integer.compare(b2.getY(), b1.getY()));

        fellingPlayers.add(player.getUniqueId());
        try {
            for (Block log : logsToBreak) {
                if (log.equals(startBlock)) continue;
                player.breakBlock(log);
            }
        } finally {
            fellingPlayers.remove(player.getUniqueId());
        }
    }

    private boolean isLog(Material type) {
        return Tag.LOGS.isTagged(type);
    }

    public int packCoordinate(int x, int y, int z) {
        int chunkX = x & 15;
        int chunkZ = z & 15;
        int shiftedY = (y + 64) & 0x7FF; // Supports -64 up to 1983
        return (chunkX << 16) | (chunkZ << 12) | shiftedY;
    }

    private void markPlayerPlaced(Block block) {
        Chunk chunk = block.getChunk();
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        int packed = packCoordinate(block.getX(), block.getY(), block.getZ());

        int[] existing = pdc.get(PLACED_LOGS_KEY, PersistentDataType.INTEGER_ARRAY);
        if (existing == null) {
            pdc.set(PLACED_LOGS_KEY, PersistentDataType.INTEGER_ARRAY, new int[]{packed});
        } else {
            for (int i : existing) {
                if (i == packed) return;
            }
            int[] updated = Arrays.copyOf(existing, existing.length + 1);
            updated[existing.length] = packed;
            pdc.set(PLACED_LOGS_KEY, PersistentDataType.INTEGER_ARRAY, updated);
        }
    }

    private boolean isPlayerPlaced(Block block) {
        Chunk chunk = block.getChunk();
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        int[] existing = pdc.get(PLACED_LOGS_KEY, PersistentDataType.INTEGER_ARRAY);
        if (existing == null) return false;

        int packed = packCoordinate(block.getX(), block.getY(), block.getZ());
        for (int i : existing) {
            if (i == packed) return true;
        }
        return false;
    }

    private void removePlayerPlaced(Block block) {
        Chunk chunk = block.getChunk();
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        int[] existing = pdc.get(PLACED_LOGS_KEY, PersistentDataType.INTEGER_ARRAY);
        if (existing == null) return;

        int packed = packCoordinate(block.getX(), block.getY(), block.getZ());
        boolean found = false;
        for (int i : existing) {
            if (i == packed) {
                found = true;
                break;
            }
        }
        if (!found) return;

        if (existing.length == 1) {
            pdc.remove(PLACED_LOGS_KEY);
            return;
        }

        int[] updated = new int[existing.length - 1];
        int index = 0;
        for (int i : existing) {
            if (i != packed) {
                if (index < updated.length) {
                    updated[index++] = i;
                }
            }
        }
        pdc.set(PLACED_LOGS_KEY, PersistentDataType.INTEGER_ARRAY, updated);
    }
}

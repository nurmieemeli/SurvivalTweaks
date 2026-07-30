package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.SurvivalTweaks;
import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

public final class FastLeafDecayService implements Listener, AutoCloseable {

    private static final BlockFace[] SEARCH_FACES = {
            BlockFace.UP,
            BlockFace.DOWN,
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST
    };
    private static final int LEAVES_PER_BATCH = 24;
    private static final int VANILLA_LOG_DISTANCE = 6;

    private final SurvivalTweaks plugin;
    private final SettingsService settings;
    private final TickWorkBudget workBudget;
    private final TaskFailureIsolation failures;
    private final Queue<Block> decayQueue = new ArrayDeque<>();
    private final Set<Block> queuedLeaves = new HashSet<>();
    private BukkitTask batchTask;

    public FastLeafDecayService(SurvivalTweaks plugin, SettingsService settings) {
        this(plugin, settings, null, null);
    }

    public FastLeafDecayService(
            SurvivalTweaks plugin,
            SettingsService settings,
            TickWorkBudget workBudget,
            TaskFailureIsolation failures
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.workBudget = workBudget;
        this.failures = failures;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        PluginSettings current = settings.current();
        if (!current.fastLeafDecayEnabled() || !Tag.LOGS.isTagged(event.getBlock().getType())) {
            return;
        }

        enqueueCanopy(event.getBlock(), current.fastLeafDecayRadius());
        scheduleBatch(current.fastLeafDecayDelayTicks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        PluginSettings current = settings.current();
        if (!current.fastLeafDecayEnabled()) {
            return;
        }

        enqueueCanopy(event.getBlock(), current.fastLeafDecayRadius());
        scheduleBatch(current.fastLeafDecayDelayTicks());
    }

    private void enqueueCanopy(Block center, int radius) {
        Queue<SearchNode> search = new ArrayDeque<>();
        Set<Block> visited = new HashSet<>();

        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    Block neighbor = center.getRelative(x, y, z);
                    if (isNaturalLeaf(neighbor)) {
                        search.add(new SearchNode(neighbor, 1));
                    }
                }
            }
        }

        while (!search.isEmpty()) {
            SearchNode node = search.poll();
            Block leaf = node.block();
            if (!visited.add(leaf)) {
                continue;
            }
            if (queuedLeaves.add(leaf)) {
                decayQueue.add(leaf);
            }
            if (node.distance() >= radius) {
                continue;
            }
            for (BlockFace face : SEARCH_FACES) {
                Block neighbor = leaf.getRelative(face);
                if (isNaturalLeaf(neighbor) && !visited.contains(neighbor)) {
                    search.add(new SearchNode(neighbor, node.distance() + 1));
                }
            }
        }
    }

    private void scheduleBatch(int delayTicks) {
        if (batchTask != null || decayQueue.isEmpty()) {
            return;
        }
        Runnable batch = () -> processBatch(delayTicks);
        batchTask = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                failures == null ? batch : failures.guard("fast leaf decay", batch),
                delayTicks
        );
    }

    private void processBatch(int delayTicks) {
        batchTask = null;
        if (!settings.current().fastLeafDecayEnabled()) {
            decayQueue.clear();
            queuedLeaves.clear();
            return;
        }

        int processed = 0;
        boolean budgetExhausted = false;
        while (processed < LEAVES_PER_BATCH && !decayQueue.isEmpty()) {
            if (workBudget != null && !workBudget.tryAcquire(1)) {
                budgetExhausted = true;
                break;
            }
            Block leaf = decayQueue.poll();
            queuedLeaves.remove(leaf);
            if (isNaturalLeaf(leaf) && !hasSupportingLog(leaf)) {
                leaf.breakNaturally();
                enqueueCanopy(leaf, 1);
            }
            processed++;
        }

        if (!decayQueue.isEmpty()) {
            scheduleBatch(budgetExhausted ? 1 : delayTicks);
        }
    }

    boolean isNaturalLeaf(Block block) {
        return isNaturalLeaf(Tag.LEAVES.isTagged(block.getType()), block.getBlockData());
    }

    boolean isNaturalLeaf(boolean leafMaterial, BlockData blockData) {
        return leafMaterial
                && blockData instanceof Leaves leaves
                && !leaves.isPersistent();
    }

    private boolean hasSupportingLog(Block leaf) {
        BlockData data = leaf.getBlockData();
        if (data instanceof Leaves leaves
                && leaves.getDistance() >= leaves.getMaximumDistance()) {
            return false;
        }

        Queue<SearchNode> search = new ArrayDeque<>();
        Set<Block> visited = new HashSet<>();
        search.add(new SearchNode(leaf, 0));
        visited.add(leaf);

        while (!search.isEmpty()) {
            SearchNode node = search.poll();
            for (BlockFace face : SEARCH_FACES) {
                Block neighbor = node.block().getRelative(face);
                if (Tag.LOGS.isTagged(neighbor.getType())) {
                    return true;
                }
                int nextDistance = node.distance() + 1;
                if (nextDistance < VANILLA_LOG_DISTANCE
                        && Tag.LEAVES.isTagged(neighbor.getType())
                        && visited.add(neighbor)) {
                    search.add(new SearchNode(neighbor, nextDistance));
                }
            }
        }
        return false;
    }

    @Override
    public void close() {
        if (batchTask != null) {
            batchTask.cancel();
            batchTask = null;
        }
        decayQueue.clear();
        queuedLeaves.clear();
    }

    private record SearchNode(Block block, int distance) {
    }
}

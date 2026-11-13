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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
    private final Queue<Discovery> discovery = new ArrayDeque<>();
    private final Map<Block, Integer> discovered = new HashMap<>();
    private final Queue<Block> decayQueue = new ArrayDeque<>();
    private final Set<Block> queuedLeaves = new HashSet<>();
    private BukkitTask batchTask;
    private boolean decayNext;

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
        discovery.add(new Discovery(center, radius, true));
    }

    private void discover(Discovery node) {
        Block block = node.block();
        int radius = node.remaining();
        if (!isLoaded(block)) {
            return;
        }
        if (node.center()) {
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x != 0 || y != 0 || z != 0) {
                            discoverLeaf(block.getRelative(x, y, z), radius);
                        }
                    }
                }
            }
            return;
        }
        if (queuedLeaves.add(block)) {
            decayQueue.add(block);
        }
        if (radius > 1) {
            for (BlockFace face : SEARCH_FACES) {
                discoverLeaf(block.getRelative(face), radius - 1);
            }
        }
    }

    private void discoverLeaf(Block block, int remaining) {
        if (discovered.getOrDefault(block, 0) < remaining
                && isLoaded(block) && isNaturalLeaf(block)) {
            discovered.put(block, remaining);
            discovery.add(new Discovery(block, remaining, false));
        }
    }

    private void scheduleBatch(int delayTicks) {
        if (batchTask != null || (discovery.isEmpty() && decayQueue.isEmpty())) {
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
            discovery.clear();
            discovered.clear();
            decayQueue.clear();
            queuedLeaves.clear();
            return;
        }

        int processed = 0;
        boolean budgetExhausted = false;
        while (processed < LEAVES_PER_BATCH && (!discovery.isEmpty() || !decayQueue.isEmpty())) {
            if (workBudget != null
                    && !workBudget.tryAcquire(TickWorkBudget.Lane.LEAF_DECAY, 1)) {
                budgetExhausted = true;
                break;
            }
            processed++;
            if (!discovery.isEmpty() && (!decayNext || decayQueue.isEmpty())) {
                discover(discovery.remove());
                decayNext = true;
                if (discovery.isEmpty()) {
                    discovered.clear();
                }
                continue;
            }
            decayNext = false;
            Block leaf = decayQueue.remove();
            queuedLeaves.remove(leaf);
            if (isLoaded(leaf) && isNaturalLeaf(leaf) && !hasSupportingLog(leaf)) {
                leaf.breakNaturally();
                enqueueCanopy(leaf, 1);
            }
        }

        if (!discovery.isEmpty() || !decayQueue.isEmpty()) {
            scheduleBatch(budgetExhausted ? 1 : delayTicks);
        }
    }

    private boolean isLoaded(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
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
                if (!isLoaded(neighbor) || Tag.LOGS.isTagged(neighbor.getType())) {
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
        discovery.clear();
        discovered.clear();
        decayQueue.clear();
        queuedLeaves.clear();
    }

    public int queuedLeaves() {
        return decayQueue.size() + discovery.size();
    }

    private record Discovery(Block block, int remaining, boolean center) {
    }

    private record SearchNode(Block block, int distance) {
    }
}

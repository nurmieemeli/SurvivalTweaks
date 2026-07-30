package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.BlockKey;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class ContainerBlockResolver {

    public Optional<Target> target(Player player, int distance) {
        Block block = player.getTargetBlockExact(distance);
        if (block == null || !(block.getState() instanceof Container)) {
            return Optional.empty();
        }
        return Optional.of(new Target(block, blocksFor(block)));
    }

    public Set<BlockKey> blocksFor(Block block) {
        if (!(block.getState() instanceof Container container)) {
            return Set.of();
        }
        Set<BlockKey> blocks = blocksFor(container.getInventory());
        return blocks.isEmpty() ? Set.of(BlockKey.from(block)) : blocks;
    }

    public Set<BlockKey> blocksFor(Inventory inventory) {
        Set<BlockKey> blocks = new LinkedHashSet<>();
        if (inventory instanceof DoubleChestInventory doubleChest) {
            addInventoryBlock(blocks, doubleChest.getLeftSide());
            addInventoryBlock(blocks, doubleChest.getRightSide());
        } else {
            addInventoryBlock(blocks, inventory);
        }
        return Set.copyOf(blocks);
    }

    /**
     * Resolves the one or two backing blocks without allocating a collection.
     * Inventory move events use this path because hopper networks can produce
     * a very large number of checks per tick.
     */
    public BlockPair blockPairFor(Inventory inventory) {
        if (inventory instanceof DoubleChestInventory doubleChest) {
            return new BlockPair(
                    inventoryBlock(doubleChest.getLeftSide()),
                    inventoryBlock(doubleChest.getRightSide())
            );
        }
        return new BlockPair(inventoryBlock(inventory), null);
    }

    private void addInventoryBlock(Set<BlockKey> blocks, Inventory inventory) {
        BlockKey block = inventoryBlock(inventory);
        if (block != null) {
            blocks.add(block);
        }
    }

    private BlockKey inventoryBlock(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Container container) {
            return BlockKey.from(container.getBlock());
        }

        Location location = inventory.getLocation();
        if (location != null && location.getBlock().getState() instanceof Container container) {
            return BlockKey.from(container.getBlock());
        }
        return null;
    }

    public record Target(Block block, Set<BlockKey> blocks) {
    }

    public record BlockPair(BlockKey first, BlockKey second) {
    }
}

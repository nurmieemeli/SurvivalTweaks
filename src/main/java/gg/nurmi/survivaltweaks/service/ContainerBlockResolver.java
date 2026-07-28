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

    private void addInventoryBlock(Set<BlockKey> blocks, Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Container container) {
            blocks.add(BlockKey.from(container.getBlock()));
            return;
        }

        Location location = inventory.getLocation();
        if (location != null && location.getBlock().getState() instanceof Container) {
            blocks.add(BlockKey.from(location.getBlock()));
        }
    }

    public record Target(Block block, Set<BlockKey> blocks) {
    }
}

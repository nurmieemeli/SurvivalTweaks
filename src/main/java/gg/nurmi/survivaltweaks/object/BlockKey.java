package gg.nurmi.survivaltweaks.object;

import org.bukkit.block.Block;

import java.util.Objects;
import java.util.UUID;

public record BlockKey(UUID worldId, int x, int y, int z) {

    public BlockKey {
        Objects.requireNonNull(worldId, "worldId");
    }

    public static BlockKey from(Block block) {
        return new BlockKey(
                block.getWorld().getUID(),
                block.getX(),
                block.getY(),
                block.getZ()
        );
    }
}

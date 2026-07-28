package gg.nurmi.survivaltweaks.object;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;
import java.util.UUID;

public record NewPlayerSpawnLocation(
        UUID worldId,
        String worldName,
        int x,
        int y,
        int z,
        float yaw
) {

    public NewPlayerSpawnLocation {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(worldName, "worldName");
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
    }

    public Location toLocation(World world) {
        return new Location(world, x + 0.5, y, z + 0.5, yaw, 0.0f);
    }

    public long horizontalDistanceSquared(NewPlayerSpawnLocation other) {
        long deltaX = (long) x - other.x;
        long deltaZ = (long) z - other.z;
        return deltaX * deltaX + deltaZ * deltaZ;
    }
}

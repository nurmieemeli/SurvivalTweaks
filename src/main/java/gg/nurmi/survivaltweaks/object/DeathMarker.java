package gg.nurmi.survivaltweaks.object;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record DeathMarker(
        UUID playerId,
        UUID worldId,
        String worldName,
        double x,
        double y,
        double z,
        Instant createdAt,
        Instant expiresAt,
        String cause
) {

    public DeathMarker {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        cause = cause == null ? "UNKNOWN" : cause;
    }

    public static DeathMarker at(UUID playerId, Location location, Instant now, Instant expiresAt, String cause) {
        World world = Objects.requireNonNull(location.getWorld(), "world");
        return new DeathMarker(
                playerId,
                world.getUID(),
                world.getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                now,
                expiresAt,
                cause
        );
    }

    public boolean expired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public Optional<Location> resolve(Server server) {
        World world = server.getWorld(worldId);
        if (world == null) {
            world = server.getWorld(worldName);
        }
        return world == null ? Optional.empty() : Optional.of(new Location(world, x, y, z));
    }
}

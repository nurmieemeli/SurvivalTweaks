package gg.nurmi.survivaltweaks.object;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record Home(
        String name,
        UUID worldId,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        Material icon,
        String description,
        boolean favorite,
        int order,
        HomeCategory category,
        HomeArrivalStyle arrivalStyle
) {

    public Home(
            String name,
            UUID worldId,
            String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {
        this(
                name, worldId, worldName, x, y, z, yaw, pitch,
                Material.ENDER_PEARL, "", false, 0, HomeCategory.OTHER, HomeArrivalStyle.DEFAULT
        );
    }

    public Home(
            String name,
            UUID worldId,
            String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            Material icon,
            String description,
            boolean favorite,
            int order
    ) {
        this(
                name, worldId, worldName, x, y, z, yaw, pitch,
                icon, description, favorite, order, HomeCategory.OTHER, HomeArrivalStyle.DEFAULT
        );
    }

    public Home {
        name = requireText(name, "name");
        worldName = requireText(worldName, "worldName");
        icon = icon == null ? Material.ENDER_PEARL : icon;
        description = description == null ? "" : description.strip();
        category = category == null ? HomeCategory.OTHER : category;
        arrivalStyle = arrivalStyle == null ? HomeArrivalStyle.DEFAULT : arrivalStyle;
    }

    public static Home at(String name, Location location) {
        Objects.requireNonNull(location, "location");
        World world = Objects.requireNonNull(location.getWorld(), "location world");
        return new Home(
                name,
                world.getUID(),
                world.getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch(),
                Material.ENDER_PEARL,
                "",
                false,
                0,
                HomeCategory.OTHER,
                HomeArrivalStyle.DEFAULT
        );
    }

    public Home withIcon(Material updatedIcon) {
        return copy(name, worldId, worldName, x, y, z, yaw, pitch, updatedIcon, description, favorite, order, category, arrivalStyle);
    }

    public Home withDescription(String updatedDescription) {
        return copy(name, worldId, worldName, x, y, z, yaw, pitch, icon, updatedDescription, favorite, order, category, arrivalStyle);
    }

    public Home withFavorite(boolean updatedFavorite) {
        return copy(name, worldId, worldName, x, y, z, yaw, pitch, icon, description, updatedFavorite, order, category, arrivalStyle);
    }

    public Home withOrder(int updatedOrder) {
        return copy(name, worldId, worldName, x, y, z, yaw, pitch, icon, description, favorite, updatedOrder, category, arrivalStyle);
    }

    public Home withName(String updatedName) {
        return copy(updatedName, worldId, worldName, x, y, z, yaw, pitch, icon, description, favorite, order, category, arrivalStyle);
    }

    public Home withCategory(HomeCategory updatedCategory) {
        return copy(name, worldId, worldName, x, y, z, yaw, pitch, icon, description, favorite, order, updatedCategory, arrivalStyle);
    }

    public Home withArrivalStyle(HomeArrivalStyle updatedStyle) {
        return copy(name, worldId, worldName, x, y, z, yaw, pitch, icon, description, favorite, order, category, updatedStyle);
    }

    public Home withLocation(Location location) {
        World world = Objects.requireNonNull(location.getWorld(), "location world");
        return copy(
                name, world.getUID(), world.getName(),
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(),
                icon, description, favorite, order, category, arrivalStyle
        );
    }

    public Optional<Location> resolve(Server server) {
        World world = worldId == null ? null : server.getWorld(worldId);
        if (world == null) {
            world = server.getWorld(worldName);
        }
        if (world == null) {
            return Optional.empty();
        }
        return Optional.of(new Location(world, x, y, z, yaw, pitch));
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static Home copy(
            String name,
            UUID worldId,
            String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            Material icon,
            String description,
            boolean favorite,
            int order,
            HomeCategory category,
            HomeArrivalStyle arrivalStyle
    ) {
        return new Home(
                name, worldId, worldName, x, y, z, yaw, pitch,
                icon, description, favorite, order, category, arrivalStyle
        );
    }
}

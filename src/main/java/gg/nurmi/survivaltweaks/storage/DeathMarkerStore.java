package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.DeathMarker;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DeathMarkerStore {

    private final Path file;
    private final Logger logger;

    public DeathMarkerStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public List<DeathMarker> load() {
        if (Files.notExists(file)) {
            return List.of();
        }
        List<DeathMarker> markers = new ArrayList<>();
        for (Map<?, ?> values : YamlConfiguration.loadConfiguration(file.toFile()).getMapList("markers")) {
            try {
                markers.add(new DeathMarker(
                        UUID.fromString(text(values, "player")),
                        UUID.fromString(text(values, "world-uuid")),
                        text(values, "world"),
                        number(values, "x").doubleValue(),
                        number(values, "y").doubleValue(),
                        number(values, "z").doubleValue(),
                        Instant.parse(text(values, "created-at")),
                        Instant.parse(text(values, "expires-at")),
                        Objects.toString(values.get("cause"), "UNKNOWN")
                ));
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Skipped an invalid death marker", exception);
            }
        }
        return List.copyOf(markers);
    }

    public void save(Collection<DeathMarker> markers) throws IOException {
        Path parent = Objects.requireNonNull(file.getParent());
        Files.createDirectories(parent);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        yaml.set("markers", markers.stream().map(this::serialize).toList());
        Path temporary = Files.createTempFile(parent, "death-markers-", ".tmp");
        try {
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Map<String, Object> serialize(DeathMarker marker) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("player", marker.playerId().toString());
        values.put("world-uuid", marker.worldId().toString());
        values.put("world", marker.worldName());
        values.put("x", marker.x());
        values.put("y", marker.y());
        values.put("z", marker.z());
        values.put("created-at", marker.createdAt().toString());
        values.put("expires-at", marker.expiresAt().toString());
        values.put("cause", marker.cause());
        return values;
    }

    private String text(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value.toString();
    }

    private Number number(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return number;
    }
}

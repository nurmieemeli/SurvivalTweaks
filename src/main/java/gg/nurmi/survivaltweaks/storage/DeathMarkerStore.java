package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.DeathMarker;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

final class DeathMarkerStore {

    private final Logger logger;

    DeathMarkerStore(Logger logger) {
        this.logger = logger;
    }

    List<DeathMarker> load(YamlConfiguration yaml) {
        List<DeathMarker> markers = new ArrayList<>();
        for (Map<?, ?> values : yaml.getMapList("markers")) {
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

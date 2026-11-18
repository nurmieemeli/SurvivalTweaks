package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.NewPlayerSpawnAssignment;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnLocation;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnState;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static gg.nurmi.survivaltweaks.storage.LegacyYamlFields.number;
import static gg.nurmi.survivaltweaks.storage.LegacyYamlFields.text;

final class NewPlayerSpawnStore {

    private final Logger logger;

    NewPlayerSpawnStore(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    NewPlayerSpawnState load(YamlConfiguration yaml) {
        List<NewPlayerSpawnLocation> available = new ArrayList<>();
        List<NewPlayerSpawnLocation> retired = new ArrayList<>();
        Map<UUID, NewPlayerSpawnAssignment> assignments = new LinkedHashMap<>();
        Set<UUID> awaitingReplacement = new LinkedHashSet<>();
        for (Map<?, ?> values : yaml.getMapList("available")) {
            try {
                available.add(deserializeLocation(values));
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Skipped an invalid preloaded new-player spawn", exception);
            }
        }
        for (Map<?, ?> values : yaml.getMapList("assignments")) {
            try {
                UUID playerId = UUID.fromString(text(values, "player"));
                NewPlayerSpawnLocation location = deserializeLocation(map(values, "location"));
                var assignment = new NewPlayerSpawnAssignment(playerId, location,
                        Boolean.TRUE.equals(values.get("completed")));
                if (assignments.putIfAbsent(playerId, assignment) != null) {
                    throw new IllegalArgumentException("Duplicate spawn assignment for player " + playerId);
                }
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Skipped an invalid new-player spawn assignment: " + exception.getMessage(),
                        exception);
            }
        }
        for (Map<?, ?> values : yaml.getMapList("retired")) {
            try {
                retired.add(deserializeLocation(values));
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Skipped an invalid retired new-player spawn", exception);
            }
        }
        for (String value : yaml.getStringList("awaiting-replacement")) {
            try {
                awaitingReplacement.add(UUID.fromString(value));
            } catch (IllegalArgumentException exception) {
                logger.log(Level.WARNING, "Skipped an invalid replacement player UUID", exception);
            }
        }
        return new NewPlayerSpawnState(available, assignments, retired, awaitingReplacement);
    }

    private NewPlayerSpawnLocation deserializeLocation(Map<?, ?> values) {
        return new NewPlayerSpawnLocation(
                UUID.fromString(text(values, "world-uuid")),
                text(values, "world"),
                number(values, "x").intValue(),
                number(values, "y").intValue(),
                number(values, "z").intValue(),
                number(values, "yaw").floatValue()
        );
    }

    private Map<?, ?> map(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Map<?, ?> mapped)) {
            throw new IllegalArgumentException("Missing map field '" + key + "'");
        }
        return mapped;
    }

}

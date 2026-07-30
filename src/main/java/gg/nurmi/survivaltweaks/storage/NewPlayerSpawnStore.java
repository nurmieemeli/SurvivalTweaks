package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.NewPlayerSpawnAssignment;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnLocation;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnState;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

public final class NewPlayerSpawnStore implements NewPlayerSpawnDataStore {

    public static final int SCHEMA_VERSION = 2;

    private final Path file;
    private final Logger logger;

    public NewPlayerSpawnStore(Path file, Logger logger) {
        this.file = Objects.requireNonNull(file, "file");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public NewPlayerSpawnState load() {
        if (Files.notExists(file)) {
            return NewPlayerSpawnState.EMPTY;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
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
                assignments.put(
                        playerId,
                        new NewPlayerSpawnAssignment(
                                playerId,
                                location,
                                Boolean.TRUE.equals(values.get("completed"))
                        )
                );
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Skipped an invalid new-player spawn assignment", exception);
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

    @Override
    public NewPlayerSpawnState loadSpawnState() {
        return load();
    }

    public void save(NewPlayerSpawnState state) throws IOException {
        Path parent = Objects.requireNonNull(file.getParent(), "spawn file parent");
        Files.createDirectories(parent);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", SCHEMA_VERSION);
        yaml.set("available", state.available().stream().map(this::serializeLocation).toList());
        yaml.set("assignments", state.assignments().values().stream()
                .sorted(java.util.Comparator.comparing(assignment -> assignment.playerId().toString()))
                .map(this::serializeAssignment)
                .toList());
        yaml.set("retired", state.retired().stream().map(this::serializeLocation).toList());
        yaml.set(
                "awaiting-replacement",
                state.awaitingReplacement().stream().map(UUID::toString).sorted().toList()
        );
        Path temporary = Files.createTempFile(parent, "new-player-spawns-", ".tmp");
        try {
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            replaceAtomically(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void saveSpawnState(NewPlayerSpawnState state) throws IOException {
        save(state);
    }

    private Map<String, Object> serializeAssignment(NewPlayerSpawnAssignment assignment) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("player", assignment.playerId().toString());
        values.put("completed", assignment.completed());
        values.put("location", serializeLocation(assignment.location()));
        return values;
    }

    private Map<String, Object> serializeLocation(NewPlayerSpawnLocation location) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("world-uuid", location.worldId().toString());
        values.put("world", location.worldName());
        values.put("x", location.x());
        values.put("y", location.y());
        values.put("z", location.z());
        values.put("yaw", location.yaw());
        return values;
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

    private String text(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing text field '" + key + "'");
        }
        return value.toString();
    }

    private Number number(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Missing numeric field '" + key + "'");
        }
        return number;
    }

    private void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

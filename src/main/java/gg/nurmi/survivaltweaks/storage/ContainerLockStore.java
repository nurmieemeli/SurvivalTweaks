package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.object.ContainerLockSnapshot;
import gg.nurmi.survivaltweaks.object.LockAccessMode;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ContainerLockStore {

    static final int SCHEMA_VERSION = 3;

    private final Path file;
    private final Logger logger;

    public ContainerLockStore(Path file, Logger logger) {
        this.file = Objects.requireNonNull(file, "file");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public List<ContainerLockSnapshot> load() {
        if (Files.notExists(file)) {
            return List.of();
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        List<ContainerLockSnapshot> locks = new ArrayList<>();
        for (Map<?, ?> serialized : yaml.getMapList("locks")) {
            try {
                locks.add(deserialize(serialized));
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Skipped an invalid container lock in " + file.getFileName(), exception);
            }
        }
        return List.copyOf(locks);
    }

    public void save(Collection<ContainerLockSnapshot> locks) throws IOException {
        Path parent = Objects.requireNonNull(file.getParent(), "lock file parent");
        Files.createDirectories(parent);

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", SCHEMA_VERSION);
        yaml.set("locks", locks.stream().map(this::serialize).toList());

        Path temporary = Files.createTempFile(parent, "container-locks-", ".tmp");
        try {
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            replaceAtomically(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private ContainerLockSnapshot deserialize(Map<?, ?> serialized) {
        UUID id = UUID.fromString(text(serialized, "id"));
        UUID ownerId = UUID.fromString(text(serialized, "owner"));

        Set<UUID> trusted = new LinkedHashSet<>();
        Object trustedValue = serialized.get("trusted");
        if (trustedValue instanceof Collection<?> values) {
            values.forEach(value -> trusted.add(UUID.fromString(value.toString())));
        }

        Set<BlockKey> blocks = new LinkedHashSet<>();
        Object blocksValue = serialized.get("blocks");
        if (!(blocksValue instanceof Collection<?> values)) {
            throw new IllegalArgumentException("Missing block list");
        }
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> block)) {
                throw new IllegalArgumentException("Invalid block entry");
            }
            blocks.add(new BlockKey(
                    UUID.fromString(text(block, "world")),
                    number(block, "x").intValue(),
                    number(block, "y").intValue(),
                    number(block, "z").intValue()
            ));
        }
        String name = Objects.toString(serialized.get("name"), "");
        LockAccessMode accessMode;
        try {
            accessMode = LockAccessMode.valueOf(
                    Objects.toString(serialized.get("access-mode"), "TRUSTED").toUpperCase(java.util.Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            accessMode = LockAccessMode.TRUSTED;
        }
        return new ContainerLockSnapshot(
                id,
                ownerId,
                blocks,
                trusted,
                name,
                accessMode,
                Boolean.TRUE.equals(serialized.get("automation-allowed"))
        );
    }

    private Map<String, Object> serialize(ContainerLockSnapshot lock) {
        Map<String, Object> serialized = new LinkedHashMap<>();
        serialized.put("id", lock.id().toString());
        serialized.put("owner", lock.ownerId().toString());
        serialized.put("name", lock.name());
        serialized.put("access-mode", lock.accessMode().name());
        serialized.put("automation-allowed", lock.automationAllowed());
        serialized.put("trusted", lock.trustedPlayers().stream().map(UUID::toString).sorted().toList());
        serialized.put("blocks", lock.blocks().stream()
                .sorted((left, right) -> {
                    int world = left.worldId().compareTo(right.worldId());
                    if (world != 0) {
                        return world;
                    }
                    int x = Integer.compare(left.x(), right.x());
                    if (x != 0) {
                        return x;
                    }
                    int y = Integer.compare(left.y(), right.y());
                    return y != 0 ? y : Integer.compare(left.z(), right.z());
                })
                .map(this::serializeBlock)
                .toList());
        return serialized;
    }

    private Map<String, Object> serializeBlock(BlockKey block) {
        Map<String, Object> serialized = new LinkedHashMap<>();
        serialized.put("world", block.worldId().toString());
        serialized.put("x", block.x());
        serialized.put("y", block.y());
        serialized.put("z", block.z());
        return serialized;
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

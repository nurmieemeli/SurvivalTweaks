package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.object.ContainerLockSnapshot;
import gg.nurmi.survivaltweaks.object.LockAccessMode;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static gg.nurmi.survivaltweaks.storage.LegacyYamlFields.number;
import static gg.nurmi.survivaltweaks.storage.LegacyYamlFields.text;

final class ContainerLockStore {

    private final Path file;
    private final Logger logger;

    ContainerLockStore(Path file, Logger logger) {
        this.file = Objects.requireNonNull(file, "file");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    List<ContainerLockSnapshot> load(YamlConfiguration yaml) {
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

}

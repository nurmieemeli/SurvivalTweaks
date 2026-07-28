package gg.nurmi.survivaltweaks.object;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ContainerLockSnapshot(
        UUID id,
        UUID ownerId,
        Set<BlockKey> blocks,
        Set<UUID> trustedPlayers,
        String name,
        LockAccessMode accessMode,
        boolean automationAllowed
) {

    public ContainerLockSnapshot(
            UUID id,
            UUID ownerId,
            Set<BlockKey> blocks,
            Set<UUID> trustedPlayers
    ) {
        this(id, ownerId, blocks, trustedPlayers, "", LockAccessMode.TRUSTED, false);
    }

    public ContainerLockSnapshot(
            UUID id,
            UUID ownerId,
            Set<BlockKey> blocks,
            Set<UUID> trustedPlayers,
            String name,
            LockAccessMode accessMode
    ) {
        this(id, ownerId, blocks, trustedPlayers, name, accessMode, false);
    }

    public ContainerLockSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        blocks = Set.copyOf(blocks);
        trustedPlayers = Set.copyOf(trustedPlayers);
        name = name == null ? "" : name.strip();
        Objects.requireNonNull(accessMode, "accessMode");
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("A container lock must contain at least one block");
        }
    }
}

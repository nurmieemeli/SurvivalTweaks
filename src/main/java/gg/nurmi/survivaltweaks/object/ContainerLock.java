package gg.nurmi.survivaltweaks.object;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ContainerLock {

    private final UUID id;
    private UUID ownerId;
    private final Set<BlockKey> blocks;
    private final Set<UUID> trustedPlayers;
    private String name;
    private LockAccessMode accessMode;
    private boolean automationAllowed;

    public ContainerLock(UUID id, UUID ownerId, Set<BlockKey> blocks, Set<UUID> trustedPlayers) {
        this(id, ownerId, blocks, trustedPlayers, "", LockAccessMode.TRUSTED, false);
    }

    public ContainerLock(
            UUID id,
            UUID ownerId,
            Set<BlockKey> blocks,
            Set<UUID> trustedPlayers,
            String name,
            LockAccessMode accessMode
    ) {
        this(id, ownerId, blocks, trustedPlayers, name, accessMode, false);
    }

    public ContainerLock(
            UUID id,
            UUID ownerId,
            Set<BlockKey> blocks,
            Set<UUID> trustedPlayers,
            String name,
            LockAccessMode accessMode,
            boolean automationAllowed
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("A container lock must contain at least one block");
        }
        this.blocks = new LinkedHashSet<>(blocks);
        this.trustedPlayers = new LinkedHashSet<>(trustedPlayers);
        this.trustedPlayers.remove(ownerId);
        this.name = name == null ? "" : name.strip();
        this.accessMode = Objects.requireNonNull(accessMode, "accessMode");
        this.automationAllowed = automationAllowed;
    }

    public static ContainerLock create(UUID ownerId, Set<BlockKey> blocks) {
        return new ContainerLock(UUID.randomUUID(), ownerId, blocks, Set.of());
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public Set<BlockKey> blocks() {
        return Set.copyOf(blocks);
    }

    public Set<UUID> trustedPlayers() {
        return Set.copyOf(trustedPlayers);
    }

    public String name() {
        return name;
    }

    public LockAccessMode accessMode() {
        return accessMode;
    }

    public boolean automationAllowed() {
        return automationAllowed;
    }

    public boolean canAccess(UUID playerId, boolean administrator) {
        return administrator
                || ownerId.equals(playerId)
                || accessMode == LockAccessMode.PUBLIC
                || accessMode == LockAccessMode.DEPOSIT_ONLY
                || trustedPlayers.contains(playerId);
    }

    public boolean canWithdraw(UUID playerId, boolean administrator) {
        return administrator
                || ownerId.equals(playerId)
                || accessMode == LockAccessMode.PUBLIC
                || trustedPlayers.contains(playerId);
    }

    public boolean canManage(UUID playerId, boolean administrator) {
        return administrator || ownerId.equals(playerId);
    }

    public boolean trust(UUID playerId) {
        if (ownerId.equals(playerId)) {
            return false;
        }
        return trustedPlayers.add(playerId);
    }

    public boolean untrust(UUID playerId) {
        return trustedPlayers.remove(playerId);
    }

    public boolean addBlock(BlockKey block) {
        return blocks.add(block);
    }

    public boolean rename(String updatedName) {
        String normalized = updatedName == null ? "" : updatedName.strip();
        if (name.equals(normalized)) {
            return false;
        }
        name = normalized;
        return true;
    }

    public boolean accessMode(LockAccessMode updatedMode) {
        Objects.requireNonNull(updatedMode, "updatedMode");
        if (accessMode == updatedMode) {
            return false;
        }
        accessMode = updatedMode;
        return true;
    }

    public boolean transfer(UUID newOwnerId) {
        Objects.requireNonNull(newOwnerId, "newOwnerId");
        if (ownerId.equals(newOwnerId)) {
            return false;
        }
        UUID previousOwner = ownerId;
        ownerId = newOwnerId;
        trustedPlayers.remove(newOwnerId);
        trustedPlayers.add(previousOwner);
        return true;
    }

    public boolean automationAllowed(boolean updatedAllowed) {
        if (automationAllowed == updatedAllowed) {
            return false;
        }
        automationAllowed = updatedAllowed;
        return true;
    }

    public ContainerLockSnapshot snapshot() {
        return new ContainerLockSnapshot(
                id,
                ownerId,
                blocks,
                trustedPlayers,
                name,
                accessMode,
                automationAllowed
        );
    }
}

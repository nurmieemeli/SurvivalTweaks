package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.object.ContainerLock;
import gg.nurmi.survivaltweaks.object.ContainerLockSnapshot;
import gg.nurmi.survivaltweaks.storage.ContainerLockDataStore;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ContainerLockService implements AutoCloseable {

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final long INITIAL_RETRY_MILLIS = 100;
    private static final long MAX_RETRY_MILLIS = 2_000;

    private final ContainerLockDataStore store;
    private final Logger logger;
    private final PersistenceMonitor monitor;
    private final Map<UUID, ContainerLock> locksById = new HashMap<>();
    private final Map<BlockKey, ContainerLock> locksByBlock = new HashMap<>();
    private final Map<UUID, java.util.ArrayDeque<AccessAttempt>> accessHistory = new HashMap<>();
    private final AtomicReference<List<ContainerLockSnapshot>> pending = new AtomicReference<>();
    private final AtomicReference<List<ContainerLockSnapshot>> latestRequested = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final ExecutorService writer;
    private boolean closing;

    public ContainerLockService(ContainerLockDataStore store, Logger logger) {
        this(store, logger, new PersistenceMonitor());
    }

    public ContainerLockService(
            ContainerLockDataStore store,
            Logger logger,
            PersistenceMonitor monitor
    ) {
        this.store = store;
        this.logger = logger;
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.writer = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "SurvivalTweaks-container-lock-writer");
            thread.setDaemon(false);
            return thread;
        });

        load();
        latestRequested.set(snapshot());
    }

    public Optional<ContainerLock> create(UUID ownerId, Set<BlockKey> blocks) {
        if (blocks.isEmpty() || blocks.stream().anyMatch(locksByBlock::containsKey)) {
            return Optional.empty();
        }

        ContainerLock lock = ContainerLock.create(ownerId, blocks);
        locksById.put(lock.id(), lock);
        blocks.forEach(block -> locksByBlock.put(block, lock));
        markDirty();
        return Optional.of(lock);
    }

    public Optional<ContainerLock> lockFor(BlockKey block) {
        return Optional.ofNullable(locksByBlock.get(block));
    }

    public Optional<ContainerLock> lock(UUID lockId) {
        return Optional.ofNullable(locksById.get(lockId));
    }

    public Set<ContainerLock> locksFor(Collection<BlockKey> blocks) {
        Set<ContainerLock> locks = new LinkedHashSet<>();
        blocks.stream().map(locksByBlock::get).filter(java.util.Objects::nonNull).forEach(locks::add);
        return Set.copyOf(locks);
    }

    public boolean canAccess(UUID playerId, boolean administrator, Collection<BlockKey> blocks) {
        for (BlockKey block : blocks) {
            ContainerLock lock = locksByBlock.get(block);
            if (lock != null && !lock.canAccess(playerId, administrator)) {
                return false;
            }
        }
        return true;
    }

    public Optional<ContainerLock> singleLockFor(Collection<BlockKey> blocks) {
        ContainerLock selected = null;
        for (BlockKey block : blocks) {
            ContainerLock lock = locksByBlock.get(block);
            if (lock == null) {
                continue;
            }
            if (selected != null && selected != lock) {
                return Optional.empty();
            }
            selected = lock;
        }
        return Optional.ofNullable(selected);
    }

    public boolean automationAllowed(Collection<BlockKey> blocks) {
        for (BlockKey block : blocks) {
            ContainerLock lock = locksByBlock.get(block);
            if (lock != null && !lock.automationAllowed()) {
                return false;
            }
        }
        return true;
    }

    public boolean automationAllowed(BlockKey first, BlockKey second) {
        ContainerLock firstLock = first == null ? null : locksByBlock.get(first);
        if (firstLock != null && !firstLock.automationAllowed()) {
            return false;
        }
        ContainerLock secondLock = second == null ? null : locksByBlock.get(second);
        return secondLock == null || secondLock.automationAllowed();
    }

    public boolean addBlocks(ContainerLock lock, Collection<BlockKey> blocks) {
        if (blocks.stream().map(locksByBlock::get).anyMatch(existing -> existing != null && existing != lock)) {
            return false;
        }

        boolean changed = false;
        for (BlockKey block : blocks) {
            if (lock.addBlock(block)) {
                locksByBlock.put(block, lock);
                changed = true;
            }
        }
        if (changed) {
            markDirty();
        }
        return true;
    }

    public boolean trust(ContainerLock lock, UUID playerId) {
        boolean changed = lock.trust(playerId);
        if (changed) {
            markDirty();
        }
        return changed;
    }

    public boolean untrust(ContainerLock lock, UUID playerId) {
        boolean changed = lock.untrust(playerId);
        if (changed) {
            markDirty();
        }
        return changed;
    }

    public boolean rename(ContainerLock lock, String name) {
        boolean changed = lock.rename(name);
        if (changed) {
            markDirty();
        }
        return changed;
    }

    public boolean cycleAccessMode(ContainerLock lock) {
        boolean changed = lock.accessMode(lock.accessMode().next());
        if (changed) {
            markDirty();
        }
        return changed;
    }

    public boolean transfer(ContainerLock lock, UUID newOwnerId) {
        boolean changed = lock.transfer(newOwnerId);
        if (changed) {
            markDirty();
        }
        return changed;
    }

    public boolean toggleAutomation(ContainerLock lock) {
        boolean changed = lock.automationAllowed(!lock.automationAllowed());
        if (changed) {
            markDirty();
        }
        return changed;
    }



    public void recordAccess(ContainerLock lock, UUID playerId, boolean allowed, Instant when) {
        java.util.ArrayDeque<AccessAttempt> attempts = accessHistory.computeIfAbsent(
                lock.id(),
                ignored -> new java.util.ArrayDeque<>()
        );
        attempts.addFirst(new AccessAttempt(playerId, allowed, when));
        while (attempts.size() > 5) {
            attempts.removeLast();
        }
    }

    public List<AccessAttempt> recentAccess(ContainerLock lock) {
        java.util.ArrayDeque<AccessAttempt> attempts = accessHistory.get(lock.id());
        return attempts == null ? List.of() : List.copyOf(attempts);
    }

    public void remove(ContainerLock lock) {
        if (locksById.remove(lock.id(), lock)) {
            lock.blocks().forEach(block -> locksByBlock.remove(block, lock));
            accessHistory.remove(lock.id());
            markDirty();
        }
    }

    public boolean isLocked(BlockKey block) {
        return locksByBlock.containsKey(block);
    }

    public boolean contains(ContainerLock lock) {
        return locksById.get(lock.id()) == lock;
    }

    public int lockCount() {
        return locksById.size();
    }

    public long lockCount(UUID ownerId) {
        return locksById.values().stream().filter(lock -> lock.ownerId().equals(ownerId)).count();
    }

    public List<ContainerLock> locksForOwner(UUID ownerId) {
        return locksById.values().stream()
                .filter(lock -> lock.ownerId().equals(ownerId))
                .sorted((left, right) -> {
                    int name = left.name().compareToIgnoreCase(right.name());
                    return name != 0 ? name : left.id().compareTo(right.id());
                })
                .toList();
    }

    public List<ContainerLock> allLocks() {
        return locksById.values().stream()
                .sorted((left, right) -> left.id().compareTo(right.id()))
                .toList();
    }

    @Override
    public void close() {
        if (closing) {
            return;
        }

        markDirty();
        closing = true;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                logger.warning("Timed out while waiting for container locks to finish saving.");
                writer.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
            logger.warning("Interrupted while waiting for container locks to finish saving.");
        }
    }

    private void load() {
        for (ContainerLockSnapshot snapshot : store.loadLocks()) {
            if (snapshot.blocks().stream().anyMatch(locksByBlock::containsKey)) {
                logger.warning("Skipped overlapping container lock " + snapshot.id());
                continue;
            }

            ContainerLock lock = new ContainerLock(
                    snapshot.id(),
                    snapshot.ownerId(),
                    snapshot.blocks(),
                    snapshot.trustedPlayers(),
                    snapshot.name(),
                    snapshot.accessMode(),
                    snapshot.automationAllowed()
            );
            locksById.put(lock.id(), lock);
            lock.blocks().forEach(block -> locksByBlock.put(block, lock));
        }
    }

    private void markDirty() {
        if (closing) {
            throw new IllegalStateException("Container lock service is closing");
        }

        List<ContainerLockSnapshot> current = snapshot();
        List<ContainerLockSnapshot> previous = latestRequested.getAndSet(current);
        if (current.equals(previous)) {
            return;
        }

        pending.set(current);
        monitor.queued("container-locks", 1);
        if (scheduled.compareAndSet(false, true)) {
            writer.execute(this::drain);
        }
    }

    private void drain() {
        int failures = 0;
        while (true) {
            List<ContainerLockSnapshot> locks;
            while ((locks = pending.getAndSet(null)) != null) {
                monitor.started("container-locks", pending.get() == null ? 0 : 1);
                try {
                    store.saveLocks(locks);
                    failures = 0;
                    monitor.succeeded("container-locks", pending.get() == null ? 0 : 1);
                } catch (IOException exception) {
                    logger.log(Level.SEVERE, "Could not save container locks", exception);
                    if (latestRequested.get() == locks) {
                        pending.compareAndSet(null, locks);
                    }
                    monitor.failed(
                            "container-locks",
                            pending.get() == null ? 0 : 1,
                            exception
                    );
                    if (!pauseBeforeRetry(++failures)) {
                        return;
                    }
                }
            }

            scheduled.set(false);
            monitor.queued("container-locks", pending.get() == null ? 0 : 1);
            if (pending.get() == null || !scheduled.compareAndSet(false, true)) {
                return;
            }
        }
    }

    private boolean pauseBeforeRetry(int failures) {
        long delay = Math.min(
                MAX_RETRY_MILLIS,
                INITIAL_RETRY_MILLIS << Math.min(4, failures - 1)
        );
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private List<ContainerLockSnapshot> snapshot() {
        return locksById.values().stream()
                .map(ContainerLock::snapshot)
                .sorted((left, right) -> left.id().compareTo(right.id()))
                .toList();
    }

    public record AccessAttempt(UUID playerId, boolean allowed, Instant when) {
    }
}

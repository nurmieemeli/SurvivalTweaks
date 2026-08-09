package gg.nurmi.survivaltweaks.service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class PersistenceCoordinator {

    private final ProfileRepository profiles;
    private final ContainerLockService containerLocks;
    private final DeathRecoveryService deathRecovery;
    private final NewPlayerSpawnService newPlayerSpawns;

    public PersistenceCoordinator(
            ProfileRepository profiles,
            ContainerLockService containerLocks,
            DeathRecoveryService deathRecovery,
            NewPlayerSpawnService newPlayerSpawns
    ) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.containerLocks = Objects.requireNonNull(containerLocks, "containerLocks");
        this.deathRecovery = Objects.requireNonNull(deathRecovery, "deathRecovery");
        this.newPlayerSpawns = Objects.requireNonNull(newPlayerSpawns, "newPlayerSpawns");
    }

    public void prepareMaintenance(Instant now, int mailPurgeInactiveDays) {
        Objects.requireNonNull(now, "now");
        profiles.prepareMaintenance(now, mailPurgeInactiveDays);
        deathRecovery.prepareMaintenance(now);
    }

    public void flush(Duration timeout) throws IOException {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        require("profiles", profiles.flush(remaining(deadline)));
        require("container locks", containerLocks.flush(remaining(deadline)));
        require("death markers", deathRecovery.flush(remaining(deadline)));
        require("new-player spawn state", newPlayerSpawns.flush(remaining(deadline)));
    }

    private Duration remaining(long deadline) throws IOException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new IOException("Timed out while draining persistence queues");
        }
        return Duration.ofNanos(nanos);
    }

    private void require(String owner, boolean flushed) throws IOException {
        if (!flushed) {
            throw new IOException("Could not flush " + owner + " before the persistence deadline");
        }
    }
}

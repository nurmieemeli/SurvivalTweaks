package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.object.ProfileSnapshot;
import gg.nurmi.survivaltweaks.storage.ProfileDataStore;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ProfileRepository implements AutoCloseable {

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final ProfileDataStore store;
    private final Logger logger;
    private final ConcurrentMap<UUID, Profile> profiles = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ProfileSnapshot> latestRequested = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ProfileSnapshot> pending = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, EvictionCandidate> evictionCandidates = new ConcurrentHashMap<>();
    private final Set<UUID> scheduled = ConcurrentHashMap.newKeySet();
    private final ExecutorService writer;
    private boolean closing;

    public ProfileRepository(ProfileDataStore store, Logger logger) {
        this.store = Objects.requireNonNull(store, "store");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.writer = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "SurvivalTweaks-profile-writer");
            thread.setDaemon(false);
            return thread;
        });
    }

    public Profile load(UUID uniqueId) {
        Profile cached = profiles.get(uniqueId);
        if (cached != null) {
            evictionCandidates.remove(uniqueId);
            return cached;
        }

        Profile loaded = store.load(uniqueId);
        Profile existing = profiles.putIfAbsent(uniqueId, loaded);
        if (existing != null) {
            evictionCandidates.remove(uniqueId);
            return existing;
        }

        ProfileSnapshot initial = loaded.snapshot();
        if (loaded.migrationRequired()) {
            enqueueIfChanged(initial);
        } else {
            latestRequested.put(uniqueId, initial);
        }
        return loaded;
    }

    public void save(Profile profile) {
        profiles.putIfAbsent(profile.uniqueId(), profile);
        evictionCandidates.remove(profile.uniqueId());
        enqueueIfChanged(profile.snapshot());
    }

    public void saveAll() {
        profiles.values().stream()
                .map(Profile::snapshot)
                .forEach(this::enqueueIfChanged);
    }

    public void evictOffline(Collection<UUID> onlinePlayers) {
        Set<UUID> online = Set.copyOf(onlinePlayers);
        profiles.keySet().stream()
                .filter(uniqueId -> !online.contains(uniqueId))
                .toList()
                .forEach(this::playerDisconnected);
    }

    public void playerDisconnected(UUID uniqueId) {
        Profile profile = profiles.get(uniqueId);
        if (profile != null) {
            ProfileSnapshot snapshot = profile.snapshot();
            EvictionCandidate candidate = new EvictionCandidate(profile, snapshot);
            evictionCandidates.put(uniqueId, candidate);
            boolean queued = enqueueIfChanged(snapshot);
            if (!queued && !scheduled.contains(uniqueId) && !pending.containsKey(uniqueId)) {
                evict(uniqueId, candidate);
            }
        }
    }

    public boolean flush(Duration timeout) {
        if (closing) {
            return false;
        }
        Future<?> barrier = writer.submit(() -> {
        });
        try {
            barrier.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception exception) {
            barrier.cancel(true);
            logger.log(Level.WARNING, "Could not flush profile data before the deadline", exception);
            return false;
        }
    }

    @Override
    public void close() {
        if (closing) {
            return;
        }

        profiles.values().stream().map(Profile::snapshot).forEach(this::enqueueIfChanged);
        closing = true;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                logger.warning("Timed out while waiting for profile data to finish saving.");
                writer.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
            logger.warning("Interrupted while waiting for profile data to finish saving.");
        }

        profiles.clear();
        latestRequested.clear();
        pending.clear();
        evictionCandidates.clear();
        scheduled.clear();
    }

    int cachedProfileCount() {
        return profiles.size();
    }

    private boolean enqueueIfChanged(ProfileSnapshot snapshot) {
        if (closing) {
            throw new IllegalStateException("Profile repository is closing");
        }

        ProfileSnapshot previous = latestRequested.put(snapshot.uniqueId(), snapshot);
        if (snapshot.equals(previous)) {
            return false;
        }

        pending.put(snapshot.uniqueId(), snapshot);
        if (scheduled.add(snapshot.uniqueId())) {
            writer.execute(() -> drain(snapshot.uniqueId()));
        }
        return true;
    }

    private void drain(UUID uniqueId) {
        boolean writeFailed = false;
        while (true) {
            ProfileSnapshot snapshot;
            while ((snapshot = pending.remove(uniqueId)) != null) {
                try {
                    store.save(snapshot);
                } catch (IOException exception) {
                    writeFailed = true;
                    latestRequested.remove(uniqueId, snapshot);
                    logger.log(Level.SEVERE, "Could not save profile " + uniqueId, exception);
                }
            }

            scheduled.remove(uniqueId);
            if (pending.containsKey(uniqueId)) {
                if (scheduled.add(uniqueId)) {
                    continue;
                }
                return;
            }
            EvictionCandidate candidate = evictionCandidates.get(uniqueId);
            if (!writeFailed && candidate != null) {
                evict(uniqueId, candidate);
            } else if (writeFailed) {
                evictionCandidates.remove(uniqueId);
            }
            return;
        }
    }

    private void evict(UUID uniqueId, EvictionCandidate candidate) {
        if (evictionCandidates.remove(uniqueId, candidate)) {
            profiles.remove(uniqueId, candidate.profile());
            latestRequested.remove(uniqueId, candidate.snapshot());
        }
    }

    private record EvictionCandidate(Profile profile, ProfileSnapshot snapshot) {
    }
}

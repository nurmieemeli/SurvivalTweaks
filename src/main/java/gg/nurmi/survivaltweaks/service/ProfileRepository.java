package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.LanguagePreference;
import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.object.ProfileSnapshot;
import gg.nurmi.survivaltweaks.storage.ProfileDataStore;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ProfileRepository implements AutoCloseable {

    public static final String PENDING_LANGUAGES_FILENAME = "pending-languages.properties";

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final long INITIAL_RETRY_MILLIS = 100;
    private static final long MAX_RETRY_MILLIS = 2_000;

    private final ProfileDataStore store;
    private final Logger logger;
    private final PersistenceMonitor monitor;
    private final Path pendingLanguagesFile;
    private final ConcurrentMap<UUID, LanguagePreference> languageRecovery = new ConcurrentHashMap<>();
    private final Set<UUID> cleanupScheduled = new HashSet<>();
    private final ConcurrentMap<UUID, Profile> profiles = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ProfileSnapshot> latestRequested = new ConcurrentHashMap<>();
    private final Object queue = new Object();
    private final Map<UUID, Versioned> pending = new HashMap<>();
    private final Set<UUID> scheduled = new HashSet<>();
    private Versioned inFlight;
    private long submitted;
    private final ConcurrentMap<UUID, EvictionCandidate> evictionCandidates = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<UUID>> globalTrusts = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor writer;
    private volatile boolean languageRecoveryReady;
    private volatile boolean closing;

    public ProfileRepository(ProfileDataStore store, Logger logger) {
        this(store, logger, new PersistenceMonitor());
    }

    public ProfileRepository(
            ProfileDataStore store,
            Logger logger,
            PersistenceMonitor monitor
    ) {
        this(store, logger, monitor, null);
    }

    public ProfileRepository(
            ProfileDataStore store,
            Logger logger,
            PersistenceMonitor monitor,
            Path pendingLanguagesFile
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.pendingLanguagesFile = pendingLanguagesFile;
        this.languageRecoveryReady = pendingLanguagesFile == null;
        try {
            globalTrusts.putAll(store.loadAllGlobalTrusts());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not load profile-wide container trusts safely",
                    exception
            );
        }
        this.writer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "SurvivalTweaks-profile-writer");
            thread.setDaemon(false);
            return thread;
        });
        writer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public void retryPendingLanguages() {
        if (!languageRecoveryReady) {
            writer.execute(() -> recoverPendingLanguages(true));
        }
    }

    boolean languageRecoveryReady() {
        return languageRecoveryReady;
    }

    boolean languageRecoveryPending(UUID playerId) {
        return languageRecovery.containsKey(playerId);
    }

    private void recoverPendingLanguages(boolean logFailure) {
        if (closing || languageRecoveryReady) {
            return;
        }
        try {
            Map<UUID, LanguagePreference> languages = readPendingLanguages();
            synchronized (languageRecovery) {
                if (closing) {
                    return;
                }
                languageRecovery.clear();
                languageRecovery.putAll(languages);
            }
            languages.forEach(this::stageRecoveredLanguage);
            languageRecoveryReady = true;
        } catch (IOException | RuntimeException exception) {
            synchronized (languageRecovery) {
                if (closing) {
                    return;
                }
                if (logFailure) {
                    logger.log(Level.WARNING, "Could not load pending language changes. Retrying.", exception);
                }
                writer.schedule(() -> recoverPendingLanguages(false), MAX_RETRY_MILLIS, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void stageRecoveredLanguage(UUID playerId, LanguagePreference language) {
        while (!closing) {
            Profile profile = load(playerId);
            synchronized (profile) {
                if (profiles.get(playerId) != profile) {
                    continue;
                }
                if (profile.stageLanguage(language).isPresent()) {
                    evictionCandidates.remove(playerId);
                    enqueue(profile, profile.snapshot());
                }
                return;
            }
        }
    }

    private Profile cachedProfile(UUID uniqueId) {
        Profile cached;
        while ((cached = profiles.get(uniqueId)) != null) {
            synchronized (cached) {
                if (profiles.get(uniqueId) == cached) {
                    evictionCandidates.remove(uniqueId);
                    return cached;
                }
            }
        }

        return null;
    }

    public Profile load(UUID uniqueId) {
        Profile cached = cachedProfile(uniqueId);
        if (cached != null) {
            return cached;
        }
        Profile loaded = store.load(uniqueId);
        synchronized (loaded) {
            Profile existing = profiles.putIfAbsent(uniqueId, loaded);
            if (existing == null) {
                ProfileSnapshot initial = loaded.snapshot();
                globalTrusts.put(uniqueId, loaded.trustedPlayers());
                if (loaded.migrationRequired()) {
                    enqueueIfChanged(loaded, initial);
                } else {
                    latestRequested.put(uniqueId, initial);
                }
                return loaded;
            }
        }
        return load(uniqueId);
    }

    public CompletableFuture<Profile> loadAsync(UUID uniqueId) {
        Profile cached = cachedProfile(uniqueId);
        return cached == null ? CompletableFuture.supplyAsync(() -> load(uniqueId))
                : CompletableFuture.completedFuture(cached);
    }

    <T> T withCurrent(Profile loaded, Function<Profile, T> action) {
        UUID playerId = loaded.uniqueId();
        Profile current = loaded;
        while (true) {
            synchronized (current) {
                if (profiles.get(playerId) == current) {
                    evictionCandidates.remove(playerId);
                    return action.apply(current);
                }
            }
            current = load(playerId);
        }
    }

    public void save(Profile profile) {
        synchronized (profile) {
            if (profiles.get(profile.uniqueId()) != profile) {
                throw new IllegalStateException("Cannot save a stale profile: " + profile.uniqueId());
            }
            evictionCandidates.remove(profile.uniqueId());
            globalTrusts.put(profile.uniqueId(), profile.trustedPlayers());
            enqueueIfChanged(profile, profile.snapshot());
        }
    }

    public void saveAll() {
        profiles.values().forEach(profile -> {
            synchronized (profile) {
                if (profiles.get(profile.uniqueId()) == profile) {
                    enqueueIfChanged(profile, profile.snapshot());
                }
            }
        });
    }

    public void prepareMaintenance(Instant now, int mailPurgeInactiveDays) {
        Objects.requireNonNull(now, "now");
        if (mailPurgeInactiveDays <= 0) {
            return;
        }
        Instant cutoff = now.minus(Duration.ofDays(mailPurgeInactiveDays));
        profiles.values().forEach(profile -> {
            synchronized (profile) {
                if (profiles.get(profile.uniqueId()) == profile && profile.removeMailBefore(cutoff) > 0) {
                    save(profile);
                }
            }
        });
    }

    public void evictOffline(Collection<UUID> onlinePlayers) {
        Set<UUID> online = Set.copyOf(onlinePlayers);
        profiles.keySet().stream()
                .filter(uniqueId -> !online.contains(uniqueId))
                .toList()
                .forEach(this::playerDisconnected);
    }

    public boolean isTrusted(UUID ownerId, UUID playerId) {
        Set<UUID> trusts = globalTrusts.get(ownerId);
        return trusts != null && trusts.contains(playerId);
    }

    public void playerDisconnected(UUID uniqueId) {
        Profile profile = profiles.get(uniqueId);
        if (profile != null) {
            synchronized (profile) {
                if (profiles.get(uniqueId) != profile) {
                    return;
                }
                ProfileSnapshot snapshot = profile.snapshot();
                EvictionCandidate candidate = new EvictionCandidate(profile, snapshot);
                evictionCandidates.put(uniqueId, candidate);
                boolean queued = enqueueIfChanged(profile, snapshot);
                if (!queued && !isScheduled(uniqueId)) {
                    evict(uniqueId, candidate);
                }
            }
        }
    }

    /**
     * Waits for the snapshots already requested, including any replacements for them.
     */
    public boolean flush(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (queue) {
            long target = submitted;
            while (!closing && ((inFlight != null && inFlight.sequence() <= target)
                    || pending.values().stream().anyMatch(entry -> entry.sequence() <= target))) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    logger.warning("Could not flush profile data before the deadline");
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(queue, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !closing;
        }
    }

    @Override
    public synchronized void close() {
        if (closing) {
            return;
        }

        long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
        saveAll();
        flush(Duration.ofNanos(deadline - System.nanoTime()));
        synchronized (languageRecovery) {
            synchronized (queue) {
                closing = true;
                writer.shutdown();
                queue.notifyAll();
            }
        }
        try {
            if (!writer.awaitTermination(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                logger.warning("Timed out while waiting for profile data to finish saving.");
                writer.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
            logger.warning("Interrupted while waiting for profile data to finish saving.");
        }

        boolean interrupted = Thread.interrupted();
        try {
            savePendingLanguages();
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Could not preserve uncommitted language changes for restart", exception);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        profiles.clear();
        latestRequested.clear();
        synchronized (queue) {
            pending.clear();
            scheduled.clear();
        }
        evictionCandidates.clear();
        globalTrusts.clear();
    }

    private Map<UUID, LanguagePreference> readPendingLanguages() throws IOException {
        byte[] contents;
        try {
            contents = Files.readAllBytes(pendingLanguagesFile);
        } catch (NoSuchFileException exception) {
            return Map.of();
        }

        Map<UUID, LanguagePreference> languages = new HashMap<>();
        Properties property = new Properties();
        boolean malformed = false;
        // The journal writer emits one complete property per line.
        for (String line : new String(contents, StandardCharsets.UTF_8).lines().toList()) {
            property.clear();
            try {
                property.load(new StringReader(line));
                for (String playerId : property.stringPropertyNames()) {
                    UUID id = UUID.fromString(playerId);
                    if (!id.toString().equalsIgnoreCase(playerId)) {
                        throw new IllegalArgumentException("Invalid player UUID");
                    }
                    languages.put(id, LanguagePreference.valueOf(property.getProperty(playerId)));
                }
            } catch (IllegalArgumentException exception) {
                malformed = true;
            }
        }
        if (malformed) {
            Path original = Files.createTempFile(pendingLanguagesFile.toAbsolutePath().getParent(),
                    "pending-languages-invalid-", ".properties");
            Files.write(original, contents);
            logger.warning("Preserved malformed language recovery data at " + original
                    + "; recovering valid entries.");
            writePendingLanguages(languages);
        }
        return languages;
    }

    private void savePendingLanguages() throws IOException {
        if (pendingLanguagesFile == null) {
            return;
        }
        Map<UUID, LanguagePreference> saved = new HashMap<>();
        profiles.forEach((playerId, profile) -> profile.pendingLanguage().ifPresent(
                language -> saved.put(playerId, language)
        ));
        if (saved.isEmpty()) {
            return;
        }
        synchronized (languageRecovery) {
            Map<UUID, LanguagePreference> combined = new HashMap<>(languageRecovery);
            combined.putAll(saved);
            writePendingLanguages(combined);
            languageRecovery.putAll(saved);
        }
        logger.info("Preserved " + saved.size() + " uncommitted language change(s) for restart.");
    }

    private void finishLanguageRecovery(UUID playerId, LanguagePreference language) throws IOException {
        synchronized (languageRecovery) {
            if (languageRecovery.get(playerId) != language) {
                return;
            }
            // Retire the replay entry before another choice can replace the committed language.
            Map<UUID, LanguagePreference> remaining = new HashMap<>(languageRecovery);
            remaining.remove(playerId);
            writePendingLanguages(remaining);
            languageRecovery.remove(playerId);
        }
        logger.info("Recovered pending language change for " + playerId + ".");
    }

    private void retireRecoveredLanguage(UUID playerId, LanguagePreference language, boolean logFailure) {
        try {
            finishLanguageRecovery(playerId, language);
            cleanupScheduled.remove(playerId);
        } catch (IOException exception) {
            if (logFailure) {
                logger.log(Level.WARNING, "Could not remove the committed language recovery entry for "
                        + playerId + ". Retrying cleanup.", exception);
            }
            synchronized (queue) {
                if (!closing) {
                    writer.schedule(() -> retireRecoveredLanguage(playerId, language, false),
                            MAX_RETRY_MILLIS, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    private void writePendingLanguages(Map<UUID, LanguagePreference> languages) throws IOException {
        if (languages.isEmpty()) {
            Files.deleteIfExists(pendingLanguagesFile);
            return;
        }
        Properties saved = new Properties();
        languages.forEach((playerId, language) -> saved.setProperty(playerId.toString(), language.name()));
        Path temporary = Files.createTempFile(pendingLanguagesFile.toAbsolutePath().getParent(), ".languages-", ".tmp");
        try {
            try (var output = Files.newBufferedWriter(temporary)) {
                saved.store(output, "Uncommitted player language changes to replay on startup");
            }
            try {
                Files.move(temporary, pendingLanguagesFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, pendingLanguagesFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    int cachedProfileCount() {
        return profiles.size();
    }

    // Capture and enqueue under the same profile lock to preserve language changes across saves.
    private boolean enqueueIfChanged(Profile profile, ProfileSnapshot snapshot) {
        if (closing) {
            throw new IllegalStateException("Profile repository is closing");
        }

        ProfileSnapshot previous = latestRequested.get(snapshot.uniqueId());
        if (snapshot.equals(previous)) {
            return false;
        }
        enqueue(profile, snapshot);
        return true;
    }

    private void enqueue(Profile profile, ProfileSnapshot snapshot) {
        UUID uniqueId = snapshot.uniqueId();
        synchronized (queue) {
            if (closing) {
                throw new IllegalStateException("Profile repository is closing");
            }
            latestRequested.put(uniqueId, snapshot);
            Versioned previous = pending.get(uniqueId);
            long sequence = ++submitted;
            pending.put(uniqueId, new Versioned(previous == null ? sequence : previous.sequence(), profile, snapshot));
            monitor.queued("profiles", pending.size());
            if (scheduled.add(uniqueId)) {
                writer.execute(() -> drain(uniqueId, 0));
            }
        }
    }

    private boolean isScheduled(UUID uniqueId) {
        synchronized (queue) {
            return scheduled.contains(uniqueId);
        }
    }

    private void drain(UUID uniqueId, int failures) {
        Versioned entry;
        synchronized (queue) {
            entry = pending.remove(uniqueId);
            inFlight = entry;
            monitor.started("profiles", pending.size());
        }
        Exception failure = null;
        try {
            store.save(entry.snapshot());
        } catch (IOException | RuntimeException exception) {
            failure = exception;
            logger.log(Level.SEVERE, "Could not save profile " + uniqueId, exception);
        }
        if (failure == null) {
            LanguagePreference language = entry.snapshot().preferences().language();
            entry.profile().confirmSavedLanguage(language);
            if (languageRecovery.get(uniqueId) == language && cleanupScheduled.add(uniqueId)) {
                retireRecoveredLanguage(uniqueId, language, true);
            }
        }
        synchronized (queue) {
            if (failure == null) {
                monitor.succeeded("profiles", pending.size());
            } else {
                pending.merge(uniqueId, entry, (newer, failed) ->
                        new Versioned(failed.sequence(), newer.profile(), newer.snapshot()));
                monitor.failed("profiles", pending.size(), failure);
            }
            if (!closing && pending.containsKey(uniqueId)) {
                int nextFailures = failure == null ? 0 : Math.min(5, failures + 1);
                long delay = nextFailures == 0 ? 0 : INITIAL_RETRY_MILLIS << (nextFailures - 1);
                writer.schedule(() -> drain(uniqueId, nextFailures), delay, TimeUnit.MILLISECONDS);
                inFlight = null;
                queue.notifyAll();
                return;
            }
            scheduled.remove(uniqueId);
        }
        EvictionCandidate candidate = evictionCandidates.get(uniqueId);
        if (candidate != null && !closing) {
            evict(uniqueId, candidate);
        }
        synchronized (queue) {
            inFlight = null;
            queue.notifyAll();
        }
    }

    private void evict(UUID uniqueId, EvictionCandidate candidate) {
        synchronized (candidate.profile()) {
            if (!isScheduled(uniqueId) && evictionCandidates.remove(uniqueId, candidate)) {
                profiles.remove(uniqueId, candidate.profile());
                latestRequested.remove(uniqueId, candidate.snapshot());
            }
        }
    }

    private record EvictionCandidate(Profile profile, ProfileSnapshot snapshot) {
    }

    /** A queued profile snapshot together with the enqueue it came from. */
    private record Versioned(long sequence, Profile profile, ProfileSnapshot snapshot) {
    }
}

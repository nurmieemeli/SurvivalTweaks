package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Stream;

public final class DiagnosticService implements AutoCloseable {

    private static final int PROFILE_SCHEMA = 6;
    private static final int LOCK_SCHEMA = 3;
    private static final int NEW_PLAYER_SPAWN_SCHEMA = 2;

    private final JavaPlugin plugin;
    private final Path dataFolder;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final NamespacedKey compassOwnerKey;
    private final NamespacedKey compassMarkerKey;
    private final ExecutorService executor;
    private volatile boolean closed;
    private volatile long generation;

    public DiagnosticService(JavaPlugin plugin, Clock clock) {
        this(
                plugin,
                clock,
                Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().name("SurvivalTweaks diagnostics").daemon(true).factory()
                )
        );
    }

    DiagnosticService(JavaPlugin plugin, Clock clock, ExecutorService executor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dataFolder = plugin.getDataFolder().toPath();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.compassOwnerKey = new NamespacedKey(plugin, "death-compass-owner");
        this.compassMarkerKey = new NamespacedKey(plugin, "death-compass-marker");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    DiagnosticService(Path dataFolder, Clock clock) {
        this.plugin = null;
        this.dataFolder = dataFolder;
        this.clock = clock;
        this.compassOwnerKey = null;
        this.compassMarkerKey = null;
        this.executor = null;
    }

    public boolean run(Consumer<Report> completion) {
        if (closed || !running.compareAndSet(false, true)) {
            return false;
        }
        Snapshot snapshot;
        try {
            snapshot = snapshot();
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
        long runGeneration = ++generation;
        try {
            executor.execute(() -> inspectAsync(snapshot, completion, runGeneration));
        } catch (RejectedExecutionException exception) {
            running.set(false);
            return false;
        }
        return true;
    }

    Report inspect(Snapshot snapshot) {
        ArrayList<Issue> issues = new ArrayList<>();
        ensureActive();
        inspectDataDirectory(issues);
        ensureActive();
        inspectConfiguration(issues);
        ensureActive();
        inspectMessages(issues);
        ensureActive();
        int profiles = inspectProfiles(snapshot, issues);
        ensureActive();
        int locks = inspectLocks(snapshot, issues);
        ensureActive();
        DeathSummary deaths = inspectDeaths(snapshot, issues);
        ensureActive();
        inspectNewPlayerSpawns(snapshot, issues);
        ensureActive();
        int backups = inspectBackups(issues);
        ensureActive();
        inspectCompasses(snapshot, deaths.activeMarkers(), issues);
        long errors = issues.stream().filter(issue -> issue.severity() == Severity.ERROR).count();
        long warnings = issues.size() - errors;
        return new Report(
                Math.toIntExact(errors),
                Math.toIntExact(warnings),
                profiles,
                locks,
                deaths.total(),
                backups,
                List.copyOf(issues)
        );
    }

    private void inspectAsync(
            Snapshot snapshot,
            Consumer<Report> completion,
            long runGeneration
    ) {
        Report report;
        try {
            report = inspect(snapshot);
        } catch (CancellationException exception) {
            finishCancelled(runGeneration);
            return;
        } catch (RuntimeException exception) {
            report = new Report(
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    List.of(new Issue(Severity.ERROR, "Diagnostic scan failed: " + reason(exception)))
            );
        }
        if (closed || runGeneration != generation) {
            finishCancelled(runGeneration);
            return;
        }

        Report completed = report;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (closed || runGeneration != generation) {
                    return;
                }
                running.set(false);
                completion.accept(completed);
            });
        } catch (RuntimeException exception) {
            running.set(false);
            if (!closed) {
                plugin.getLogger().log(Level.WARNING, "Could not deliver diagnostic results", exception);
            }
        }
    }

    private void finishCancelled(long runGeneration) {
        if (runGeneration == generation) {
            running.set(false);
        }
    }

    private void ensureActive() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Diagnostic scan interrupted");
        }
    }

    @Override
    public void close() {
        if (executor == null) {
            return;
        }
        closed = true;
        generation++;
        running.set(false);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Diagnostic worker did not stop within five seconds.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Interrupted while waiting for the diagnostic worker to stop.");
        }
    }

    private Snapshot snapshot() {
        Set<UUID> worldIds = plugin.getServer().getWorlds().stream()
                .map(org.bukkit.World::getUID)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> worldNames = plugin.getServer().getWorlds().stream()
                .map(world -> world.getName().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        ArrayList<CompassReference> compasses = new ArrayList<>();
        plugin.getServer().getOnlinePlayers().forEach(player -> {
            for (ItemStack item : player.getInventory().getContents()) {
                if (item == null) {
                    continue;
                }
                String owner = item.getItemMeta().getPersistentDataContainer().get(
                        compassOwnerKey,
                        PersistentDataType.STRING
                );
                String marker = item.getItemMeta().getPersistentDataContainer().get(
                        compassMarkerKey,
                        PersistentDataType.STRING
                );
                if (owner != null || marker != null) {
                    compasses.add(new CompassReference(player.getUniqueId(), owner, marker));
                }
            }
        });
        return new Snapshot(worldIds, worldNames, List.copyOf(compasses), clock.instant());
    }

    private void inspectDataDirectory(List<Issue> issues) {
        if (!Files.isDirectory(dataFolder)) {
            issues.add(new Issue(Severity.ERROR, "Plugin data directory does not exist."));
        } else if (!Files.isWritable(dataFolder)) {
            issues.add(new Issue(Severity.ERROR, "Plugin data directory is not writable."));
        }
    }

    private void inspectConfiguration(List<Issue> issues) {
        Path config = dataFolder.resolve("config.yml");
        if (Files.notExists(config)) {
            issues.add(new Issue(Severity.ERROR, "config.yml is missing."));
            return;
        }
        try {
            PluginSettings.validate(load(config));
        } catch (Exception exception) {
            issues.add(new Issue(Severity.ERROR, "config.yml is invalid: " + reason(exception)));
        }
    }

    private void inspectMessages(List<Issue> issues) {
        Path englishFile = dataFolder.resolve("messages_en.yml");
        Path finnishFile = dataFolder.resolve("messages_fi.yml");
        if (Files.notExists(englishFile) || Files.notExists(finnishFile)) {
            issues.add(new Issue(Severity.ERROR, "One or both language catalogs are missing."));
            return;
        }
        try {
            YamlConfiguration english = load(englishFile);
            YamlConfiguration finnish = load(finnishFile);
            Set<String> englishKeys = stringKeys(english);
            Set<String> finnishKeys = stringKeys(finnish);
            if (!englishKeys.equals(finnishKeys)) {
                issues.add(new Issue(
                        Severity.ERROR,
                        "Finnish and English language catalogs have different message keys."
                ));
            }
            MiniMessage miniMessage = MiniMessage.miniMessage();
            for (String key : englishKeys) {
                miniMessage.deserialize(english.getString(key, ""));
                miniMessage.deserialize(finnish.getString(key, ""));
            }
        } catch (Exception exception) {
            issues.add(new Issue(Severity.ERROR, "Language catalog validation failed: " + reason(exception)));
        }
    }

    private int inspectProfiles(Snapshot snapshot, List<Issue> issues) {
        Path profiles = dataFolder.resolve("userdata");
        if (Files.notExists(profiles)) {
            return 0;
        }
        int count = 0;
        try (Stream<Path> files = Files.list(profiles)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .toList()) {
                ensureActive();
                count++;
                inspectProfile(file, snapshot, issues);
            }
        } catch (Exception exception) {
            issues.add(new Issue(Severity.ERROR, "Could not scan userdata: " + reason(exception)));
        }
        return count;
    }

    private void inspectProfile(Path file, Snapshot snapshot, List<Issue> issues) {
        String filename = file.getFileName().toString();
        try {
            UUID.fromString(filename.substring(0, filename.length() - 4));
        } catch (RuntimeException exception) {
            issues.add(new Issue(Severity.ERROR, "Profile filename is not a UUID: " + filename));
        }
        try {
            YamlConfiguration yaml = load(file);
            int schema = yaml.getInt("schema-version", 0);
            inspectSchema("Profile " + filename, schema, PROFILE_SCHEMA, issues);
            if (!yaml.isList("homes")) {
                issues.add(new Issue(Severity.WARNING, "Profile " + filename + " still uses legacy home storage."));
                return;
            }
            Set<String> names = new HashSet<>();
            for (Map<?, ?> home : yaml.getMapList("homes")) {
                String name = text(home.get("name"));
                if (name == null) {
                    issues.add(new Issue(Severity.ERROR, "Profile " + filename + " has a home without a name."));
                    continue;
                }
                if (!names.add(name.toLowerCase(Locale.ROOT))) {
                    issues.add(new Issue(
                            Severity.ERROR,
                            "Profile " + filename + " has duplicate home name '" + name + "'."
                    ));
                }
                UUID worldId = uuid(home.get("world-uuid"));
                String worldName = text(home.get("world"));
                if (home.get("world-uuid") != null && worldId == null) {
                    issues.add(new Issue(
                            Severity.ERROR,
                            "Profile " + filename + " has an invalid world UUID for home '" + name + "'."
                    ));
                } else if (!loaded(worldId, worldName, snapshot)) {
                    issues.add(new Issue(
                            Severity.WARNING,
                            "Profile " + filename + " references unloaded world '" + worldName + "'."
                    ));
                }
            }
        } catch (Exception exception) {
            issues.add(new Issue(Severity.ERROR, "Could not read profile " + filename + ": " + reason(exception)));
        }
    }

    private int inspectLocks(Snapshot snapshot, List<Issue> issues) {
        Path file = dataFolder.resolve("locked-containers.yml");
        if (Files.notExists(file)) {
            return 0;
        }
        int count = 0;
        Map<String, String> claimedBlocks = new HashMap<>();
        try {
            YamlConfiguration yaml = load(file);
            inspectSchema("Locked containers", yaml.getInt("schema-version", 0), LOCK_SCHEMA, issues);
            for (Map<?, ?> lock : yaml.getMapList("locks")) {
                ensureActive();
                count++;
                String id = text(lock.get("id"));
                if (uuid(lock.get("id")) == null || uuid(lock.get("owner")) == null) {
                    issues.add(new Issue(Severity.ERROR, "Lock " + fallback(id) + " has an invalid ID or owner UUID."));
                }
                Object blocksValue = lock.get("blocks");
                if (!(blocksValue instanceof List<?> blocks) || blocks.isEmpty()) {
                    issues.add(new Issue(Severity.ERROR, "Lock " + fallback(id) + " has no block coordinates."));
                    continue;
                }
                for (Object entry : blocks) {
                    if (!(entry instanceof Map<?, ?> block)) {
                        issues.add(new Issue(Severity.ERROR, "Lock " + fallback(id) + " has an invalid block entry."));
                        continue;
                    }
                    UUID worldId = uuid(block.get("world"));
                    if (worldId == null || !(block.get("x") instanceof Number)
                            || !(block.get("y") instanceof Number)
                            || !(block.get("z") instanceof Number)) {
                        issues.add(new Issue(
                                Severity.ERROR,
                                "Lock " + fallback(id) + " has invalid block coordinates."
                        ));
                        continue;
                    }
                    String key = worldId + ":" + block.get("x") + ":" + block.get("y") + ":" + block.get("z");
                    String previous = claimedBlocks.putIfAbsent(key, fallback(id));
                    if (previous != null) {
                        issues.add(new Issue(
                                Severity.ERROR,
                                "Locks " + previous + " and " + fallback(id) + " overlap at " + key + "."
                        ));
                    }
                    if (!snapshot.worldIds().contains(worldId)) {
                        issues.add(new Issue(
                                Severity.WARNING,
                                "Lock " + fallback(id) + " references unloaded world " + worldId + "."
                        ));
                    }
                }
            }
        } catch (Exception exception) {
            issues.add(new Issue(Severity.ERROR, "Could not read locked containers: " + reason(exception)));
        }
        return count;
    }

    private DeathSummary inspectDeaths(Snapshot snapshot, List<Issue> issues) {
        Path file = dataFolder.resolve("death-markers.yml");
        if (Files.notExists(file)) {
            return new DeathSummary(0, Map.of());
        }
        int count = 0;
        Map<UUID, Set<String>> active = new HashMap<>();
        try {
            YamlConfiguration yaml = load(file);
            for (Map<?, ?> marker : yaml.getMapList("markers")) {
                ensureActive();
                count++;
                UUID playerId = uuid(marker.get("player"));
                UUID worldId = uuid(marker.get("world-uuid"));
                String created = text(marker.get("created-at"));
                Instant expires = instant(marker.get("expires-at"));
                if (playerId == null || worldId == null || created == null || expires == null) {
                    issues.add(new Issue(Severity.ERROR, "A death marker contains invalid identifiers or timestamps."));
                    continue;
                }
                if (!snapshot.worldIds().contains(worldId)
                        && !snapshot.worldNames().contains(
                                fallback(text(marker.get("world"))).toLowerCase(Locale.ROOT)
                )) {
                    issues.add(new Issue(
                            Severity.WARNING,
                            "Death marker for " + playerId + " references an unloaded world."
                    ));
                }
                if (expires.isAfter(snapshot.now())) {
                    active.computeIfAbsent(playerId, ignored -> new HashSet<>()).add(created);
                } else {
                    issues.add(new Issue(
                            Severity.WARNING,
                            "Expired death marker for " + playerId + " is still persisted."
                    ));
                }
            }
        } catch (Exception exception) {
            issues.add(new Issue(Severity.ERROR, "Could not read death markers: " + reason(exception)));
        }
        Map<UUID, Set<String>> immutable = active.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> Set.copyOf(entry.getValue())
                )
        );
        return new DeathSummary(count, immutable);
    }

    private void inspectNewPlayerSpawns(Snapshot snapshot, List<Issue> issues) {
        Path file = dataFolder.resolve("new-player-spawns.yml");
        if (Files.notExists(file)) {
            return;
        }
        try {
            YamlConfiguration yaml = load(file);
            inspectSchema(
                    "New-player spawns",
                    yaml.getInt("schema-version", 0),
                    NEW_PLAYER_SPAWN_SCHEMA,
                    issues
            );
            if (!yaml.isList("available") || !yaml.isList("assignments")) {
                issues.add(new Issue(
                        Severity.ERROR,
                        "New-player spawn data must contain available and assignment lists."
                ));
                return;
            }
            int schema = yaml.getInt("schema-version", 0);
            if (schema >= 2 && (!yaml.isList("retired") || !yaml.isList("awaiting-replacement"))) {
                issues.add(new Issue(
                        Severity.ERROR,
                        "New-player spawn schema 2 must contain retired and awaiting-replacement lists."
                ));
            }
            Set<String> claimedLocations = new HashSet<>();
            for (Map<?, ?> location : yaml.getMapList("available")) {
                ensureActive();
                inspectSpawnLocation(
                        location,
                        "A preloaded new-player spawn",
                        snapshot,
                        claimedLocations,
                        issues,
                        true
                );
            }
            Set<UUID> players = new HashSet<>();
            for (Map<?, ?> assignment : yaml.getMapList("assignments")) {
                ensureActive();
                UUID playerId = uuid(assignment.get("player"));
                if (playerId == null) {
                    issues.add(new Issue(Severity.ERROR, "A new-player spawn assignment has an invalid player UUID."));
                } else if (!players.add(playerId)) {
                    issues.add(new Issue(
                            Severity.ERROR,
                            "Player " + playerId + " has duplicate new-player spawn assignments."
                    ));
                }
                if (!(assignment.get("completed") instanceof Boolean)) {
                    issues.add(new Issue(
                            Severity.ERROR,
                            "New-player spawn assignment for " + fallback(
                                    playerId == null ? null : playerId.toString()
                            ) + " has no completion state."
                    ));
                }
                if (!(assignment.get("location") instanceof Map<?, ?> location)) {
                    issues.add(new Issue(
                            Severity.ERROR,
                            "New-player spawn assignment for " + fallback(
                                    playerId == null ? null : playerId.toString()
                            ) + " has no location."
                    ));
                    continue;
                }
                inspectSpawnLocation(
                        location,
                        "New-player spawn assignment for " + fallback(
                                playerId == null ? null : playerId.toString()
                        ),
                        snapshot,
                        claimedLocations,
                        issues,
                        true
                );
            }
            for (Map<?, ?> location : yaml.getMapList("retired")) {
                inspectSpawnLocation(
                        location,
                        "A retired new-player spawn",
                        snapshot,
                        claimedLocations,
                        issues,
                        false
                );
            }
            Set<UUID> awaiting = new HashSet<>();
            for (String value : yaml.getStringList("awaiting-replacement")) {
                UUID playerId = uuid(value);
                if (playerId == null) {
                    issues.add(new Issue(
                            Severity.ERROR,
                            "The new-player replacement queue contains an invalid player UUID."
                    ));
                } else if (!awaiting.add(playerId)) {
                    issues.add(new Issue(
                            Severity.ERROR,
                            "Player " + playerId + " occurs twice in the new-player replacement queue."
                    ));
                } else if (players.contains(playerId)) {
                    issues.add(new Issue(
                            Severity.ERROR,
                            "Player " + playerId + " has both a spawn assignment and a replacement queue entry."
                    ));
                }
            }
        } catch (Exception exception) {
            issues.add(new Issue(
                    Severity.ERROR,
                    "Could not read new-player spawn data: " + reason(exception)
            ));
        }
    }

    private void inspectSpawnLocation(
            Map<?, ?> location,
            String source,
            Snapshot snapshot,
            Set<String> claimedLocations,
            List<Issue> issues,
            boolean requireLoadedWorld
    ) {
        UUID worldId = uuid(location.get("world-uuid"));
        String worldName = text(location.get("world"));
        if (worldId == null
                || worldName == null
                || !(location.get("x") instanceof Number x)
                || !(location.get("y") instanceof Number y)
                || !(location.get("z") instanceof Number z)
                || !(location.get("yaw") instanceof Number)) {
            issues.add(new Issue(Severity.ERROR, source + " contains invalid world or coordinates."));
            return;
        }
        String key = worldId + ":" + x.intValue() + ":" + y.intValue() + ":" + z.intValue();
        if (!claimedLocations.add(key)) {
            issues.add(new Issue(Severity.ERROR, source + " duplicates location " + key + "."));
        }
        if (requireLoadedWorld && !loaded(worldId, worldName, snapshot)) {
            issues.add(new Issue(Severity.WARNING, source + " references an unloaded world."));
        }
    }

    private int inspectBackups(List<Issue> issues) {
        Path directory = dataFolder.resolve("backups");
        if (Files.notExists(directory)) {
            issues.add(new Issue(Severity.WARNING, "No safety backups have been created yet."));
            return 0;
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> archives = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .sorted()
                    .toList();
            int count = archives.size();
            if (count == 0) {
                issues.add(new Issue(Severity.WARNING, "No safety backups have been created yet."));
            }
            for (Path archive : archives) {
                ensureActive();
                BackupService.Verification verification = BackupService.verifyArchive(archive);
                if (!verification.valid()) {
                    issues.add(new Issue(
                            Severity.ERROR,
                            "Backup " + archive.getFileName() + " is invalid: " + verification.problem()
                    ));
                }
            }
            return count;
        } catch (Exception exception) {
            issues.add(new Issue(Severity.WARNING, "Could not inspect safety backups: " + reason(exception)));
            return 0;
        }
    }

    private void inspectCompasses(
            Snapshot snapshot,
            Map<UUID, Set<String>> activeMarkers,
            List<Issue> issues
    ) {
        int stale = 0;
        for (CompassReference compass : snapshot.compasses()) {
            UUID owner = uuid(compass.owner());
            if (owner == null
                    || !owner.equals(compass.holder())
                    || compass.marker() == null
                    || !activeMarkers.getOrDefault(owner, Set.of()).contains(compass.marker())) {
                stale++;
            }
        }
        if (stale > 0) {
            issues.add(new Issue(
                    Severity.WARNING,
                    stale + " stale or mismatched recovery compass item(s) are online."
            ));
        }
    }

    private void inspectSchema(
            String source,
            int actual,
            int expected,
            List<Issue> issues
    ) {
        if (actual < expected) {
            issues.add(new Issue(
                    Severity.WARNING,
                    source + " uses schema " + actual + " and will migrate to " + expected + "."
            ));
        } else if (actual > expected) {
            issues.add(new Issue(
                    Severity.ERROR,
                    source + " uses unsupported future schema " + actual + "."
            ));
        }
    }

    private boolean loaded(UUID worldId, String worldName, Snapshot snapshot) {
        return (worldId != null && snapshot.worldIds().contains(worldId))
                || (worldName != null
                && snapshot.worldNames().contains(worldName.toLowerCase(Locale.ROOT)));
    }

    private YamlConfiguration load(Path file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        return yaml;
    }

    private Set<String> stringKeys(YamlConfiguration catalog) {
        return catalog.getKeys(true).stream()
                .filter(catalog::isString)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private UUID uuid(Object value) {
        try {
            return value == null ? null : UUID.fromString(value.toString());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Instant instant(Object value) {
        try {
            return value == null ? null : Instant.parse(value.toString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String fallback(String value) {
        return value == null ? "<unknown>" : value;
    }

    private String reason(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    public enum Severity {
        ERROR,
        WARNING
    }

    public record Issue(Severity severity, String detail) {
    }

    public record Report(
            int errors,
            int warnings,
            int profiles,
            int locks,
            int deathMarkers,
            int backups,
            List<Issue> issues
    ) {

        public boolean healthy() {
            return errors == 0 && warnings == 0;
        }
    }

    record Snapshot(
            Set<UUID> worldIds,
            Set<String> worldNames,
            List<CompassReference> compasses,
            Instant now
    ) {
    }

    record CompassReference(UUID holder, String owner, String marker) {
    }

    private record DeathSummary(int total, Map<UUID, Set<String>> activeMarkers) {
    }
}

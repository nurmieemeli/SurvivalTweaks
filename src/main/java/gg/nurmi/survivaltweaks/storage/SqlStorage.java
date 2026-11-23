package gg.nurmi.survivaltweaks.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.object.ContainerLockSnapshot;
import gg.nurmi.survivaltweaks.object.DeathMarker;
import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.HomeArrivalStyle;
import gg.nurmi.survivaltweaks.object.HomeCategory;
import gg.nurmi.survivaltweaks.object.LanguagePreference;
import gg.nurmi.survivaltweaks.object.LockAccessMode;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnAssignment;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnLocation;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnState;
import gg.nurmi.survivaltweaks.object.NotificationType;
import gg.nurmi.survivaltweaks.object.OnboardingHint;
import gg.nurmi.survivaltweaks.object.PlayerNotification;
import gg.nurmi.survivaltweaks.object.PlayerPreferences;
import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.object.ProfileSnapshot;
import org.bukkit.Material;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SqlStorage implements
        ProfileDataStore,
        ContainerLockDataStore,
        DeathMarkerDataStore,
        NewPlayerSpawnDataStore,
        AutoCloseable {

    public static final int SCHEMA_VERSION = 1;
    private static final String META_SCHEMA_VERSION = "schema-version";
    private static final String META_INSTANCE_ID = "instance-id";
    private static final long LEASE_CHECK_SECONDS = 30;

    private static final String OLD_MAIL = "st_notifications WHERE notification_type = 'MAIL' AND created_at < ?";
    private static final String EXPIRED_DEATH_MARKERS = "st_death_markers WHERE expires_at <= ?";
    private static final String ORPHANED_HOME_SHARES = "st_home_shares WHERE NOT EXISTS "
            + "(SELECT 1 FROM st_homes WHERE st_homes.player_id = st_home_shares.player_id "
            + "AND st_homes.home_key = st_home_shares.home_key)";
    private static final String ORPHANED_HOMES = "st_homes WHERE NOT EXISTS "
            + "(SELECT 1 FROM st_profiles WHERE st_profiles.player_id = st_homes.player_id)";
    private static final String ORPHANED_HINTS = "st_hints WHERE NOT EXISTS "
            + "(SELECT 1 FROM st_profiles WHERE st_profiles.player_id = st_hints.player_id)";
    private static final String ORPHANED_NOTIFICATIONS = "st_notifications WHERE NOT EXISTS "
            + "(SELECT 1 FROM st_profiles WHERE st_profiles.player_id = st_notifications.player_id)";
    private static final String ORPHANED_MAIL_BLOCKS = "st_mail_blocks WHERE NOT EXISTS "
            + "(SELECT 1 FROM st_profiles WHERE st_profiles.player_id = st_mail_blocks.player_id)";
    private static final String ORPHANED_PROFILE_TRUST = "st_profile_trusted WHERE NOT EXISTS "
            + "(SELECT 1 FROM st_profiles WHERE st_profiles.player_id = st_profile_trusted.player_id)";
    private static final String ORPHANED_LOCK_BLOCKS = "st_lock_blocks WHERE NOT EXISTS "
            + "(SELECT 1 FROM st_locks WHERE st_locks.lock_id = st_lock_blocks.lock_id)";
    private static final String ORPHANED_LOCK_TRUST = "st_lock_trusted WHERE NOT EXISTS "
            + "(SELECT 1 FROM st_locks WHERE st_locks.lock_id = st_lock_trusted.lock_id)";
    private static final String EMPTY_LOCKS = "st_locks WHERE NOT EXISTS "
            + "(SELECT 1 FROM st_lock_blocks WHERE st_lock_blocks.lock_id = st_locks.lock_id)";
    private static final String INVALID_SPAWN_LOCATIONS = "st_spawn_locations WHERE location_kind NOT IN "
            + "('available', 'assignment', 'retired') OR (location_kind = 'assignment' AND player_id IS NULL) "
            + "OR (location_kind <> 'assignment' AND player_id IS NOT NULL)";

    private final StorageConfiguration configuration;
    private final Logger logger;
    private final HikariDataSource dataSource;
    private final ReentrantReadWriteLock snapshotGate = new ReentrantReadWriteLock();
    private long mailPurgedBefore = Long.MIN_VALUE;
    private Connection instanceLeaseConnection;
    private List<?> leaseKeys = List.of();
    private ScheduledExecutorService leaseMonitor;
    private ScheduledFuture<?> leaseCheck;
    private volatile String leaseFailure = "";

    public SqlStorage(StorageConfiguration configuration, Logger logger) {
        this(configuration, logger, false);
    }

    private SqlStorage(StorageConfiguration configuration, Logger logger, boolean readOnlySnapshot) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.logger = Objects.requireNonNull(logger, "logger");
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SurvivalTweaks-" + configuration.backend().key());
        // A backup contains a checkpointed database with no WAL. Immutable mode also
        // prevents SQLite from creating new sidecars in the staged restore directory.
        hikari.setJdbcUrl(readOnlySnapshot
                ? "jdbc:sqlite:" + configuration.sqliteFile().toUri().toASCIIString() + "?immutable=1"
                : configuration.jdbcUrl());
        hikari.setDriverClassName(configuration.driverClassName());
        hikari.setMaximumPoolSize(
                configuration.backend() == StorageBackend.SQLITE
                        ? 1
                        : configuration.poolSize() + 1
        );
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(configuration.connectionTimeoutMillis());
        hikari.setValidationTimeout(Math.min(5_000, configuration.connectionTimeoutMillis()));
        hikari.setInitializationFailTimeout(configuration.connectionTimeoutMillis());
        hikari.setAutoCommit(true);
        if (configuration.backend() != StorageBackend.SQLITE) {
            hikari.setUsername(configuration.username());
            hikari.setPassword(configuration.password());
        }
        configureDriver(hikari);
        if (readOnlySnapshot) {
            SQLiteConfig options = new SQLiteConfig();
            options.setReadOnly(true);
            options.toProperties().forEach((key, value) ->
                    hikari.addDataSourceProperty(key.toString(), value));
            hikari.setReadOnly(true);
        }
        dataSource = new HikariDataSource(hikari);
        if (readOnlySnapshot) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            validateExistingSchema(connection);
            configureConnection(connection);
            initializeDatabaseDefaults(connection);
            initializeSchema(connection);
        } catch (SQLException | RuntimeException exception) {
            dataSource.close();
            throw new IllegalStateException(
                    "Could not initialize " + configuration.backend().key() + " storage",
                    exception
            );
        }
    }

    public StorageBackend backend() {
        return configuration.backend();
    }

    public String endpointFingerprint() {
        return configuration.endpointFingerprint();
    }

    public UUID ensureInstanceId(UUID expected) throws IOException {
        Objects.requireNonNull(expected, "expected");
        try (Connection connection = connection()) {
            Optional<String> current = meta(connection, META_INSTANCE_ID);
            if (current.isPresent()) {
                UUID stored = UUID.fromString(current.get());
                if (!stored.equals(expected)) {
                    throw new IOException(
                            "The configured database belongs to a different SurvivalTweaks instance"
                    );
                }
                return stored;
            }
            setMeta(connection, META_INSTANCE_ID, expected.toString());
            return expected;
        } catch (SQLException | IllegalArgumentException exception) {
            throw io("Could not verify storage instance identity", exception);
        }
    }

    public UUID instanceId() throws IOException {
        try (Connection connection = connection()) {
            return readInstanceId(connection);
        } catch (SQLException | IllegalArgumentException exception) {
            throw io("Could not read storage instance identity", exception);
        }
    }

    static UUID sqliteInstanceId(Path file) throws IOException {
        SQLiteConfig options = new SQLiteConfig();
        // SQLite needs write access to recover a hot rollback journal before reading the identity.
        options.setReadOnly(!hasSqliteSidecar(file, "-journal"));
        try (Connection connection = openExistingSqlite(file, options)) {
            return readInstanceId(connection);
        } catch (SQLException | IllegalArgumentException exception) {
            throw new IOException("Could not read SQLite instance identity", exception);
        }
    }

    static StorageSnapshot verifySqliteRestore(StorageConfiguration configuration, UUID expected, Logger logger)
            throws IOException {
        try (SqlStorage copy = new SqlStorage(configuration, logger, true)) {
            Inspection inspection = copy.inspect(expected == null);
            Verification verification = inspection.verification();
            if (!verification.healthy()) {
                throw new IOException(String.join("; ", verification.problems()));
            }
            if (expected != null && !expected.equals(copy.instanceId())) {
                throw new IOException("SQLite database does not match the archived storage identity");
            }
            return inspection.snapshot().orElseThrow();
        } catch (IOException | RuntimeException exception) {
            throw new IOException("Cannot restore " + configuration.sqliteFile().getFileName()
                    + ": " + exception.getMessage(), exception);
        }
    }

    private static UUID readInstanceId(Connection connection) throws SQLException, IOException {
        String value = meta(connection, META_INSTANCE_ID)
                .orElseThrow(() -> new IOException("Storage instance identity is missing"));
        return UUID.fromString(value);
    }

    /**
     * Holds a database-session lock for this endpoint until the storage closes.
     * This prevents two copied server directories from concurrently mutating the
     * same remote database even when their stored instance UUIDs are identical.
     */
    public synchronized void acquireInstanceLease() throws IOException {
        if (configuration.backend() == StorageBackend.SQLITE || instanceLeaseConnection != null) {
            return;
        }
        if (!leaseFailure.isBlank()) {
            throw new IOException("The database instance lease has failed: " + leaseFailure);
        }
        Connection lease = null;
        try {
            lease = connection();
            boolean postgres = configuration.backend() == StorageBackend.POSTGRESQL;
            List<?> keys;
            // Retain the configured endpoint key so older builds cannot acquire it concurrently.
            if (postgres) {
                String schema = leaseIdentity(lease, "SELECT current_schema()");
                UUID hash = UUID.nameUUIDFromBytes(("survivaltweaks:" + schema)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                long current = hash.getMostSignificantBits() ^ hash.getLeastSignificantBits();
                long legacy = Long.parseUnsignedLong(configuration.endpointFingerprint().substring(0, 16), 16);
                keys = current == legacy ? List.of(current) : List.of(current, legacy);
            } else {
                String database = leaseIdentity(lease,
                        "SELECT IF(@@lower_case_table_names = 0, DATABASE(), LOWER(DATABASE()))");
                String current = "survivaltweaks:" + UUID.nameUUIDFromBytes(
                        database.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String legacy = "survivaltweaks:" + configuration.endpointFingerprint().substring(0, 40);
                keys = List.of(current, legacy);
            }
            String acquire = postgres ? "SELECT pg_try_advisory_lock(?)" : "SELECT GET_LOCK(?, 0)";
            for (Object key : keys) {
                if (!leaseQuery(lease, acquire, key)) {
                    throw new IOException("Another SurvivalTweaks server is already using this database endpoint");
                }
            }
            leaseKeys = keys;
            instanceLeaseConnection = lease;
            startLeaseMonitor();
        } catch (SQLException | RuntimeException | IOException exception) {
            if (lease != null) {
                // Returning a pooled connection would leave any acquired session locks in place.
                dataSource.evictConnection(lease);
                try {
                    lease.close();
                } catch (SQLException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            if (exception instanceof IOException failure) {
                throw failure;
            }
            throw io("Could not acquire the database instance lease", exception);
        }
    }

    private static boolean leaseQuery(Connection lease, String query, Object key) throws SQLException {
        try (PreparedStatement statement = lease.prepareStatement(query)) {
            statement.setObject(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private static String leaseIdentity(Connection connection, String query) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(query)) {
            if (!results.next() || results.getString(1) == null) {
                throw new SQLException("Could not identify the database namespace for the instance lease");
            }
            return results.getString(1);
        }
    }

    @Override
    public Profile load(UUID uniqueId) {
        Objects.requireNonNull(uniqueId, "uniqueId");
        try (Connection connection = connection()) {
            beginSnapshotRead(connection);
            Profile profile = readProfile(connection, uniqueId);
            connection.commit();
            return profile;
        } catch (SQLException | RuntimeException exception) {
            throw new IllegalStateException("Could not load profile " + uniqueId, exception);
        }
    }

    private Profile readProfile(Connection connection, UUID uniqueId) throws SQLException {
        Profile profile = new Profile(uniqueId);
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT sounds, particles, dialogs, action_bar, reduced_effects,
                       player_list, mention_notifications, journey_guidance,
                       public_profile, mail_enabled, language, last_known_name,
                       last_seen_at, play_time_ticks
                FROM st_profiles WHERE player_id = ?
                """
        )) {
            statement.setString(1, uniqueId.toString());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return profile;
                }
                readProfileRow(results, profile);
            }
        }
        loadHomes(connection, profile);
        loadHints(connection, profile);
        loadNotifications(connection, profile);
        loadMailBlocks(connection, profile);
        loadProfileTrusted(connection, profile);
        return profile;
    }

    private void readProfileRow(ResultSet row, Profile profile) throws SQLException {
        profile.preferences(new PlayerPreferences(
                bool(row, "sounds"),
                bool(row, "particles"),
                bool(row, "dialogs"),
                bool(row, "action_bar"),
                bool(row, "reduced_effects"),
                bool(row, "player_list"),
                bool(row, "mention_notifications"),
                bool(row, "journey_guidance"),
                bool(row, "public_profile"),
                bool(row, "mail_enabled"),
                enumValue(LanguagePreference.class, row.getString("language"), LanguagePreference.AUTO)
        ));
        profile.lastKnownName(row.getString("last_known_name"));
        long lastSeen = row.getLong("last_seen_at");
        if (!row.wasNull()) {
            profile.lastSeenAt(Instant.ofEpochMilli(lastSeen));
        }
        profile.playTimeTicks(row.getLong("play_time_ticks"));
    }

    @Override
    public Map<UUID, Set<UUID>> loadAllGlobalTrusts() throws IOException {
        try (Connection connection = connection()) {
            return loadAllGlobalTrusts(connection);
        } catch (SQLException | RuntimeException exception) {
            throw io("Could not load global trusts", exception);
        }
    }

    @Override
    public Map<UUID, String> loadKnownPlayerNames() throws IOException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT player_id, last_known_name FROM st_profiles WHERE last_known_name <> ''");
             ResultSet results = statement.executeQuery()) {
            Map<UUID, String> names = new java.util.HashMap<>();
            while (results.next()) {
                names.put(UUID.fromString(results.getString("player_id")), results.getString("last_known_name"));
            }
            return names;
        } catch (SQLException | RuntimeException exception) {
            throw io("Could not load saved player names", exception);
        }
    }

    @Override
    public void save(ProfileSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        transaction(connection -> replaceProfile(connection, snapshot, mailPurgedBefore));
    }

    @Override
    public List<ContainerLockSnapshot> loadLocks() {
        try (Connection connection = connection()) {
            return loadLocks(connection);
        } catch (SQLException | RuntimeException exception) {
            throw new IllegalStateException("Could not load container locks", exception);
        }
    }

    @Override
    public void saveLocks(java.util.Collection<ContainerLockSnapshot> locks) throws IOException {
        List<ContainerLockSnapshot> snapshot = List.copyOf(locks);
        transaction(connection -> replaceLocks(connection, snapshot));
    }

    @Override
    public List<DeathMarker> loadDeathMarkers() {
        try (Connection connection = connection()) {
            return loadDeathMarkers(connection);
        } catch (SQLException | RuntimeException exception) {
            throw new IllegalStateException("Could not load death markers", exception);
        }
    }

    @Override
    public void saveDeathMarkers(java.util.Collection<DeathMarker> markers) throws IOException {
        List<DeathMarker> snapshot = List.copyOf(markers);
        transaction(connection -> replaceDeathMarkers(connection, snapshot));
    }

    @Override
    public NewPlayerSpawnState loadSpawnState() {
        try (Connection connection = connection()) {
            return loadSpawnState(connection);
        } catch (SQLException | RuntimeException exception) {
            throw new IllegalStateException("Could not load new-player spawn state", exception);
        }
    }

    @Override
    public void saveSpawnState(NewPlayerSpawnState state) throws IOException {
        Objects.requireNonNull(state, "state");
        transaction(connection -> replaceSpawnState(connection, state));
    }

    public StorageSnapshot exportSnapshot() throws IOException {
        snapshotGate.writeLock().lock();
        try (Connection connection = connection()) {
            beginSnapshotRead(connection);
            try {
                StorageSnapshot snapshot = readSnapshot(connection);
                connection.commit();
                return snapshot;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        } catch (Exception exception) {
            throw io("Could not export storage snapshot: " + exception.getMessage(), exception);
        } finally {
            snapshotGate.writeLock().unlock();
        }
    }

    private void beginSnapshotRead(Connection connection) throws SQLException {
        if (configuration.backend() != StorageBackend.SQLITE) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
        }
        connection.setAutoCommit(false);
    }

    private StorageSnapshot readSnapshot(Connection connection) throws SQLException {
        return new StorageSnapshot(loadAllProfiles(connection), loadLocks(connection),
                loadDeathMarkers(connection), loadSpawnState(connection));
    }

    public void replaceAll(StorageSnapshot snapshot, UUID instanceId) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(instanceId, "instanceId");
        transaction(connection -> {
            clearAll(connection);
            for (ProfileSnapshot profile : snapshot.profiles()) {
                replaceProfile(connection, profile, Long.MIN_VALUE);
            }
            replaceLocks(connection, snapshot.locks());
            replaceDeathMarkers(connection, snapshot.deathMarkers());
            replaceSpawnState(connection, snapshot.newPlayerSpawns());
            setMeta(connection, META_INSTANCE_ID, instanceId.toString());
        });
    }

    public void clearData(UUID instanceId) throws IOException {
        replaceAll(
                new StorageSnapshot(List.of(), List.of(), List.of(), NewPlayerSpawnState.EMPTY),
                instanceId
        );
    }

    public boolean isEmpty() throws IOException {
        try (Connection connection = connection()) {
            return count(connection, "st_profiles") == 0
                    && count(connection, "st_locks") == 0
                    && count(connection, "st_death_markers") == 0
                    && count(connection, "st_spawn_locations") == 0
                    && count(connection, "st_spawn_replacements") == 0;
        } catch (SQLException exception) {
            throw io("Could not inspect storage contents", exception);
        }
    }

    public Status status() {
        if (!leaseFailure.isBlank()) {
            return new Status(
                    configuration.backend(),
                    false,
                    0,
                    -1,
                    0,
                    0,
                    0,
                    "Database instance lease failed: " + leaseFailure
            );
        }
        long started = System.nanoTime();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1");
             ResultSet ignored = statement.executeQuery()) {
            long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            int schema = Integer.parseInt(meta(connection, META_SCHEMA_VERSION).orElse("0"));
            return new Status(
                    configuration.backend(),
                    true,
                    schema,
                    latency,
                    dataSource.getHikariPoolMXBean().getActiveConnections(),
                    dataSource.getHikariPoolMXBean().getIdleConnections(),
                    dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection(),
                    ""
            );
        } catch (Exception exception) {
            return new Status(
                    configuration.backend(),
                    false,
                    0,
                    -1,
                    0,
                    0,
                    0,
                    exception.getClass().getSimpleName() + ": "
                            + Objects.requireNonNullElse(exception.getMessage(), "unknown failure")
            );
        }
    }

    public Verification verify() {
        return inspect(false).verification();
    }

    /**
     * Verifies the database and hands back the snapshot the integrity check
     * already had to read, so callers that need the data do not pay for a
     * second full pass over every table.
     */
    public Inspection inspect() {
        return inspect(false);
    }

    public MaintenancePreview maintenancePreview(Instant now, int mailPurgeInactiveDays) throws IOException {
        Objects.requireNonNull(now, "now");
        try (Connection connection = connection()) {
            return maintenancePreview(connection, now, mailPurgeInactiveDays);
        } catch (SQLException | RuntimeException exception) {
            throw io("Could not preview storage maintenance", exception);
        }
    }

    public MaintenanceResult maintain(Instant now, int mailPurgeInactiveDays) throws IOException {
        Objects.requireNonNull(now, "now");
        snapshotGate.writeLock().lock();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                MaintenancePreview before = maintenancePreview(connection, now, mailPurgeInactiveDays);
                int oldMailRemoved = 0;
                long mailCutoff = Long.MIN_VALUE;
                if (mailPurgeInactiveDays > 0) {
                    mailCutoff = now.minus(Duration.ofDays(mailPurgeInactiveDays)).toEpochMilli();
                    oldMailRemoved = deleteRows(connection, OLD_MAIL, mailCutoff);
                }
                MaintenancePreview removed = new MaintenancePreview(
                        deleteRows(connection, EXPIRED_DEATH_MARKERS, now.toEpochMilli()),
                        deleteRows(connection, ORPHANED_HOME_SHARES),
                        deleteRows(connection, ORPHANED_HOMES),
                        deleteRows(connection, ORPHANED_HINTS),
                        deleteRows(connection, ORPHANED_NOTIFICATIONS),
                        deleteRows(connection, ORPHANED_MAIL_BLOCKS),
                        deleteRows(connection, ORPHANED_PROFILE_TRUST),
                        deleteRows(connection, ORPHANED_LOCK_BLOCKS),
                        deleteRows(connection, ORPHANED_LOCK_TRUST),
                        deleteRows(connection, EMPTY_LOCKS),
                        deleteRows(connection, INVALID_SPAWN_LOCATIONS),
                        oldMailRemoved
                );
                MaintenancePreview remaining = maintenancePreview(connection, now, mailPurgeInactiveDays);
                if (remaining.total() != 0) {
                    throw new SQLException(
                            "Maintenance cleanup left " + remaining.total()
                                    + " invalid or expired row(s)"
                    );
                }
                List<String> problems = new ArrayList<>();
                inspectSchema(connection, false, problems);
                if (!problems.isEmpty()) {
                    throw new SQLException("Maintenance verification failed: " + String.join("; ", problems));
                }
                readSnapshot(connection);
                connection.commit();
                // Queued profile snapshots may still contain mail that this transaction deleted.
                mailPurgedBefore = Math.max(mailPurgedBefore, mailCutoff);
                return new MaintenanceResult(before, removed, now);
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        } catch (Exception exception) {
            throw io("Could not perform storage maintenance", exception);
        } finally {
            snapshotGate.writeLock().unlock();
        }
    }

    Verification verifyUninitialized() {
        return inspect(true).verification();
    }

    private Inspection inspect(boolean allowMissingInstanceId) {
        List<String> problems = new ArrayList<>();
        if (!leaseFailure.isBlank()) {
            problems.add("Database instance lease failed: " + leaseFailure);
        }
        try (Connection connection = connection()) {
            inspectSchema(connection, allowMissingInstanceId, problems);
        } catch (Exception exception) {
            problems.add(exception.getClass().getSimpleName() + ": "
                    + Objects.requireNonNullElse(exception.getMessage(), "verification failed"));
        }
        StorageSnapshot snapshot = null;
        try {
            snapshot = exportSnapshot();
        } catch (Exception exception) {
            problems.add("Snapshot read failed: " + exception.getClass().getSimpleName() + ": "
                    + Objects.requireNonNullElse(exception.getMessage(), "unknown failure"));
        }
        return new Inspection(
                new Verification(problems.isEmpty(), List.copyOf(problems)),
                Optional.ofNullable(snapshot)
        );
    }

    private void inspectSchema(Connection connection, boolean allowMissingInstanceId, List<String> problems)
            throws SQLException {
        for (String requiredQuery : requiredSchemaQueries()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeQuery(requiredQuery).close();
            }
        }
        if (configuration.backend() == StorageBackend.SQLITE) {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
                while (result.next()) {
                    String value = result.getString(1);
                    if (!value.equalsIgnoreCase("ok")) {
                        problems.add(value);
                    }
                }
            }
        }
        orphanCount(connection, "st_homes", "player_id", "st_profiles", "player_id", problems);
        orphanCount(connection, "st_hints", "player_id", "st_profiles", "player_id", problems);
        orphanCount(connection, "st_notifications", "player_id", "st_profiles", "player_id", problems);
        orphanCount(connection, "st_mail_blocks", "player_id", "st_profiles", "player_id", problems);
        orphanCount(connection, "st_profile_trusted", "player_id", "st_profiles", "player_id", problems);
        orphanCount(connection, "st_lock_blocks", "lock_id", "st_locks", "lock_id", problems);
        orphanCount(connection, "st_lock_trusted", "lock_id", "st_locks", "lock_id", problems);
        problemCount(
                connection,
                "SELECT COUNT(*) FROM st_home_shares s LEFT JOIN st_homes h "
                        + "ON s.player_id = h.player_id AND s.home_key = h.home_key "
                        + "WHERE h.player_id IS NULL",
                "st_home_shares contains %d orphan row(s)",
                problems
        );
        problemCount(
                connection,
                "SELECT COUNT(*) FROM st_locks l LEFT JOIN st_lock_blocks b "
                        + "ON l.lock_id = b.lock_id WHERE b.lock_id IS NULL",
                "st_locks contains %d lock(s) without blocks",
                problems
        );
        problemCount(
                connection,
                "SELECT COUNT(*) FROM " + INVALID_SPAWN_LOCATIONS,
                "st_spawn_locations contains %d invalid row(s)",
                problems
        );
        invalidEnumCount(connection, "st_profiles", "language", LanguagePreference.class, problems);
        invalidEnumCount(connection, "st_homes", "category", HomeCategory.class, problems);
        invalidEnumCount(connection, "st_homes", "arrival_style", HomeArrivalStyle.class, problems);
        invalidEnumCount(connection, "st_hints", "hint", OnboardingHint.class, problems);
        invalidEnumCount(
                connection,
                "st_notifications",
                "notification_type",
                NotificationType.class,
                problems
        );
        invalidEnumCount(connection, "st_locks", "access_mode", LockAccessMode.class, problems);
        int schema = Integer.parseInt(meta(connection, META_SCHEMA_VERSION).orElse("0"));
        if (schema != SCHEMA_VERSION) {
            problems.add("Expected schema " + SCHEMA_VERSION + " but found " + schema);
        }
        Optional<String> instance = meta(connection, META_INSTANCE_ID);
        if (instance.isEmpty()) {
            if (!allowMissingInstanceId) {
                problems.add("Storage instance identity is missing");
            }
        } else {
            UUID.fromString(instance.get());
        }
    }

    private List<String> requiredSchemaQueries() {
        return List.of(
                "SELECT meta_key, meta_value FROM st_meta WHERE 1 = 0",
                "SELECT player_id, sounds, particles, dialogs, action_bar, reduced_effects, "
                        + "player_list, mention_notifications, journey_guidance, public_profile, "
                        + "mail_enabled, language, last_known_name, last_seen_at, play_time_ticks "
                        + "FROM st_profiles WHERE 1 = 0",
                "SELECT player_id, home_key, position, display_name, world_id, world_name, x, y, z, "
                        + "yaw, pitch, icon, description, favorite, sort_order, category, arrival_style "
                        + "FROM st_homes WHERE 1 = 0",
                "SELECT player_id, home_key, shared_player_id FROM st_home_shares WHERE 1 = 0",
                "SELECT player_id, hint FROM st_hints WHERE 1 = 0",
                "SELECT player_id, notification_id, position, notification_type, created_at, actor_id, "
                        + "actor_name, detail, is_read FROM st_notifications WHERE 1 = 0",
                "SELECT player_id, blocked_player_id FROM st_mail_blocks WHERE 1 = 0",
                "SELECT player_id, trusted_player_id FROM st_profile_trusted WHERE 1 = 0",
                "SELECT lock_id, owner_id, lock_name, access_mode, automation_allowed "
                        + "FROM st_locks WHERE 1 = 0",
                "SELECT lock_id, world_id, x, y, z FROM st_lock_blocks WHERE 1 = 0",
                "SELECT lock_id, player_id FROM st_lock_trusted WHERE 1 = 0",
                "SELECT player_id, world_id, world_name, x, y, z, created_at, expires_at, cause "
                        + "FROM st_death_markers WHERE 1 = 0",
                "SELECT location_kind, position, player_id, completed, world_id, world_name, "
                        + "x, y, z, yaw FROM st_spawn_locations WHERE 1 = 0",
                "SELECT player_id FROM st_spawn_replacements WHERE 1 = 0"
        );
    }

    private MaintenancePreview maintenancePreview(Connection connection, Instant now, int mailPurgeInactiveDays)
            throws SQLException {
        int oldMailCount = 0;
        if (mailPurgeInactiveDays > 0) {
            long mailCutoff = now.minus(Duration.ofDays(mailPurgeInactiveDays)).toEpochMilli();
            oldMailCount = countRows(connection, OLD_MAIL, mailCutoff);
        }
        return new MaintenancePreview(
                countRows(connection, EXPIRED_DEATH_MARKERS, now.toEpochMilli()),
                countRows(connection, ORPHANED_HOME_SHARES),
                countRows(connection, ORPHANED_HOMES),
                countRows(connection, ORPHANED_HINTS),
                countRows(connection, ORPHANED_NOTIFICATIONS),
                countRows(connection, ORPHANED_MAIL_BLOCKS),
                countRows(connection, ORPHANED_PROFILE_TRUST),
                countRows(connection, ORPHANED_LOCK_BLOCKS),
                countRows(connection, ORPHANED_LOCK_TRUST),
                countRows(connection, EMPTY_LOCKS),
                countRows(connection, INVALID_SPAWN_LOCATIONS),
                oldMailCount
        );
    }

    private int countRows(Connection connection, String rowFilter, long parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + rowFilter)) {
            statement.setLong(1, parameter);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return Math.toIntExact(result.getLong(1));
            }
        }
    }

    private int countRows(Connection connection, String rowFilter) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + rowFilter)) {
            result.next();
            return Math.toIntExact(result.getLong(1));
        }
    }

    private int deleteRows(Connection connection, String rowFilter, long parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + rowFilter)) {
            statement.setLong(1, parameter);
            return statement.executeUpdate();
        }
    }

    private int deleteRows(Connection connection, String rowFilter) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate("DELETE FROM " + rowFilter);
        }
    }

    public void checkpoint() throws IOException {
        if (configuration.backend() != StorageBackend.SQLITE) {
            return;
        }
        try (Connection connection = connection()) {
            checkpoint(connection);
        } catch (SQLException exception) {
            throw io("Could not checkpoint SQLite storage", exception);
        }
    }

    public static void checkpointBeforeStartupBackup(Path database) throws IOException {
        boolean hasJournal = hasSqliteSidecar(database, "-journal");
        if (!Files.isRegularFile(database) || Files.isSymbolicLink(database)
                || (!hasJournal && !hasSqliteSidecar(database, "-wal"))) {
            return;
        }
        SQLiteConfig options = new SQLiteConfig();
        options.setBusyTimeout(0);
        try (Connection connection = openExistingSqlite(database, options)) {
            if (hasJournal) {
                // Touch the database so SQLite resolves the journal before we copy the main file.
                try (Statement statement = connection.createStatement();
                     ResultSet version = statement.executeQuery("PRAGMA schema_version")) {
                    if (!version.next()) {
                        throw new CheckpointUnavailableException(null);
                    }
                }
            }
            checkpoint(connection);
        } catch (SQLException exception) {
            throw new CheckpointUnavailableException(exception);
        }
    }

    private static Connection openExistingSqlite(Path database, SQLiteConfig options) throws SQLException {
        options.resetOpenMode(SQLiteOpenMode.CREATE);
        return new JDBC().connect("jdbc:sqlite:" + database, options.toProperties());
    }

    private static boolean hasSqliteSidecar(Path database, String suffix) {
        return !Files.notExists(database.resolveSibling(database.getFileName() + suffix), LinkOption.NOFOLLOW_LINKS);
    }

    private static void checkpoint(Connection connection) throws SQLException, CheckpointUnavailableException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA wal_checkpoint(FULL)")) {
            if (!result.next() || result.getInt(1) != 0 || result.getInt(2) != result.getInt(3)) {
                throw new CheckpointUnavailableException(null);
            }
        }
    }

    public static final class CheckpointUnavailableException extends IOException {

        private CheckpointUnavailableException(SQLException cause) {
            super("SQLite could not recover its rollback journal or checkpoint its WAL", cause);
        }
    }

    public SnapshotLease acquireSnapshotLease() throws IOException {
        snapshotGate.writeLock().lock();
        try {
            checkpoint();
            return snapshotGate.writeLock()::unlock;
        } catch (IOException | RuntimeException exception) {
            snapshotGate.writeLock().unlock();
            throw exception;
        }
    }

    @Override
    public synchronized void close() {
        stopLeaseMonitor();
        releaseInstanceLease();
        dataSource.close();
    }

    private void startLeaseMonitor() {
        if (leaseMonitor != null) {
            return;
        }
        leaseMonitor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "SurvivalTweaks-database-lease");
            thread.setDaemon(false);
            return thread;
        });
        leaseCheck = leaseMonitor.scheduleWithFixedDelay(
                this::verifyInstanceLease,
                LEASE_CHECK_SECONDS,
                LEASE_CHECK_SECONDS,
                TimeUnit.SECONDS
        );
    }

    private synchronized void verifyInstanceLease() {
        Connection lease = instanceLeaseConnection;
        if (lease == null || !leaseFailure.isBlank()) {
            return;
        }
        try {
            for (Object key : leaseKeys) {
                boolean held;
                if (key instanceof Long value) {
                    try (PreparedStatement check = lease.prepareStatement("""
                            SELECT EXISTS (
                                SELECT 1 FROM pg_locks WHERE locktype = 'advisory'
                                AND pid = pg_backend_pid() AND granted AND objsubid = 1
                                AND classid::bigint = ? AND objid::bigint = ?
                            )
                            """)) {
                        check.setLong(1, value >>> 32);
                        check.setLong(2, value & 0xffffffffL);
                        try (ResultSet result = check.executeQuery()) {
                            held = result.next() && result.getBoolean(1);
                        }
                    }
                } else {
                    held = leaseQuery(lease, "SELECT IS_USED_LOCK(?) = CONNECTION_ID()", key);
                }
                if (!held) {
                    throw new SQLException("The database session no longer owns its singleton lock");
                }
            }
        } catch (SQLException | RuntimeException exception) {
            leaseFailure = exception.getClass().getSimpleName() + ": "
                    + Objects.requireNonNullElse(exception.getMessage(), "lease check failed");
            logger.log(
                    Level.SEVERE,
                    "Lost the SurvivalTweaks database instance lease; closing the pool to prevent concurrent writers",
                    exception
            );
            instanceLeaseConnection = null;
            try {
                lease.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            dataSource.close();
            if (leaseCheck != null) {
                leaseCheck.cancel(false);
                leaseCheck = null;
            }
            if (leaseMonitor != null) {
                leaseMonitor.shutdown();
                leaseMonitor = null;
            }
        }
    }

    private void stopLeaseMonitor() {
        if (leaseCheck != null) {
            leaseCheck.cancel(true);
            leaseCheck = null;
        }
        if (leaseMonitor != null) {
            leaseMonitor.shutdownNow();
            leaseMonitor = null;
        }
    }

    private void releaseInstanceLease() {
        Connection lease = instanceLeaseConnection;
        instanceLeaseConnection = null;
        if (lease == null) {
            return;
        }
        try {
            if (!lease.isClosed()) {
                String unlock = configuration.backend() == StorageBackend.POSTGRESQL
                        ? "SELECT pg_advisory_unlock(?)" : "SELECT RELEASE_LOCK(?)";
                for (Object key : leaseKeys) {
                    leaseQuery(lease, unlock, key);
                }
            }
        } catch (SQLException exception) {
            dataSource.evictConnection(lease);
            logger.log(Level.WARNING, "Could not release database instance lease", exception);
        } finally {
            try {
                lease.close();
            } catch (SQLException exception) {
                logger.log(Level.WARNING, "Could not close database lease connection", exception);
            }
        }
    }

    private void configureDriver(HikariConfig hikari) {
        int connectSeconds = Math.max(
                1,
                (int) Math.ceil(configuration.connectionTimeoutMillis() / 1_000.0)
        );
        if (configuration.backend() == StorageBackend.POSTGRESQL) {
            hikari.addDataSourceProperty("sslmode", configuration.postgresqlSslMode());
            hikari.addDataSourceProperty("currentSchema", configuration.postgresqlSchema());
            hikari.addDataSourceProperty("connectTimeout", connectSeconds);
            hikari.addDataSourceProperty("socketTimeout", configuration.socketTimeoutSeconds());
            hikari.addDataSourceProperty("queryTimeout", configuration.queryTimeoutSeconds());
            hikari.addDataSourceProperty("tcpKeepAlive", true);
            hikari.addDataSourceProperty("ApplicationName", "SurvivalTweaks");
            hikari.addDataSourceProperty("reWriteBatchedInserts", true);
            hikari.setConnectionInitSql(
                    "SET search_path TO " + configuration.postgresqlSchema()
            );
        } else if (configuration.backend() == StorageBackend.MYSQL) {
            hikari.addDataSourceProperty("sslMode", configuration.mysqlSslModeProperty());
            hikari.addDataSourceProperty("connectTimeout", configuration.connectionTimeoutMillis());
            hikari.addDataSourceProperty(
                    "socketTimeout",
                    configuration.socketTimeoutSeconds() * 1_000
            );
            hikari.addDataSourceProperty("tcpKeepAlive", true);
            hikari.addDataSourceProperty("rewriteBatchedStatements", true);
        }
    }

    private Connection connection() throws SQLException {
        if (!leaseFailure.isBlank()) {
            throw new SQLException("Database instance lease failed: " + leaseFailure);
        }
        Connection connection = dataSource.getConnection();
        try {
            configureConnection(connection);
            return withQueryTimeout(connection);
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    private Connection withQueryTimeout(Connection delegate) {
        if (configuration.backend() == StorageBackend.SQLITE) {
            return delegate;
        }
        return (Connection) Proxy.newProxyInstance(
                SqlStorage.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if (result instanceof Statement statement) {
                            try {
                                statement.setQueryTimeout(configuration.queryTimeoutSeconds());
                            } catch (SQLException | RuntimeException exception) {
                                try {
                                    statement.close();
                                } catch (SQLException closeFailure) {
                                    exception.addSuppressed(closeFailure);
                                }
                                throw exception;
                            }
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
        );
    }

    /**
     * Session state that every connection needs. PostgreSQL gets its search
     * path from {@code currentSchema} and the pool's init SQL instead, both of
     * which run once per physical connection rather than once per borrow.
     */
    private void configureConnection(Connection connection) throws SQLException {
        if (configuration.backend() != StorageBackend.SQLITE || connection.isReadOnly()) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 10000");
            statement.execute("PRAGMA synchronous = FULL");
        }
    }

    /**
     * Settings stored in the database itself rather than in a session, so they
     * survive reconnects and only need applying when the storage is opened.
     */
    private void initializeDatabaseDefaults(Connection connection) throws SQLException {
        if (configuration.backend() != StorageBackend.SQLITE) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
        }
    }

    private void validateExistingSchema(Connection connection) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(
                connection.getCatalog(),
                configuration.backend() == StorageBackend.POSTGRESQL ? configuration.postgresqlSchema() : null,
                "st_meta", null
        )) {
            boolean exists = false;
            while (tables.next()) {
                if ("st_meta".equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                return;
            }
        }
        Optional<String> stored = meta(connection, META_SCHEMA_VERSION);
        if (stored.isEmpty()) {
            return;
        }
        int version = Integer.parseInt(stored.get());
        if (version > SCHEMA_VERSION) {
            throw new SQLException(
                    "Database schema " + version + " is newer than supported schema "
                            + SCHEMA_VERSION
            );
        }
        if (version < SCHEMA_VERSION) {
            throw new SQLException(
                    "No migration path from database schema " + version + " to "
                            + SCHEMA_VERSION
            );
        }
    }

    private void initializeSchema(Connection connection) throws SQLException {
        if (configuration.backend() == StorageBackend.POSTGRESQL) {
            ensurePostgresqlSchema(connection);
        }
        for (String ddl : schema()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(ddl);
            }
        }
        ensureIndex(connection, "st_lock_blocks", "st_lock_blocks_lock_id_idx", "lock_id");
        if (meta(connection, META_SCHEMA_VERSION).isEmpty()) {
            setMeta(connection, META_SCHEMA_VERSION, Integer.toString(SCHEMA_VERSION));
        }
    }

    private void ensurePostgresqlSchema(Connection connection) throws SQLException {
        boolean exists;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = ?"
        )) {
            statement.setString(1, configuration.postgresqlSchema());
            try (ResultSet result = statement.executeQuery()) {
                exists = result.next();
            }
        }
        try (Statement statement = connection.createStatement()) {
            if (!exists) {
                statement.execute("CREATE SCHEMA " + configuration.postgresqlSchema());
            }
            statement.execute("SET search_path TO " + configuration.postgresqlSchema());
        }
    }

    private void ensureIndex(
            Connection connection,
            String table,
            String index,
            String column
    ) throws SQLException {
        if (hasIndex(connection, table, index)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX " + index + " ON " + table + " (" + column + ")");
        } catch (SQLException exception) {
            if (!hasIndex(connection, table, index)) {
                throw exception;
            }
        }
    }

    private boolean hasIndex(Connection connection, String table, String index) throws SQLException {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                connection.getCatalog(),
                configuration.backend() == StorageBackend.POSTGRESQL
                        ? configuration.postgresqlSchema()
                        : null,
                table,
                false,
                false
        )) {
            while (indexes.next()) {
                if (index.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private List<String> schema() {
        return List.of(
                """
                CREATE TABLE IF NOT EXISTS st_meta (
                    meta_key VARCHAR(64) PRIMARY KEY,
                    meta_value VARCHAR(512) NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS st_profiles (
                    player_id VARCHAR(36) PRIMARY KEY,
                    sounds INTEGER NOT NULL,
                    particles INTEGER NOT NULL,
                    dialogs INTEGER NOT NULL,
                    action_bar INTEGER NOT NULL,
                    reduced_effects INTEGER NOT NULL,
                    player_list INTEGER NOT NULL,
                    mention_notifications INTEGER NOT NULL,
                    journey_guidance INTEGER NOT NULL,
                    public_profile INTEGER NOT NULL,
                    mail_enabled INTEGER NOT NULL,
                    language VARCHAR(32) NOT NULL,
                    last_known_name VARCHAR(64) NOT NULL,
                    last_seen_at BIGINT NULL,
                    play_time_ticks BIGINT NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS st_homes (
                    player_id VARCHAR(36) NOT NULL,
                    home_key VARCHAR(64) NOT NULL,
                    position INTEGER NOT NULL,
                    display_name VARCHAR(64) NOT NULL,
                    world_id VARCHAR(36) NULL,
                    world_name VARCHAR(128) NOT NULL,
                    x DOUBLE PRECISION NOT NULL,
                    y DOUBLE PRECISION NOT NULL,
                    z DOUBLE PRECISION NOT NULL,
                    yaw DOUBLE PRECISION NOT NULL,
                    pitch DOUBLE PRECISION NOT NULL,
                    icon VARCHAR(128) NOT NULL,
                    description VARCHAR(1024) NOT NULL,
                    favorite INTEGER NOT NULL,
                    sort_order INTEGER NOT NULL,
                    category VARCHAR(32) NOT NULL,
                    arrival_style VARCHAR(32) NOT NULL,
                    PRIMARY KEY (player_id, home_key),
                    FOREIGN KEY (player_id) REFERENCES st_profiles(player_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS st_home_shares (
                    player_id VARCHAR(36) NOT NULL,
                    home_key VARCHAR(64) NOT NULL,
                    shared_player_id VARCHAR(36) NOT NULL,
                    PRIMARY KEY (player_id, home_key, shared_player_id),
                    FOREIGN KEY (player_id, home_key)
                        REFERENCES st_homes(player_id, home_key) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS st_hints (
                    player_id VARCHAR(36) NOT NULL,
                    hint VARCHAR(64) NOT NULL,
                    PRIMARY KEY (player_id, hint),
                    FOREIGN KEY (player_id) REFERENCES st_profiles(player_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS st_notifications (
                    player_id VARCHAR(36) NOT NULL,
                    notification_id VARCHAR(36) NOT NULL,
                    position INTEGER NOT NULL,
                    notification_type VARCHAR(64) NOT NULL,
                    created_at BIGINT NOT NULL,
                    actor_id VARCHAR(36) NULL,
                    actor_name VARCHAR(64) NOT NULL,
                    detail VARCHAR(1024) NOT NULL,
                    is_read INTEGER NOT NULL,
                    PRIMARY KEY (player_id, notification_id),
                    FOREIGN KEY (player_id) REFERENCES st_profiles(player_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS st_mail_blocks (
                    player_id VARCHAR(36) NOT NULL,
                    blocked_player_id VARCHAR(36) NOT NULL,
                    PRIMARY KEY (player_id, blocked_player_id),
                    FOREIGN KEY (player_id) REFERENCES st_profiles(player_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS st_profile_trusted (
                    player_id VARCHAR(36) NOT NULL,
                    trusted_player_id VARCHAR(36) NOT NULL,
                    PRIMARY KEY (player_id, trusted_player_id),
                    FOREIGN KEY (player_id) REFERENCES st_profiles(player_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS st_locks (
                    lock_id VARCHAR(36) PRIMARY KEY,
                    owner_id VARCHAR(36) NOT NULL,
                    lock_name VARCHAR(128) NOT NULL,
                    access_mode VARCHAR(32) NOT NULL,
                    automation_allowed INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS st_lock_blocks (
                    lock_id VARCHAR(36) NOT NULL,
                    world_id VARCHAR(36) NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    PRIMARY KEY (world_id, x, y, z),
                    FOREIGN KEY (lock_id) REFERENCES st_locks(lock_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS st_lock_trusted (
                    lock_id VARCHAR(36) NOT NULL,
                    player_id VARCHAR(36) NOT NULL,
                    PRIMARY KEY (lock_id, player_id),
                    FOREIGN KEY (lock_id) REFERENCES st_locks(lock_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS st_death_markers (
                    player_id VARCHAR(36) PRIMARY KEY,
                    world_id VARCHAR(36) NOT NULL,
                    world_name VARCHAR(128) NOT NULL,
                    x DOUBLE PRECISION NOT NULL,
                    y DOUBLE PRECISION NOT NULL,
                    z DOUBLE PRECISION NOT NULL,
                    created_at BIGINT NOT NULL,
                    expires_at BIGINT NOT NULL,
                    cause VARCHAR(64) NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS st_spawn_locations (
                    location_kind VARCHAR(16) NOT NULL,
                    position INTEGER NOT NULL,
                    player_id VARCHAR(36) NULL,
                    completed INTEGER NOT NULL,
                    world_id VARCHAR(36) NOT NULL,
                    world_name VARCHAR(128) NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    yaw DOUBLE PRECISION NOT NULL,
                    PRIMARY KEY (location_kind, position)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS st_spawn_replacements (
                    player_id VARCHAR(36) PRIMARY KEY
                )
                """
        );
    }

    private void replaceProfile(Connection connection, ProfileSnapshot snapshot, long mailCutoff) throws SQLException {
        String playerId = snapshot.uniqueId().toString();
        PlayerPreferences preferences = snapshot.preferences();
        int updated;
        try (PreparedStatement update = connection.prepareStatement(
                """
                UPDATE st_profiles SET
                    sounds = ?, particles = ?, dialogs = ?, action_bar = ?, reduced_effects = ?,
                    player_list = ?, mention_notifications = ?, journey_guidance = ?,
                    public_profile = ?, mail_enabled = ?, language = ?, last_known_name = ?,
                    last_seen_at = ?, play_time_ticks = ?
                WHERE player_id = ?
                """
        )) {
            int index = 1;
            setBool(update, index++, preferences.soundsEnabled());
            setBool(update, index++, preferences.particlesEnabled());
            setBool(update, index++, preferences.dialogsEnabled());
            setBool(update, index++, preferences.actionBarEnabled());
            setBool(update, index++, preferences.reducedEffects());
            setBool(update, index++, preferences.playerListEnabled());
            setBool(update, index++, preferences.mentionNotificationsEnabled());
            setBool(update, index++, preferences.journeyGuidanceEnabled());
            setBool(update, index++, preferences.publicProfileEnabled());
            setBool(update, index++, preferences.mailEnabled());
            update.setString(index++, preferences.language().name());
            update.setString(index++, snapshot.lastKnownName());
            if (snapshot.lastSeenAt() == null) {
                update.setNull(index++, Types.BIGINT);
            } else {
                update.setLong(index++, snapshot.lastSeenAt().toEpochMilli());
            }
            update.setLong(index++, snapshot.playTimeTicks());
            update.setString(index, playerId);
            updated = update.executeUpdate();
        }
        if (updated == 0) {
            try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO st_profiles (
                    player_id, sounds, particles, dialogs, action_bar, reduced_effects,
                    player_list, mention_notifications, journey_guidance, public_profile,
                    mail_enabled, language, last_known_name, last_seen_at, play_time_ticks
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
            )) {
                int index = 1;
                insert.setString(index++, playerId);
                setBool(insert, index++, preferences.soundsEnabled());
                setBool(insert, index++, preferences.particlesEnabled());
                setBool(insert, index++, preferences.dialogsEnabled());
                setBool(insert, index++, preferences.actionBarEnabled());
                setBool(insert, index++, preferences.reducedEffects());
                setBool(insert, index++, preferences.playerListEnabled());
                setBool(insert, index++, preferences.mentionNotificationsEnabled());
                setBool(insert, index++, preferences.journeyGuidanceEnabled());
                setBool(insert, index++, preferences.publicProfileEnabled());
                setBool(insert, index++, preferences.mailEnabled());
                insert.setString(index++, preferences.language().name());
                insert.setString(index++, snapshot.lastKnownName());
                if (snapshot.lastSeenAt() == null) {
                    insert.setNull(index++, Types.BIGINT);
                } else {
                    insert.setLong(index++, snapshot.lastSeenAt().toEpochMilli());
                }
                insert.setLong(index, snapshot.playTimeTicks());
                insert.executeUpdate();
            }
        } else {
            clearProfileChildren(connection, playerId);
        }
        insertHomes(connection, snapshot);
        insertHints(connection, snapshot);
        insertNotifications(connection, snapshot, mailCutoff);
        insertMailBlocks(connection, snapshot);
        insertProfileTrusted(connection, snapshot);
    }

    private void clearProfileChildren(Connection connection, String playerId) throws SQLException {
        for (String table : List.of(
                "st_home_shares",
                "st_homes",
                "st_hints",
                "st_notifications",
                "st_mail_blocks",
                "st_profile_trusted"
        )) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE player_id = ?"
            )) {
                statement.setString(1, playerId);
                statement.executeUpdate();
            }
        }
    }

    private void insertHomes(Connection connection, ProfileSnapshot snapshot) throws SQLException {
        try (PreparedStatement homeInsert = connection.prepareStatement(
                """
                INSERT INTO st_homes (
                    player_id, home_key, position, display_name, world_id, world_name,
                    x, y, z, yaw, pitch, icon, description, favorite, sort_order,
                    category, arrival_style
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
        ); PreparedStatement shareInsert = connection.prepareStatement(
                """
                INSERT INTO st_home_shares (player_id, home_key, shared_player_id)
                VALUES (?, ?, ?)
                """
        )) {
            int position = 0;
            for (Home home : snapshot.homes()) {
                String homeKey = home.name().toLowerCase(java.util.Locale.ROOT);
                int index = 1;
                homeInsert.setString(index++, snapshot.uniqueId().toString());
                homeInsert.setString(index++, homeKey);
                homeInsert.setInt(index++, position++);
                homeInsert.setString(index++, home.name());
                if (home.worldId() == null) {
                    homeInsert.setNull(index++, Types.VARCHAR);
                } else {
                    homeInsert.setString(index++, home.worldId().toString());
                }
                homeInsert.setString(index++, home.worldName());
                homeInsert.setDouble(index++, home.x());
                homeInsert.setDouble(index++, home.y());
                homeInsert.setDouble(index++, home.z());
                homeInsert.setDouble(index++, home.yaw());
                homeInsert.setDouble(index++, home.pitch());
                homeInsert.setString(index++, home.icon().getKey().asString());
                homeInsert.setString(index++, home.description());
                setBool(homeInsert, index++, home.favorite());
                homeInsert.setInt(index++, home.order());
                homeInsert.setString(index++, home.category().name());
                homeInsert.setString(index, home.arrivalStyle().name());
                homeInsert.addBatch();
                for (UUID shared : home.sharedWith().stream().sorted().toList()) {
                    shareInsert.setString(1, snapshot.uniqueId().toString());
                    shareInsert.setString(2, homeKey);
                    shareInsert.setString(3, shared.toString());
                    shareInsert.addBatch();
                }
            }
            homeInsert.executeBatch();
            shareInsert.executeBatch();
        }
    }

    private void insertHints(Connection connection, ProfileSnapshot snapshot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO st_hints (player_id, hint) VALUES (?, ?)"
        )) {
            for (OnboardingHint hint : snapshot.seenHints().stream()
                    .sorted(Comparator.comparing(Enum::name))
                    .toList()) {
                statement.setString(1, snapshot.uniqueId().toString());
                statement.setString(2, hint.name());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertNotifications(Connection connection, ProfileSnapshot snapshot, long mailCutoff)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO st_notifications (
                    player_id, notification_id, position, notification_type, created_at,
                    actor_id, actor_name, detail, is_read
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
        )) {
            int position = 0;
            for (PlayerNotification notification : snapshot.notifications()) {
                if (notification.type() == NotificationType.MAIL && notification.createdAt().toEpochMilli() < mailCutoff) {
                    continue;
                }
                int index = 1;
                statement.setString(index++, snapshot.uniqueId().toString());
                statement.setString(index++, notification.id().toString());
                statement.setInt(index++, position++);
                statement.setString(index++, notification.type().name());
                statement.setLong(index++, notification.createdAt().toEpochMilli());
                if (notification.actorId() == null) {
                    statement.setNull(index++, Types.VARCHAR);
                } else {
                    statement.setString(index++, notification.actorId().toString());
                }
                statement.setString(index++, notification.actor());
                statement.setString(index++, notification.detail());
                setBool(statement, index, notification.read());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertMailBlocks(Connection connection, ProfileSnapshot snapshot)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO st_mail_blocks (player_id, blocked_player_id) VALUES (?, ?)"
        )) {
            for (UUID blocked : snapshot.blockedMailSenders().stream().sorted().toList()) {
                statement.setString(1, snapshot.uniqueId().toString());
                statement.setString(2, blocked.toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertProfileTrusted(Connection connection, ProfileSnapshot snapshot)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO st_profile_trusted (player_id, trusted_player_id) VALUES (?, ?)"
        )) {
            for (UUID trusted : snapshot.trustedPlayers().stream().sorted().toList()) {
                statement.setString(1, snapshot.uniqueId().toString());
                statement.setString(2, trusted.toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private List<ProfileSnapshot> loadAllProfiles(Connection connection) throws SQLException {
        Map<UUID, Profile> profiles = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT player_id, sounds, particles, dialogs, action_bar, reduced_effects,
                       player_list, mention_notifications, journey_guidance,
                       public_profile, mail_enabled, language, last_known_name,
                       last_seen_at, play_time_ticks
                FROM st_profiles ORDER BY player_id
                """
        ); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                UUID playerId = UUID.fromString(results.getString("player_id"));
                Profile profile = new Profile(playerId);
                readProfileRow(results, profile);
                profiles.put(playerId, profile);
            }
        }

        Map<HomeShareKey, Set<UUID>> shares = loadAllHomeShares(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT player_id, home_key, display_name, world_id, world_name,
                       x, y, z, yaw, pitch, icon, description, favorite,
                       sort_order, category, arrival_style
                FROM st_homes ORDER BY player_id, position
                """
        ); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                UUID playerId = UUID.fromString(results.getString("player_id"));
                Profile profile = profiles.get(playerId);
                if (profile == null) {
                    continue;
                }
                HomeShareKey key = new HomeShareKey(playerId, results.getString("home_key"));
                addLoadedHome(profile, readHomeRow(results, shares.getOrDefault(key, Set.of())));
            }
        }

        Map<UUID, Set<OnboardingHint>> hints = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_id, hint FROM st_hints ORDER BY player_id, hint"
        ); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                OnboardingHint hint = enumValue(OnboardingHint.class, results.getString("hint"), null);
                if (hint != null) {
                    hints.computeIfAbsent(
                            UUID.fromString(results.getString("player_id")),
                            ignored -> new LinkedHashSet<>()
                    ).add(hint);
                }
            }
        }

        Map<UUID, List<PlayerNotification>> notifications = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT player_id, notification_id, notification_type, created_at,
                       actor_id, actor_name, detail, is_read
                FROM st_notifications ORDER BY player_id, position
                """
        ); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                PlayerNotification notification = readNotificationRow(results);
                if (notification != null) {
                    UUID playerId = UUID.fromString(results.getString("player_id"));
                    notifications.computeIfAbsent(playerId, ignored -> new ArrayList<>()).add(notification);
                }
            }
        }

        Map<UUID, Set<UUID>> blocked = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT player_id, blocked_player_id FROM st_mail_blocks
                ORDER BY player_id, blocked_player_id
                """
        ); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                blocked.computeIfAbsent(
                        UUID.fromString(results.getString("player_id")),
                        ignored -> new LinkedHashSet<>()
                ).add(UUID.fromString(results.getString("blocked_player_id")));
            }
        }

        Map<UUID, Set<UUID>> trusted = loadAllGlobalTrusts(connection);

        for (Map.Entry<UUID, Profile> entry : profiles.entrySet()) {
            UUID playerId = entry.getKey();
            Profile profile = entry.getValue();
            profile.seenHints(hints.getOrDefault(playerId, Set.of()));
            profile.notifications(notifications.getOrDefault(playerId, List.of()));
            profile.blockedMailSenders(blocked.getOrDefault(playerId, Set.of()));
            profile.trustedPlayers(trusted.getOrDefault(playerId, Set.of()));
        }
        return profiles.values().stream().map(Profile::snapshot).toList();
    }

    private Map<UUID, Set<UUID>> loadAllGlobalTrusts(Connection connection) throws SQLException {
        Map<UUID, Set<UUID>> trusted = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT player_id, trusted_player_id FROM st_profile_trusted
                ORDER BY player_id, trusted_player_id
                """
        ); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                trusted.computeIfAbsent(
                        UUID.fromString(results.getString("player_id")),
                        ignored -> new LinkedHashSet<>()
                ).add(UUID.fromString(results.getString("trusted_player_id")));
            }
        }

        return trusted;
    }

    private Map<HomeShareKey, Set<UUID>> loadAllHomeShares(Connection connection)
            throws SQLException {
        Map<HomeShareKey, Set<UUID>> shares = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT player_id, home_key, shared_player_id FROM st_home_shares
                ORDER BY player_id, home_key, shared_player_id
                """
        ); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                HomeShareKey key = new HomeShareKey(
                        UUID.fromString(results.getString("player_id")),
                        results.getString("home_key")
                );
                shares.computeIfAbsent(key, ignored -> new LinkedHashSet<>())
                        .add(UUID.fromString(results.getString("shared_player_id")));
            }
        }
        shares.replaceAll((ignored, values) -> Set.copyOf(values));
        return Map.copyOf(shares);
    }

    private void loadHomes(Connection connection, Profile profile) throws SQLException {
        Map<String, Set<UUID>> shares = loadHomeShares(connection, profile.uniqueId());
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT home_key, display_name, world_id, world_name, x, y, z,
                       yaw, pitch, icon, description, favorite, sort_order,
                       category, arrival_style
                FROM st_homes WHERE player_id = ? ORDER BY position
                """
        )) {
            statement.setString(1, profile.uniqueId().toString());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    String key = results.getString("home_key");
                    addLoadedHome(profile, readHomeRow(results, shares.getOrDefault(key, Set.of())));
                }
            }
        }
    }

    private Home readHomeRow(ResultSet row, Set<UUID> sharedWith) throws SQLException {
        String worldId = row.getString("world_id");
        Material icon = Material.matchMaterial(row.getString("icon"));
        return new Home(
                row.getString("display_name"),
                worldId == null ? null : UUID.fromString(worldId),
                row.getString("world_name"),
                row.getDouble("x"),
                row.getDouble("y"),
                row.getDouble("z"),
                row.getFloat("yaw"),
                row.getFloat("pitch"),
                icon == null ? Material.ENDER_PEARL : icon,
                row.getString("description"),
                bool(row, "favorite"),
                row.getInt("sort_order"),
                enumValue(HomeCategory.class, row.getString("category"), HomeCategory.OTHER),
                enumValue(HomeArrivalStyle.class, row.getString("arrival_style"), HomeArrivalStyle.DEFAULT),
                sharedWith
        );
    }

    private static void addLoadedHome(Profile profile, Home home) throws SQLException {
        if (!profile.addHome(home)) {
            throw new SQLException("Conflicting home rows for player " + profile.uniqueId()
                    + ": duplicate home name '" + home.name() + "'");
        }
    }

    private Map<String, Set<UUID>> loadHomeShares(Connection connection, UUID playerId)
            throws SQLException {
        Map<String, Set<UUID>> shares = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT home_key, shared_player_id FROM st_home_shares
                WHERE player_id = ? ORDER BY home_key, shared_player_id
                """
        )) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    shares.computeIfAbsent(results.getString(1), ignored -> new LinkedHashSet<>())
                            .add(UUID.fromString(results.getString(2)));
                }
            }
        }
        shares.replaceAll((ignored, values) -> Set.copyOf(values));
        return Map.copyOf(shares);
    }

    private void loadHints(Connection connection, Profile profile) throws SQLException {
        Set<OnboardingHint> hints = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT hint FROM st_hints WHERE player_id = ? ORDER BY hint"
        )) {
            statement.setString(1, profile.uniqueId().toString());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    OnboardingHint hint = enumValue(
                            OnboardingHint.class,
                            results.getString(1),
                            null
                    );
                    if (hint != null) {
                        hints.add(hint);
                    }
                }
            }
        }
        profile.seenHints(hints);
    }

    private void loadNotifications(Connection connection, Profile profile) throws SQLException {
        List<PlayerNotification> notifications = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT notification_id, notification_type, created_at, actor_id,
                       actor_name, detail, is_read
                FROM st_notifications WHERE player_id = ? ORDER BY position
                """
        )) {
            statement.setString(1, profile.uniqueId().toString());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    PlayerNotification notification = readNotificationRow(results);
                    if (notification != null) {
                        notifications.add(notification);
                    }
                }
            }
        }
        profile.notifications(notifications);
    }

    private PlayerNotification readNotificationRow(ResultSet row) throws SQLException {
        NotificationType type = enumValue(NotificationType.class, row.getString("notification_type"), null);
        if (type == null) {
            return null;
        }
        String actorId = row.getString("actor_id");
        return new PlayerNotification(
                UUID.fromString(row.getString("notification_id")),
                type,
                Instant.ofEpochMilli(row.getLong("created_at")),
                actorId == null ? null : UUID.fromString(actorId),
                row.getString("actor_name"),
                row.getString("detail"),
                bool(row, "is_read")
        );
    }

    private void loadMailBlocks(Connection connection, Profile profile) throws SQLException {
        Set<UUID> blocked = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT blocked_player_id FROM st_mail_blocks
                WHERE player_id = ? ORDER BY blocked_player_id
                """
        )) {
            statement.setString(1, profile.uniqueId().toString());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    blocked.add(UUID.fromString(results.getString(1)));
                }
            }
        }
        profile.blockedMailSenders(blocked);
    }

    private void loadProfileTrusted(Connection connection, Profile profile) throws SQLException {
        Set<UUID> trusted = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT trusted_player_id FROM st_profile_trusted
                WHERE player_id = ? ORDER BY trusted_player_id
                """
        )) {
            statement.setString(1, profile.uniqueId().toString());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    trusted.add(UUID.fromString(results.getString(1)));
                }
            }
        }
        profile.trustedPlayers(trusted);
    }

    private void replaceLocks(Connection connection, java.util.Collection<ContainerLockSnapshot> locks)
            throws SQLException {
        Map<UUID, ContainerLockSnapshot> existing = loadLocks(connection).stream()
                .collect(java.util.stream.Collectors.toMap(ContainerLockSnapshot::id, lock -> lock));
        Map<UUID, ContainerLockSnapshot> desired = locks.stream()
                .collect(java.util.stream.Collectors.toMap(ContainerLockSnapshot::id, lock -> lock));
        Set<UUID> replaced = new LinkedHashSet<>();
        for (Map.Entry<UUID, ContainerLockSnapshot> entry : existing.entrySet()) {
            if (!entry.getValue().equals(desired.get(entry.getKey()))) {
                replaced.add(entry.getKey());
            }
        }
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM st_locks WHERE lock_id = ?"
        )) {
            for (UUID lockId : replaced) {
                delete.setString(1, lockId.toString());
                delete.addBatch();
            }
            delete.executeBatch();
        }
        List<ContainerLockSnapshot> changed = desired.values().stream()
                .filter(lock -> !lock.equals(existing.get(lock.id())))
                .sorted(Comparator.comparing(ContainerLockSnapshot::id))
                .toList();
        if (changed.isEmpty()) {
            return;
        }
        try (PreparedStatement lockInsert = connection.prepareStatement(
                """
                INSERT INTO st_locks (
                    lock_id, owner_id, lock_name, access_mode, automation_allowed
                ) VALUES (?, ?, ?, ?, ?)
                """
        ); PreparedStatement blockInsert = connection.prepareStatement(
                """
                INSERT INTO st_lock_blocks (lock_id, world_id, x, y, z)
                VALUES (?, ?, ?, ?, ?)
                """
        ); PreparedStatement trustInsert = connection.prepareStatement(
                "INSERT INTO st_lock_trusted (lock_id, player_id) VALUES (?, ?)"
        )) {
            for (ContainerLockSnapshot lock : changed) {
                lockInsert.setString(1, lock.id().toString());
                lockInsert.setString(2, lock.ownerId().toString());
                lockInsert.setString(3, lock.name());
                lockInsert.setString(4, lock.accessMode().name());
                setBool(lockInsert, 5, lock.automationAllowed());
                lockInsert.addBatch();
                for (BlockKey block : lock.blocks().stream()
                        .sorted(Comparator.comparing(BlockKey::worldId)
                                .thenComparingInt(BlockKey::x)
                                .thenComparingInt(BlockKey::y)
                                .thenComparingInt(BlockKey::z))
                        .toList()) {
                    blockInsert.setString(1, lock.id().toString());
                    blockInsert.setString(2, block.worldId().toString());
                    blockInsert.setInt(3, block.x());
                    blockInsert.setInt(4, block.y());
                    blockInsert.setInt(5, block.z());
                    blockInsert.addBatch();
                }
                for (UUID trusted : lock.trustedPlayers().stream().sorted().toList()) {
                    trustInsert.setString(1, lock.id().toString());
                    trustInsert.setString(2, trusted.toString());
                    trustInsert.addBatch();
                }
            }
            lockInsert.executeBatch();
            blockInsert.executeBatch();
            trustInsert.executeBatch();
        }
    }

    private Map<UUID, Set<BlockKey>> loadAllLockBlocks(Connection connection) throws SQLException {
        Map<UUID, Set<BlockKey>> blocks = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT lock_id, world_id, x, y, z FROM st_lock_blocks
                ORDER BY lock_id, world_id, x, y, z
                """
        )) {
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    UUID lockId = UUID.fromString(results.getString("lock_id"));
                    blocks.computeIfAbsent(lockId, ignored -> new LinkedHashSet<>()).add(new BlockKey(
                            UUID.fromString(results.getString("world_id")),
                            results.getInt("x"),
                            results.getInt("y"),
                            results.getInt("z")
                    ));
                }
            }
        }
        blocks.replaceAll((ignored, values) -> Set.copyOf(values));
        return Map.copyOf(blocks);
    }

    private Map<UUID, Set<UUID>> loadAllTrustedPlayers(Connection connection) throws SQLException {
        Map<UUID, Set<UUID>> trusted = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT lock_id, player_id FROM st_lock_trusted
                ORDER BY lock_id, player_id
                """
        )) {
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    UUID lockId = UUID.fromString(results.getString(1));
                    trusted.computeIfAbsent(lockId, ignored -> new LinkedHashSet<>())
                            .add(UUID.fromString(results.getString(2)));
                }
            }
        }
        trusted.replaceAll((ignored, values) -> Set.copyOf(values));
        return Map.copyOf(trusted);
    }

    private List<ContainerLockSnapshot> loadLocks(Connection connection) throws SQLException {
        Map<UUID, Set<BlockKey>> blocksByLock = loadAllLockBlocks(connection);
        Map<UUID, Set<UUID>> trustedByLock = loadAllTrustedPlayers(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT lock_id, owner_id, lock_name, access_mode, automation_allowed
                FROM st_locks ORDER BY lock_id
                """
        ); ResultSet results = statement.executeQuery()) {
            List<ContainerLockSnapshot> locks = new ArrayList<>();
            while (results.next()) {
                UUID lockId = UUID.fromString(results.getString("lock_id"));
                Set<BlockKey> blocks = blocksByLock.getOrDefault(lockId, Set.of());
                if (blocks.isEmpty()) {
                    logger.warning("Skipped SQL container lock without blocks: " + lockId);
                    continue;
                }
                locks.add(new ContainerLockSnapshot(
                        lockId,
                        UUID.fromString(results.getString("owner_id")),
                        blocks,
                        trustedByLock.getOrDefault(lockId, Set.of()),
                        results.getString("lock_name"),
                        enumValue(
                                LockAccessMode.class,
                                results.getString("access_mode"),
                                LockAccessMode.TRUSTED
                        ),
                        bool(results, "automation_allowed")
                ));
            }
            return List.copyOf(locks);
        }
    }

    private List<DeathMarker> loadDeathMarkers(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT player_id, world_id, world_name, x, y, z,
                       created_at, expires_at, cause
                FROM st_death_markers ORDER BY player_id
                """
        ); ResultSet results = statement.executeQuery()) {
            List<DeathMarker> markers = new ArrayList<>();
            while (results.next()) {
                markers.add(new DeathMarker(
                        UUID.fromString(results.getString("player_id")),
                        UUID.fromString(results.getString("world_id")),
                        results.getString("world_name"),
                        results.getDouble("x"),
                        results.getDouble("y"),
                        results.getDouble("z"),
                        Instant.ofEpochMilli(results.getLong("created_at")),
                        Instant.ofEpochMilli(results.getLong("expires_at")),
                        results.getString("cause")
                ));
            }
            return List.copyOf(markers);
        }
    }

    private NewPlayerSpawnState loadSpawnState(Connection connection) throws SQLException {
        List<NewPlayerSpawnLocation> available = new ArrayList<>();
        List<NewPlayerSpawnLocation> retired = new ArrayList<>();
        Map<UUID, NewPlayerSpawnAssignment> assignments = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT location_kind, position, player_id, completed,
                       world_id, world_name, x, y, z, yaw
                FROM st_spawn_locations
                ORDER BY location_kind, position
                """
        ); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                NewPlayerSpawnLocation location = new NewPlayerSpawnLocation(
                        UUID.fromString(results.getString("world_id")),
                        results.getString("world_name"),
                        results.getInt("x"),
                        results.getInt("y"),
                        results.getInt("z"),
                        results.getFloat("yaw")
                );
                switch (results.getString("location_kind")) {
                    case "available" -> available.add(location);
                    case "retired" -> retired.add(location);
                    case "assignment" -> {
                        UUID playerId = UUID.fromString(results.getString("player_id"));
                        var assignment = new NewPlayerSpawnAssignment(playerId, location, bool(results, "completed"));
                        if (assignments.putIfAbsent(playerId, assignment) != null) {
                            throw new SQLException("Conflicting spawn assignments for player " + playerId);
                        }
                    }
                    default -> logger.warning(
                            "Skipped unknown SQL spawn location kind "
                                    + results.getString("location_kind")
                    );
                }
            }
        }
        Set<UUID> awaiting = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_id FROM st_spawn_replacements ORDER BY player_id"
        ); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                awaiting.add(UUID.fromString(results.getString(1)));
            }
        }
        return new NewPlayerSpawnState(available, assignments, retired, awaiting);
    }

    private void replaceDeathMarkers(Connection connection, java.util.Collection<DeathMarker> markers)
            throws SQLException {
        Map<UUID, DeathMarker> existing = loadDeathMarkers(connection).stream()
                .collect(java.util.stream.Collectors.toMap(DeathMarker::playerId, marker -> marker));
        Map<UUID, DeathMarker> desired = markers.stream()
                .collect(java.util.stream.Collectors.toMap(DeathMarker::playerId, marker -> marker));
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM st_death_markers WHERE player_id = ?"
        )) {
            for (Map.Entry<UUID, DeathMarker> entry : existing.entrySet()) {
                if (!entry.getValue().equals(desired.get(entry.getKey()))) {
                    delete.setString(1, entry.getKey().toString());
                    delete.addBatch();
                }
            }
            delete.executeBatch();
        }
        List<DeathMarker> changed = desired.values().stream()
                .filter(marker -> !marker.equals(existing.get(marker.playerId())))
                .sorted(Comparator.comparing(DeathMarker::playerId))
                .toList();
        if (changed.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO st_death_markers (
                    player_id, world_id, world_name, x, y, z, created_at, expires_at, cause
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
        )) {
            for (DeathMarker marker : changed) {
                statement.setString(1, marker.playerId().toString());
                statement.setString(2, marker.worldId().toString());
                statement.setString(3, marker.worldName());
                statement.setDouble(4, marker.x());
                statement.setDouble(5, marker.y());
                statement.setDouble(6, marker.z());
                statement.setLong(7, marker.createdAt().toEpochMilli());
                statement.setLong(8, marker.expiresAt().toEpochMilli());
                statement.setString(9, marker.cause());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void replaceSpawnState(Connection connection, NewPlayerSpawnState state)
            throws SQLException {
        Map<SpawnKey, SpawnRow> existing = loadSpawnRows(connection);
        Map<SpawnKey, SpawnRow> desired = spawnRows(state);
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM st_spawn_locations WHERE location_kind = ? AND position = ?"
        )) {
            for (Map.Entry<SpawnKey, SpawnRow> entry : existing.entrySet()) {
                if (!entry.getValue().equals(desired.get(entry.getKey()))) {
                    delete.setString(1, entry.getKey().kind());
                    delete.setInt(2, entry.getKey().position());
                    delete.addBatch();
                }
            }
            delete.executeBatch();
        }
        try (PreparedStatement locationInsert = connection.prepareStatement(
                """
                INSERT INTO st_spawn_locations (
                    location_kind, position, player_id, completed,
                    world_id, world_name, x, y, z, yaw
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
        )) {
            for (SpawnRow row : desired.values()) {
                if (row.equals(existing.get(new SpawnKey(row.kind(), row.position())))) {
                    continue;
                }
                insertLocation(
                        locationInsert,
                        row.kind(),
                        row.position(),
                        row.playerId(),
                        row.completed(),
                        row.location()
                );
            }
            locationInsert.executeBatch();
        }
        Set<UUID> currentReplacements = loadSpawnReplacements(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM st_spawn_replacements WHERE player_id = ?"
        )) {
            for (UUID playerId : currentReplacements) {
                if (!state.awaitingReplacement().contains(playerId)) {
                    statement.setString(1, playerId.toString());
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO st_spawn_replacements (player_id) VALUES (?)"
        )) {
            for (UUID playerId : state.awaitingReplacement().stream().sorted().toList()) {
                if (currentReplacements.contains(playerId)) {
                    continue;
                }
                statement.setString(1, playerId.toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Map<SpawnKey, SpawnRow> spawnRows(NewPlayerSpawnState state) {
        Map<SpawnKey, SpawnRow> rows = new LinkedHashMap<>();
        int position = 0;
        for (NewPlayerSpawnLocation location : state.available()) {
            SpawnRow row = new SpawnRow("available", position++, null, false, location);
            rows.put(new SpawnKey(row.kind(), row.position()), row);
        }
        position = 0;
        for (NewPlayerSpawnAssignment assignment : state.assignments().values().stream()
                .sorted(Comparator.comparing(NewPlayerSpawnAssignment::playerId))
                .toList()) {
            SpawnRow row = new SpawnRow(
                    "assignment",
                    position++,
                    assignment.playerId(),
                    assignment.completed(),
                    assignment.location()
            );
            rows.put(new SpawnKey(row.kind(), row.position()), row);
        }
        position = 0;
        for (NewPlayerSpawnLocation location : state.retired()) {
            SpawnRow row = new SpawnRow("retired", position++, null, false, location);
            rows.put(new SpawnKey(row.kind(), row.position()), row);
        }
        return Map.copyOf(rows);
    }

    private Map<SpawnKey, SpawnRow> loadSpawnRows(Connection connection) throws SQLException {
        Map<SpawnKey, SpawnRow> rows = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT location_kind, position, player_id, completed,
                       world_id, world_name, x, y, z, yaw
                FROM st_spawn_locations ORDER BY location_kind, position
                """
        ); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                String kind = results.getString("location_kind");
                int position = results.getInt("position");
                String playerId = results.getString("player_id");
                SpawnRow row = new SpawnRow(
                        kind,
                        position,
                        playerId == null ? null : UUID.fromString(playerId),
                        bool(results, "completed"),
                        new NewPlayerSpawnLocation(
                                UUID.fromString(results.getString("world_id")),
                                results.getString("world_name"),
                                results.getInt("x"),
                                results.getInt("y"),
                                results.getInt("z"),
                                results.getFloat("yaw")
                        )
                );
                rows.put(new SpawnKey(kind, position), row);
            }
        }
        return Map.copyOf(rows);
    }

    private Set<UUID> loadSpawnReplacements(Connection connection) throws SQLException {
        Set<UUID> replacements = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_id FROM st_spawn_replacements ORDER BY player_id"
        ); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                replacements.add(UUID.fromString(results.getString(1)));
            }
        }
        return Set.copyOf(replacements);
    }

    private void insertLocation(
            PreparedStatement statement,
            String kind,
            int position,
            UUID playerId,
            boolean completed,
            NewPlayerSpawnLocation location
    ) throws SQLException {
        statement.setString(1, kind);
        statement.setInt(2, position);
        if (playerId == null) {
            statement.setNull(3, Types.VARCHAR);
        } else {
            statement.setString(3, playerId.toString());
        }
        setBool(statement, 4, completed);
        statement.setString(5, location.worldId().toString());
        statement.setString(6, location.worldName());
        statement.setInt(7, location.x());
        statement.setInt(8, location.y());
        statement.setInt(9, location.z());
        statement.setDouble(10, location.yaw());
        statement.addBatch();
    }

    private void clearAll(Connection connection) throws SQLException {
        for (String table : List.of(
                "st_home_shares",
                "st_hints",
                "st_notifications",
                "st_mail_blocks",
                "st_homes",
                "st_profiles",
                "st_lock_trusted",
                "st_lock_blocks",
                "st_locks",
                "st_death_markers",
                "st_spawn_replacements",
                "st_spawn_locations"
        )) {
            execute(connection, "DELETE FROM " + table);
        }
    }

    private void transaction(SqlWork work) throws IOException {
        snapshotGate.readLock().lock();
        try {
            try (Connection connection = connection()) {
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    work.run(connection);
                    connection.commit();
                } catch (Exception exception) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollback) {
                        exception.addSuppressed(rollback);
                    }
                    throw exception;
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            }
        } catch (Exception exception) {
            throw io("SQL transaction failed", exception);
        } finally {
            snapshotGate.readLock().unlock();
        }
    }

    private static Optional<String> meta(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT meta_value FROM st_meta WHERE meta_key = ?"
        )) {
            statement.setString(1, key);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(results.getString(1)) : Optional.empty();
            }
        }
    }

    private void setMeta(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM st_meta WHERE meta_key = ?"
        )) {
            delete.setString(1, key);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO st_meta (meta_key, meta_value) VALUES (?, ?)"
        )) {
            insert.setString(1, key);
            insert.setString(2, value);
            insert.executeUpdate();
        }
    }

    private long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            results.next();
            return results.getLong(1);
        }
    }

    private void orphanCount(
            Connection connection,
            String child,
            String childColumn,
            String parent,
            String parentColumn,
            List<String> problems
    ) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + child + " c LEFT JOIN " + parent
                + " p ON c." + childColumn + " = p." + parentColumn
                + " WHERE p." + parentColumn + " IS NULL";
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql)) {
            results.next();
            long count = results.getLong(1);
            if (count > 0) {
                problems.add(child + " contains " + count + " orphan row(s)");
            }
        }
    }

    private void problemCount(
            Connection connection,
            String sql,
            String message,
            List<String> problems
    ) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql)) {
            results.next();
            long count = results.getLong(1);
            if (count > 0) {
                problems.add(message.formatted(count));
            }
        }
    }

    private <E extends Enum<E>> void invalidEnumCount(
            Connection connection,
            String table,
            String column,
            Class<E> type,
            List<String> problems
    ) throws SQLException {
        String allowed = java.util.Arrays.stream(type.getEnumConstants())
                .map(value -> "'" + value.name() + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        problemCount(
                connection,
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " NOT IN (" + allowed + ")",
                table + "." + column + " contains %d invalid value(s)",
                problems
        );
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private void setBool(PreparedStatement statement, int index, boolean value)
            throws SQLException {
        statement.setInt(index, value ? 1 : 0);
    }

    private boolean bool(ResultSet results, String column) throws SQLException {
        return results.getInt(column) != 0;
    }

    private <E extends Enum<E>> E enumValue(
            Class<E> type,
            String value,
            E fallback
    ) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private IOException io(String message, Exception exception) {
        if (exception instanceof IOException io) {
            return io;
        }
        return new IOException(message, exception);
    }

    private record HomeShareKey(UUID playerId, String homeKey) {
    }

    private record SpawnKey(String kind, int position) {
    }

    private record SpawnRow(
            String kind,
            int position,
            UUID playerId,
            boolean completed,
            NewPlayerSpawnLocation location
    ) {
    }

    @FunctionalInterface
    private interface SqlWork {

        void run(Connection connection) throws Exception;
    }

    public record Status(
            StorageBackend backend,
            boolean healthy,
            int schemaVersion,
            long latencyMillis,
            int activeConnections,
            int idleConnections,
            int waitingThreads,
            String problem
    ) {
    }

    public record Verification(boolean healthy, List<String> problems) {
    }

    /**
     * A verification together with the snapshot it read, absent only when the
     * snapshot read itself was what failed.
     */
    public record Inspection(Verification verification, Optional<StorageSnapshot> snapshot) {
    }

    public record MaintenancePreview(
            int expiredDeathMarkers,
            int orphanHomeShares,
            int orphanHomes,
            int orphanHints,
            int orphanNotifications,
            int orphanMailBlocks,
            int orphanProfileTrusted,
            int orphanLockBlocks,
            int orphanLockTrust,
            int emptyLocks,
            int invalidSpawnLocations,
            int oldMail
    ) {
        public int total() {
            return expiredDeathMarkers + orphanHomeShares + orphanHomes + orphanHints
                    + orphanNotifications + orphanMailBlocks + orphanProfileTrusted + orphanLockBlocks
                    + orphanLockTrust + emptyLocks + invalidSpawnLocations + oldMail;
        }
    }

    public record MaintenanceResult(
            MaintenancePreview preview,
            MaintenancePreview removed,
            Instant completedAt
    ) {
    }

    @FunctionalInterface
    public interface SnapshotLease extends AutoCloseable {

        @Override
        void close();
    }
}

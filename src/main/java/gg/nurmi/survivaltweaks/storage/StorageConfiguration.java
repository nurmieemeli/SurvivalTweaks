package gg.nurmi.survivaltweaks.storage;

import org.bukkit.configuration.Configuration;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

public record StorageConfiguration(
        StorageBackend backend,
        Path sqliteFile,
        String host,
        int port,
        String database,
        String postgresqlSchema,
        String username,
        String password,
        boolean ssl,
        String postgresqlSslMode,
        int poolSize,
        long connectionTimeoutMillis,
        int socketTimeoutSeconds,
        int queryTimeoutSeconds
) {

    private static final String SIMPLE_DATABASE = "[A-Za-z0-9_-]{1,64}";
    private static final String SIMPLE_SCHEMA = "[A-Za-z_][A-Za-z0-9_]{0,62}";
    private static final java.util.Set<String> POSTGRESQL_SSL_MODES = java.util.Set.of(
            "disable", "allow", "prefer", "require", "verify-ca", "verify-full"
    );

    public StorageConfiguration {
        Objects.requireNonNull(backend, "backend");
        sqliteFile = Objects.requireNonNull(sqliteFile, "sqliteFile")
                .toAbsolutePath()
                .normalize();
        host = Objects.requireNonNullElse(host, "").strip();
        database = Objects.requireNonNullElse(database, "").strip();
        postgresqlSchema = Objects.requireNonNullElse(postgresqlSchema, "survivaltweaks").strip();
        username = Objects.requireNonNullElse(username, "").strip();
        password = Objects.requireNonNullElse(password, "");
        postgresqlSslMode = Objects.requireNonNullElse(postgresqlSslMode, "verify-full")
                .strip().toLowerCase(Locale.ROOT);
        if (poolSize < 1 || poolSize > 16) {
            throw new IllegalArgumentException("storage.remote.pool-size must be between 1 and 16");
        }
        if (connectionTimeoutMillis < 250 || connectionTimeoutMillis > 60_000) {
            throw new IllegalArgumentException(
                    "storage.remote.connection-timeout-millis must be between 250 and 60000"
            );
        }
        if (socketTimeoutSeconds < 1 || socketTimeoutSeconds > 600) {
            throw new IllegalArgumentException(
                    "storage.remote.socket-timeout-seconds must be between 1 and 600"
            );
        }
        if (queryTimeoutSeconds < 1 || queryTimeoutSeconds > 600) {
            throw new IllegalArgumentException(
                    "storage.remote.query-timeout-seconds must be between 1 and 600"
            );
        }
        if (backend != StorageBackend.SQLITE) {
            if (host.isBlank() || host.contains("/") || host.contains("\\")) {
                throw new IllegalArgumentException("storage.remote.host is invalid");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("storage.remote.port must be between 1 and 65535");
            }
            if (!database.matches(SIMPLE_DATABASE)) {
                throw new IllegalArgumentException(
                        "storage.remote.database may contain only letters, numbers, underscore, and hyphen"
                );
            }
            if (username.isBlank()) {
                throw new IllegalArgumentException("storage.remote.username must not be blank");
            }
            if (backend == StorageBackend.POSTGRESQL) {
                if (!postgresqlSchema.matches(SIMPLE_SCHEMA)) {
                    throw new IllegalArgumentException(
                            "storage.remote.postgresql-schema must be a simple PostgreSQL identifier"
                    );
                }
                if (!POSTGRESQL_SSL_MODES.contains(postgresqlSslMode)) {
                    throw new IllegalArgumentException(
                            "storage.remote.postgresql-ssl-mode is invalid"
                    );
                }
            }
        }
    }

    public static StorageConfiguration load(Configuration config, Path dataFolder) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(dataFolder, "dataFolder");
        String configured = config.getString("storage.backend", "auto").strip().toLowerCase(Locale.ROOT);
        String remoteType = config.getString("storage.remote.type", "").strip();
        StorageBackend selected;
        if (configured.equals("auto")) {
            selected = remoteType.isBlank()
                    ? StorageBackend.SQLITE
                    : StorageBackend.parse(remoteType);
        } else {
            selected = StorageBackend.parse(configured);
        }
        if (selected != StorageBackend.SQLITE
                && !remoteType.isBlank()
                && selected != StorageBackend.parse(remoteType)) {
            throw new IllegalArgumentException(
                    "storage.backend and storage.remote.type select different database engines"
            );
        }
        String sqliteName = config.getString("storage.sqlite.file", "survivaltweaks.db").strip();
        Path sqliteFile = Path.of(sqliteName);
        if (sqliteFile.isAbsolute() || sqliteName.contains("..")) {
            throw new IllegalArgumentException(
                    "storage.sqlite.file must stay inside the SurvivalTweaks data folder"
            );
        }
        StorageBackend remoteBackend = remoteType.isBlank()
                ? selected
                : StorageBackend.parse(remoteType);
        int defaultPort = remoteBackend == StorageBackend.MYSQL ? 3306 : 5432;
        int configuredPort = config.getInt("storage.remote.port", 0);
        return new StorageConfiguration(
                selected,
                dataFolder.resolve(sqliteFile),
                config.getString("storage.remote.host", "localhost"),
                configuredPort > 0 ? configuredPort : defaultPort,
                config.getString("storage.remote.database", "survivaltweaks"),
                config.getString("storage.remote.postgresql-schema", "survivaltweaks"),
                config.getString("storage.remote.username", "survivaltweaks"),
                config.getString("storage.remote.password", ""),
                config.getBoolean("storage.remote.ssl", true),
                config.getString("storage.remote.postgresql-ssl-mode", "verify-full"),
                config.getInt("storage.remote.pool-size", 4),
                config.getLong("storage.remote.connection-timeout-millis", 5_000),
                config.getInt("storage.remote.socket-timeout-seconds", 30),
                config.getInt("storage.remote.query-timeout-seconds", 30)
        );
    }

    public StorageConfiguration forBackend(StorageBackend requested) {
        return new StorageConfiguration(
                requested,
                sqliteFile,
                host,
                requested == StorageBackend.SQLITE
                        ? port
                        : portFor(requested),
                database,
                postgresqlSchema,
                username,
                password,
                ssl,
                postgresqlSslMode,
                poolSize,
                connectionTimeoutMillis,
                socketTimeoutSeconds,
                queryTimeoutSeconds
        );
    }

    public String jdbcUrl() {
        return switch (backend) {
            case SQLITE -> "jdbc:sqlite:" + sqliteFile;
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=UTC&useSSL=" + ssl
                    + "&requireSSL=" + ssl;
        };
    }

    public String driverClassName() {
        return switch (backend) {
            case SQLITE -> "org.sqlite.JDBC";
            case POSTGRESQL -> "org.postgresql.Driver";
            case MYSQL -> "com.mysql.cj.jdbc.Driver";
        };
    }

    public String endpointFingerprint() {
        String endpoint = backend == StorageBackend.SQLITE
                ? backend.key() + "|" + sqliteFile
                : backend.key() + "|" + host.toLowerCase(Locale.ROOT) + "|" + port + "|" + database
                        + (backend == StorageBackend.POSTGRESQL ? "|" + postgresqlSchema : "");
        return fingerprint(endpoint);
    }

    public String legacyEndpointFingerprint() {
        String endpoint = backend == StorageBackend.SQLITE
                ? backend.key() + "|" + sqliteFile
                : backend.key() + "|" + host.toLowerCase(Locale.ROOT) + "|" + port + "|" + database;
        return fingerprint(endpoint);
    }

    private static String fingerprint(String endpoint) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(endpoint.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private int portFor(StorageBackend requested) {
        if (backend == requested || backend == StorageBackend.SQLITE) {
            return port > 0 ? port : requested == StorageBackend.MYSQL ? 3306 : 5432;
        }
        return requested == StorageBackend.MYSQL ? 3306 : 5432;
    }
}

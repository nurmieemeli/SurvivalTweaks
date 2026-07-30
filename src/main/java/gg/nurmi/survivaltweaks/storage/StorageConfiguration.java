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
        String username,
        String password,
        boolean ssl,
        int poolSize,
        long connectionTimeoutMillis
) {

    private static final String SIMPLE_DATABASE = "[A-Za-z0-9_-]{1,64}";

    public StorageConfiguration {
        Objects.requireNonNull(backend, "backend");
        sqliteFile = Objects.requireNonNull(sqliteFile, "sqliteFile")
                .toAbsolutePath()
                .normalize();
        host = Objects.requireNonNullElse(host, "").strip();
        database = Objects.requireNonNullElse(database, "").strip();
        username = Objects.requireNonNullElse(username, "").strip();
        password = Objects.requireNonNullElse(password, "");
        if (poolSize < 1 || poolSize > 16) {
            throw new IllegalArgumentException("storage.remote.pool-size must be between 1 and 16");
        }
        if (connectionTimeoutMillis < 250 || connectionTimeoutMillis > 60_000) {
            throw new IllegalArgumentException(
                    "storage.remote.connection-timeout-millis must be between 250 and 60000"
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
                config.getString("storage.remote.username", "survivaltweaks"),
                config.getString("storage.remote.password", ""),
                config.getBoolean("storage.remote.ssl", true),
                config.getInt("storage.remote.pool-size", 4),
                config.getLong("storage.remote.connection-timeout-millis", 5_000)
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
                username,
                password,
                ssl,
                poolSize,
                connectionTimeoutMillis
        );
    }

    public String jdbcUrl() {
        return switch (backend) {
            case SQLITE -> "jdbc:sqlite:" + sqliteFile;
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + database
                    + (ssl ? "?sslmode=require" : "?sslmode=disable");
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
                : backend.key() + "|" + host.toLowerCase(Locale.ROOT) + "|" + port + "|" + database;
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

package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.NewPlayerSpawnState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresStorageIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void postgresqlRoundTripReplacementGuardAndLeaseAreLive() throws Exception {
        String host = environment("SURVIVALTWEAKS_POSTGRES_HOST");
        Assumptions.assumeTrue(host != null, "PostgreSQL integration environment is not configured");
        int port = Integer.parseInt(environment("SURVIVALTWEAKS_POSTGRES_PORT", "5432"));
        String database = environment("SURVIVALTWEAKS_POSTGRES_DATABASE", "survivaltweaks_test");
        String username = environment("SURVIVALTWEAKS_POSTGRES_USERNAME", "survivaltweaks");
        String password = environment("SURVIVALTWEAKS_POSTGRES_PASSWORD", "survivaltweaks");
        String schema = "st_test_" + UUID.randomUUID().toString().replace("-", "");
        StorageConfiguration configuration = new StorageConfiguration(
                StorageBackend.POSTGRESQL,
                directory.resolve("unused.db"),
                host,
                port,
                database,
                schema,
                username,
                password,
                false,
                "disable",
                2,
                5_000,
                15,
                15
        );
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database
                + "?sslmode=disable";
        Logger logger = Logger.getLogger(PostgresStorageIntegrationTest.class.getName());
        try {
            UUID instanceId = UUID.randomUUID();
            UUID awaiting = UUID.randomUUID();
            try (SqlStorage first = new SqlStorage(configuration, logger)) {
                first.ensureInstanceId(instanceId);
                first.acquireInstanceLease();
                first.saveSpawnState(new NewPlayerSpawnState(
                        List.of(),
                        Map.of(),
                        List.of(),
                        Set.of(awaiting)
                ));

                assertFalse(first.isEmpty());
                StorageSnapshot exported = first.exportSnapshot();
                assertEquals(1, exported.counts().replacementSpawns());
                assertEquals(Set.of(awaiting), exported.newPlayerSpawns().awaitingReplacement());
                assertTrue(first.verify().healthy());

                try (SqlStorage second = new SqlStorage(configuration, logger)) {
                    second.ensureInstanceId(instanceId);
                    IOException failure = assertThrows(
                            IOException.class,
                            second::acquireInstanceLease
                    );
                    assertTrue(failure.getMessage().contains("already using"));
                }
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(
                    jdbcUrl,
                    username,
                    password
            ); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String environment(String name, String fallback) {
        String value = environment(name);
        return value == null ? fallback : value;
    }
}

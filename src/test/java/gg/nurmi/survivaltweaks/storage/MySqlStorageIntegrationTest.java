package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.NewPlayerSpawnState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlStorageIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void mysqlRoundTripReplacementGuardAndLeaseAreLive() throws Exception {
        String host = environment("SURVIVALTWEAKS_MYSQL_HOST");
        Assumptions.assumeTrue(host != null, "MySQL integration environment is not configured");
        StorageConfiguration configuration = new StorageConfiguration(
                StorageBackend.MYSQL,
                directory.resolve("unused.db"),
                host,
                Integer.parseInt(environment("SURVIVALTWEAKS_MYSQL_PORT", "3306")),
                environment("SURVIVALTWEAKS_MYSQL_DATABASE", "survivaltweaks_test"),
                "survivaltweaks",
                environment("SURVIVALTWEAKS_MYSQL_USERNAME", "survivaltweaks"),
                environment("SURVIVALTWEAKS_MYSQL_PASSWORD", "survivaltweaks"),
                false,
                "disable",
                2,
                5_000,
                15,
                15
        );
        Logger logger = Logger.getLogger(MySqlStorageIntegrationTest.class.getName());
        UUID instanceId = UUID.randomUUID();
        UUID awaiting = UUID.randomUUID();
        try (SqlStorage first = new SqlStorage(configuration, logger)) {
            first.ensureInstanceId(instanceId);
            first.acquireInstanceLease();
            first.saveSpawnState(new NewPlayerSpawnState(
                    List.of(), Map.of(), List.of(), Set.of(awaiting)
            ));

            assertFalse(first.isEmpty());
            StorageSnapshot exported = first.exportSnapshot();
            assertEquals(1, exported.counts().replacementSpawns());
            assertEquals(Set.of(awaiting), exported.newPlayerSpawns().awaitingReplacement());
            assertTrue(first.verify().healthy());

            try (SqlStorage second = new SqlStorage(configuration, logger)) {
                second.ensureInstanceId(instanceId);
                IOException failure = assertThrows(IOException.class, second::acquireInstanceLease);
                assertTrue(failure.getMessage().contains("already using"));
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

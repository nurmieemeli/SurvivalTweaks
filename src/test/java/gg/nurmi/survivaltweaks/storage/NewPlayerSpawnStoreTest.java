package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.NewPlayerSpawnAssignment;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnLocation;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.logging.Logger;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewPlayerSpawnStoreTest {

    @TempDir
    Path temporary;

    @Test
    void roundTripsPreparedAndAssignedLocationsAtomically() throws IOException {
        Path file = temporary.resolve("new-player-spawns.yml");
        NewPlayerSpawnStore store = new NewPlayerSpawnStore(file, quietLogger());
        UUID worldId = UUID.randomUUID();
        UUID completedPlayer = UUID.randomUUID();
        UUID pendingPlayer = UUID.randomUUID();
        NewPlayerSpawnLocation available = location(worldId, 100, 70, 200);
        NewPlayerSpawnLocation completed = location(worldId, 500, 80, 600);
        NewPlayerSpawnLocation pending = location(worldId, -500, 65, -600);

        NewPlayerSpawnLocation retired = location(worldId, 900, 75, -900);
        store.save(new NewPlayerSpawnState(
                List.of(available),
                Map.of(
                        completedPlayer,
                        new NewPlayerSpawnAssignment(completedPlayer, completed, true),
                        pendingPlayer,
                        new NewPlayerSpawnAssignment(pendingPlayer, pending, false)
                ),
                List.of(retired),
                Set.of(pendingPlayer)
        ));

        NewPlayerSpawnState loaded = store.load();
        assertEquals(List.of(available), loaded.available());
        assertEquals(2, loaded.assignments().size());
        assertTrue(loaded.assignments().get(completedPlayer).completed());
        assertFalse(loaded.assignments().get(pendingPlayer).completed());
        assertEquals(List.of(retired), loaded.retired());
        assertEquals(Set.of(pendingPlayer), loaded.awaitingReplacement());
        assertTrue(Files.readString(file).contains("schema-version: 2"));
    }

    @Test
    void skipsMalformedEntriesWithoutDiscardingValidOnes() throws IOException {
        Path file = temporary.resolve("new-player-spawns.yml");
        UUID worldId = UUID.randomUUID();
        Files.writeString(file, """
                schema-version: 1
                available:
                  - { world-uuid: "%s", world: world, x: 10, y: 70, z: 20, yaw: 90.0 }
                  - { world: broken }
                assignments:
                  - { player: nope, completed: true, location: {} }
                """.formatted(worldId));

        NewPlayerSpawnState loaded = new NewPlayerSpawnStore(
                file,
                quietLogger()
        ).load();

        assertEquals(1, loaded.available().size());
        assertTrue(loaded.assignments().isEmpty());
        assertTrue(loaded.retired().isEmpty());
        assertTrue(loaded.awaitingReplacement().isEmpty());
    }

    private NewPlayerSpawnLocation location(UUID worldId, int x, int y, int z) {
        return new NewPlayerSpawnLocation(worldId, "world", x, y, z, 45.0f);
    }

    private Logger quietLogger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        return logger;
    }
}

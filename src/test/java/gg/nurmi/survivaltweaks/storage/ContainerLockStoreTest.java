package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.object.ContainerLockSnapshot;
import gg.nurmi.survivaltweaks.object.LockAccessMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerLockStoreTest {

    @TempDir
    Path directory;

    @Test
    void roundTripsDoubleContainerCoordinatesAndAcl() throws IOException {
        Path file = directory.resolve("locked-containers.yml");
        ContainerLockStore store = new ContainerLockStore(
                file,
                Logger.getLogger(ContainerLockStoreTest.class.getName())
        );
        UUID worldId = UUID.randomUUID();
        ContainerLockSnapshot lock = new ContainerLockSnapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Set.of(
                        new BlockKey(worldId, 10, 64, 20),
                        new BlockKey(worldId, 11, 64, 20)
                ),
                Set.of(UUID.randomUUID(), UUID.randomUUID()),
                "Workshop",
                LockAccessMode.PUBLIC,
                true
        );

        store.save(List.of(lock));

        assertEquals(List.of(lock), store.load());
        assertTrue(Files.readString(file).contains("schema-version: 3"));
        assertTrue(store.load().getFirst().automationAllowed());
    }
}

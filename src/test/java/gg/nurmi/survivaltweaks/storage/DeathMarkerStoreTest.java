package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.DeathMarker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeathMarkerStoreTest {

    @TempDir
    Path directory;

    @Test
    void roundTripsCrossWorldMarkerAndExpiry() throws Exception {
        DeathMarker marker = new DeathMarker(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "world_the_end",
                12.5,
                70,
                -8.5,
                Instant.parse("2026-07-27T10:00:00Z"),
                Instant.parse("2026-07-27T11:00:00Z"),
                "FALL"
        );
        DeathMarkerStore store = new DeathMarkerStore(
                directory.resolve("death-markers.yml"),
                Logger.getAnonymousLogger()
        );

        store.save(List.of(marker));

        assertEquals(List.of(marker), store.load());
    }
}

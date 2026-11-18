package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.config.StrictYaml;
import gg.nurmi.survivaltweaks.object.DeathMarker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
    void readsLegacyCrossWorldMarkerAndExpiry() throws Exception {
        DeathMarker marker = new DeathMarker(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "world_the_end",
                12.5,
                70,
                -8.5,
                Instant.parse("2026-07-27T10:00:00Z"),
                Instant.parse("2026-07-27T11:00:00Z"),
                "FALL"
        );
        Path file = directory.resolve("death-markers.yml");
        Files.writeString(file, """
                schema-version: 1
                markers:
                  - player: 00000000-0000-0000-0000-000000000001
                    world-uuid: 00000000-0000-0000-0000-000000000002
                    world: world_the_end
                    x: 12.5
                    y: 70
                    z: -8.5
                    created-at: "2026-07-27T10:00:00Z"
                    expires-at: "2026-07-27T11:00:00Z"
                    cause: FALL
                """);

        DeathMarkerStore reader = new DeathMarkerStore(Logger.getAnonymousLogger());
        assertEquals(List.of(marker), reader.load(StrictYaml.load(file)));
    }
}

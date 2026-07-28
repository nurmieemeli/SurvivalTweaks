package gg.nurmi.survivaltweaks.object;

import java.util.Objects;
import java.util.UUID;

public record NewPlayerSpawnAssignment(
        UUID playerId,
        NewPlayerSpawnLocation location,
        boolean completed
) {

    public NewPlayerSpawnAssignment {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(location, "location");
    }

    public NewPlayerSpawnAssignment complete() {
        return completed ? this : new NewPlayerSpawnAssignment(playerId, location, true);
    }
}

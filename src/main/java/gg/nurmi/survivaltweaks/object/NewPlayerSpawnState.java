package gg.nurmi.survivaltweaks.object;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record NewPlayerSpawnState(
        List<NewPlayerSpawnLocation> available,
        Map<UUID, NewPlayerSpawnAssignment> assignments,
        List<NewPlayerSpawnLocation> retired,
        Set<UUID> awaitingReplacement
) {

    public static final NewPlayerSpawnState EMPTY =
            new NewPlayerSpawnState(List.of(), Map.of(), List.of(), Set.of());

    public NewPlayerSpawnState {
        available = List.copyOf(available);
        assignments = Map.copyOf(assignments);
        retired = List.copyOf(retired);
        awaitingReplacement = Set.copyOf(awaitingReplacement);
    }
}

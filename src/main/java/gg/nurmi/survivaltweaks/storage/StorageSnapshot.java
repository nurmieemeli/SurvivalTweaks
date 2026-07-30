package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.ContainerLockSnapshot;
import gg.nurmi.survivaltweaks.object.DeathMarker;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnState;
import gg.nurmi.survivaltweaks.object.ProfileSnapshot;

import java.util.List;

public record StorageSnapshot(
        List<ProfileSnapshot> profiles,
        List<ContainerLockSnapshot> locks,
        List<DeathMarker> deathMarkers,
        NewPlayerSpawnState newPlayerSpawns
) {

    public StorageSnapshot {
        profiles = List.copyOf(profiles);
        locks = List.copyOf(locks);
        deathMarkers = List.copyOf(deathMarkers);
        newPlayerSpawns = newPlayerSpawns == null
                ? NewPlayerSpawnState.EMPTY
                : newPlayerSpawns;
    }

    public Counts counts() {
        int homes = profiles.stream().mapToInt(profile -> profile.homes().size()).sum();
        int notifications = profiles.stream()
                .mapToInt(profile -> profile.notifications().size())
                .sum();
        return new Counts(
                profiles.size(),
                homes,
                notifications,
                locks.size(),
                deathMarkers.size(),
                newPlayerSpawns.available().size(),
                newPlayerSpawns.assignments().size(),
                newPlayerSpawns.retired().size()
        );
    }

    public boolean empty() {
        return counts().total() == 0;
    }

    public record Counts(
            int profiles,
            int homes,
            int notifications,
            int locks,
            int deathMarkers,
            int preparedSpawns,
            int assignedSpawns,
            int retiredSpawns
    ) {

        public int total() {
            return profiles + homes + notifications + locks + deathMarkers
                    + preparedSpawns + assignedSpawns + retiredSpawns;
        }
    }
}

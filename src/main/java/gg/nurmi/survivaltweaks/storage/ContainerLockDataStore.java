package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.ContainerLockSnapshot;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

public interface ContainerLockDataStore {

    List<ContainerLockSnapshot> loadLocks();

    void saveLocks(Collection<ContainerLockSnapshot> locks) throws IOException;
}

package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.DeathMarker;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

public interface DeathMarkerDataStore {

    List<DeathMarker> loadDeathMarkers();

    void saveDeathMarkers(Collection<DeathMarker> markers) throws IOException;
}

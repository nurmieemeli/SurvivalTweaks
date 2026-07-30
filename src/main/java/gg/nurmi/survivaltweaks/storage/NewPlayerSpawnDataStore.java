package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.NewPlayerSpawnState;

import java.io.IOException;

public interface NewPlayerSpawnDataStore {

    NewPlayerSpawnState loadSpawnState();

    void saveSpawnState(NewPlayerSpawnState state) throws IOException;
}

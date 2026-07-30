package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.object.ProfileSnapshot;

import java.io.IOException;
import java.util.UUID;

public interface ProfileDataStore {

    Profile load(UUID uniqueId);

    void save(ProfileSnapshot snapshot) throws IOException;
}

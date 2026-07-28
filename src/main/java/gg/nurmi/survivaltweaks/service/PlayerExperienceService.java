package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.PlayerPreferences;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;

public final class PlayerExperienceService {

    private final ProfileRepository profiles;
    private final ConcurrentMap<UUID, PlayerPreferences> preferences = new ConcurrentHashMap<>();

    public PlayerExperienceService(ProfileRepository profiles) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    public PlayerPreferences preferences(UUID playerId) {
        return preferences.computeIfAbsent(playerId, id -> profiles.load(id).preferences());
    }

    public PlayerPreferences preferences(Player player) {
        return preferences(player.getUniqueId());
    }

    public PlayerPreferences update(Player player, UnaryOperator<PlayerPreferences> update) {
        var profile = profiles.load(player.getUniqueId());
        PlayerPreferences updated = Objects.requireNonNull(
                update.apply(profile.preferences()),
                "updated preferences"
        );
        profile.preferences(updated);
        preferences.put(player.getUniqueId(), updated);
        profiles.save(profile);
        return updated;
    }

    public void prime(UUID playerId) {
        preferences.put(playerId, profiles.load(playerId).preferences());
    }

    public void forget(UUID playerId) {
        preferences.remove(playerId);
    }

    public boolean actionBars(Player player) {
        return preferences(player).actionBarEnabled();
    }

    public boolean dialogs(Player player) {
        return preferences(player).dialogsEnabled();
    }
}

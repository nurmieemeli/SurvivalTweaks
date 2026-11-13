package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.object.PlayerPreferences;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;

public final class PlayerExperienceService {

    private final ProfileRepository profiles;
    private final ConcurrentMap<UUID, Profile> activeProfiles = new ConcurrentHashMap<>();

    public PlayerExperienceService(ProfileRepository profiles) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    public PlayerPreferences preferences(UUID playerId) {
        return activeProfiles.computeIfAbsent(playerId, profiles::load).preferences();
    }

    public PlayerPreferences preferences(Player player) {
        return preferences(player.getUniqueId());
    }

    public PlayerPreferences update(Player player, UnaryOperator<PlayerPreferences> update) {
        var profile = profiles.load(player.getUniqueId());
        synchronized (profile) {
            PlayerPreferences updated = Objects.requireNonNull(
                    update.apply(profile.preferences()),
                    "updated preferences"
            );
            profile.preferences(updated);
            activeProfiles.put(player.getUniqueId(), profile);
            profiles.save(profile);
            return updated;
        }
    }

    public Optional<CompletableFuture<Void>> cycleLanguage(UUID playerId) {
        if (!profiles.languageRecoveryReady()) {
            return Optional.empty();
        }
        var profile = profiles.load(playerId);
        synchronized (profile) {
            var saved = profile.stageLanguage(profile.preferences().language().next());
            if (saved.isPresent()) {
                activeProfiles.put(playerId, profile);
                profiles.save(profile);
            }
            return saved;
        }
    }

    public void prime(UUID playerId) {
        activeProfiles.put(playerId, profiles.load(playerId));
    }

    public void forget(UUID playerId) {
        activeProfiles.remove(playerId);
    }

    public boolean actionBars(Player player) {
        return preferences(player).actionBarEnabled();
    }

    public boolean dialogs(Player player) {
        return preferences(player).dialogsEnabled();
    }
}

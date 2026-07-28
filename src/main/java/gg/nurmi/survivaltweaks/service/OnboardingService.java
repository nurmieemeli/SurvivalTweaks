package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.OnboardingHint;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public final class OnboardingService {

    private final ProfileRepository profiles;
    private final MessageService messages;
    private volatile Function<UUID, Boolean> guidanceEnabled = ignored -> true;

    public OnboardingService(ProfileRepository profiles, MessageService messages) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    public boolean show(Player player, OnboardingHint hint) {
        var profile = profiles.load(player.getUniqueId());
        if (!profile.markHintSeen(hint)) {
            return false;
        }
        profiles.save(profile);
        if (guidanceEnabled(player.getUniqueId())) {
            messages.send(player, "onboarding." + hint.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        return true;
    }

    public boolean complete(Player player, OnboardingHint hint) {
        var profile = profiles.load(player.getUniqueId());
        if (!profile.markHintSeen(hint)) {
            return false;
        }
        profiles.save(profile);
        return true;
    }

    public boolean completed(Player player, OnboardingHint hint) {
        return profiles.load(player.getUniqueId()).hintSeen(hint);
    }

    public void guidancePreference(Function<UUID, Boolean> provider) {
        guidanceEnabled = Objects.requireNonNull(provider, "provider");
    }

    public boolean guidanceEnabled(UUID playerId) {
        return guidanceEnabled.apply(playerId);
    }
}

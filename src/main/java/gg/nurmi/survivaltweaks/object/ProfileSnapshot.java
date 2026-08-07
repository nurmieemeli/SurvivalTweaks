package gg.nurmi.survivaltweaks.object;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ProfileSnapshot(
        UUID uniqueId,
        List<Home> homes,
        PlayerPreferences preferences,
        Set<OnboardingHint> seenHints,
        List<PlayerNotification> notifications,
        String lastKnownName,
        Instant lastSeenAt,
        long playTimeTicks,
        Set<UUID> blockedMailSenders,
        Set<UUID> trustedPlayers
) {

    public ProfileSnapshot(UUID uniqueId, List<Home> homes) {
        this(uniqueId, homes, PlayerPreferences.DEFAULTS, Set.of(), List.of(), "", null, 0, Set.of(), Set.of());
    }

    public ProfileSnapshot {
        Objects.requireNonNull(uniqueId, "uniqueId");
        homes = List.copyOf(homes);
        preferences = Objects.requireNonNull(preferences, "preferences");
        seenHints = Set.copyOf(seenHints);
        notifications = List.copyOf(notifications);
        lastKnownName = lastKnownName == null ? "" : lastKnownName.strip();
        playTimeTicks = Math.max(0, playTimeTicks);
        blockedMailSenders = Set.copyOf(blockedMailSenders);
        trustedPlayers = Set.copyOf(trustedPlayers);
    }
}

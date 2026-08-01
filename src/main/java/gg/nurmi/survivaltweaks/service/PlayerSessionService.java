package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.Profile;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owns the persisted identity and timing boundary of an online player session. */
public final class PlayerSessionService implements AutoCloseable {

    private final ProfileRepository profiles;
    private final Clock clock;
    private final Map<UUID, Instant> previousSeen = new ConcurrentHashMap<>();

    public PlayerSessionService(ProfileRepository profiles, Clock clock) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Session begin(Player player) {
        Objects.requireNonNull(player, "player");
        Profile profile = profiles.load(player.getUniqueId());
        Instant lastSeen = profile.lastSeenAt().orElse(null);
        if (lastSeen == null) {
            previousSeen.remove(player.getUniqueId());
        } else {
            previousSeen.put(player.getUniqueId(), lastSeen);
        }
        updateIdentity(profile, player);
        profiles.save(profile);
        return new Session(!player.hasPlayedBefore(), Optional.ofNullable(lastSeen));
    }

    public void end(Player player) {
        Objects.requireNonNull(player, "player");
        Profile profile = profiles.load(player.getUniqueId());
        updateIdentity(profile, player);
        profile.lastSeenAt(clock.instant());
        profiles.save(profile);
        previousSeen.remove(player.getUniqueId());
    }

    public Optional<Instant> previousSeen(UUID playerId) {
        return Optional.ofNullable(previousSeen.get(playerId));
    }

    public Duration awayDuration(UUID playerId) {
        Instant now = clock.instant();
        Instant previous = previousSeen.get(playerId);
        if (previous == null || previous.isAfter(now)) {
            return Duration.ZERO;
        }
        return Duration.between(previous, now);
    }

    @Override
    public void close() {
        previousSeen.clear();
    }

    private void updateIdentity(Profile profile, Player player) {
        profile.lastKnownName(player.getName());
        profile.playTimeTicks(player.getStatistic(Statistic.PLAY_ONE_MINUTE));
    }

    public record Session(boolean firstJoin, Optional<Instant> previousSeen) {

        public Session {
            Objects.requireNonNull(previousSeen, "previousSeen");
        }
    }
}

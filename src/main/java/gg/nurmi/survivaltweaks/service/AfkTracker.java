package gg.nurmi.survivaltweaks.service;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AfkTracker {

    private static final long MOVEMENT_SAMPLE_MILLIS = 1_000L;

    private final Clock clock;
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final Set<UUID> afkPlayers = ConcurrentHashMap.newKeySet();

    public AfkTracker(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void joined(UUID playerId) {
        lastActivity.put(playerId, clock.millis());
        afkPlayers.remove(playerId);
    }

    public void left(UUID playerId) {
        lastActivity.remove(playerId);
        afkPlayers.remove(playerId);
    }

    public boolean activity(UUID playerId) {
        lastActivity.put(playerId, clock.millis());
        return afkPlayers.remove(playerId);
    }

    public boolean movementActivity(UUID playerId) {
        long now = clock.millis();
        if (afkPlayers.remove(playerId)) {
            lastActivity.put(playerId, now);
            return true;
        }
        Long previous = lastActivity.get(playerId);
        if (previous == null || now - previous >= MOVEMENT_SAMPLE_MILLIS) {
            lastActivity.put(playerId, now);
        }
        return false;
    }

    public State toggle(UUID playerId) {
        lastActivity.put(playerId, clock.millis());
        if (afkPlayers.remove(playerId)) {
            return State.ACTIVE;
        }
        afkPlayers.add(playerId);
        return State.AFK;
    }

    public boolean updateAutomatic(Collection<UUID> onlinePlayers, Duration timeout) {
        return !newlyAutomaticAfk(onlinePlayers, timeout).isEmpty();
    }

    public Set<UUID> newlyAutomaticAfk(Collection<UUID> onlinePlayers, Duration timeout) {
        long now = clock.millis();
        long timeoutMillis = timeout.toMillis();
        Set<UUID> changed = new LinkedHashSet<>();
        for (UUID playerId : onlinePlayers) {
            long lastSeen = lastActivity.computeIfAbsent(playerId, ignored -> now);
            if (now - lastSeen >= timeoutMillis && afkPlayers.add(playerId)) {
                changed.add(playerId);
            }
        }
        return Set.copyOf(changed);
    }

    public boolean isAfk(UUID playerId) {
        return afkPlayers.contains(playerId);
    }

    public int count() {
        return afkPlayers.size();
    }

    public enum State {
        ACTIVE,
        AFK
    }
}

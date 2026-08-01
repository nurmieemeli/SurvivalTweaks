package gg.nurmi.survivaltweaks.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class PersistenceMonitor {

    private final Clock clock;
    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();

    public PersistenceMonitor() {
        this(Clock.systemUTC());
    }

    PersistenceMonitor(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void queued(String name, int pending) {
        Lane lane = lane(name);
        synchronized (lane) {
            lane.pending = Math.max(0, pending);
            if (lane.pending > 0 && lane.queuedAt == null) {
                lane.queuedAt = clock.instant();
            } else if (lane.pending == 0 && !lane.active) {
                lane.queuedAt = null;
            }
        }
    }

    void started(String name, int pending) {
        Lane lane = lane(name);
        synchronized (lane) {
            lane.active = true;
            lane.pending = Math.max(0, pending);
            if (lane.queuedAt == null) {
                lane.queuedAt = clock.instant();
            }
        }
    }

    void succeeded(String name, int pending) {
        Lane lane = lane(name);
        synchronized (lane) {
            lane.active = false;
            lane.pending = Math.max(0, pending);
            lane.lastSuccess = clock.instant();
            lane.consecutiveFailures = 0;
            if (lane.pending == 0) {
                lane.queuedAt = null;
            }
        }
    }

    void failed(String name, int pending, Throwable failure) {
        Lane lane = lane(name);
        synchronized (lane) {
            lane.active = false;
            lane.pending = Math.max(1, pending);
            lane.lastFailure = clock.instant();
            lane.lastFailureReason = Objects.requireNonNullElse(
                    failure.getMessage(),
                    failure.getClass().getSimpleName()
            );
            lane.consecutiveFailures++;
            if (lane.queuedAt == null) {
                lane.queuedAt = lane.lastFailure;
            }
        }
    }

    public Snapshot snapshot() {
        Instant now = clock.instant();
        Map<String, LaneSnapshot> copied = new LinkedHashMap<>();
        lanes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> copied.put(entry.getKey(), snapshot(entry.getValue(), now)));
        int pending = copied.values().stream().mapToInt(LaneSnapshot::pending).sum();
        int active = (int) copied.values().stream().filter(LaneSnapshot::active).count();
        long oldest = copied.values().stream()
                .mapToLong(LaneSnapshot::queueAgeMillis)
                .max()
                .orElse(0);
        LaneSnapshot newestFailure = copied.values().stream()
                .filter(lane -> lane.lastFailure() != null)
                .max(Comparator.comparing(LaneSnapshot::lastFailure))
                .orElse(null);
        Instant lastSuccess = copied.values().stream()
                .map(LaneSnapshot::lastSuccess)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new Snapshot(
                pending,
                active,
                oldest,
                lastSuccess,
                newestFailure == null ? null : newestFailure.lastFailure(),
                newestFailure == null ? "" : newestFailure.lastFailureReason(),
                Map.copyOf(copied)
        );
    }

    private Lane lane(String name) {
        return lanes.computeIfAbsent(Objects.requireNonNull(name, "name"), ignored -> new Lane());
    }

    private LaneSnapshot snapshot(Lane lane, Instant now) {
        synchronized (lane) {
            long age = lane.queuedAt == null
                    ? 0
                    : Math.max(0, Duration.between(lane.queuedAt, now).toMillis());
            return new LaneSnapshot(
                    lane.pending,
                    lane.active,
                    age,
                    lane.lastSuccess,
                    lane.lastFailure,
                    lane.lastFailureReason,
                    lane.consecutiveFailures
            );
        }
    }

    private static final class Lane {
        private int pending;
        private boolean active;
        private Instant queuedAt;
        private Instant lastSuccess;
        private Instant lastFailure;
        private String lastFailureReason = "";
        private int consecutiveFailures;
    }

    public record Snapshot(
            int pending,
            int active,
            long oldestQueueMillis,
            Instant lastSuccess,
            Instant lastFailure,
            String lastFailureReason,
            Map<String, LaneSnapshot> lanes
    ) {
    }

    public record LaneSnapshot(
            int pending,
            boolean active,
            long queueAgeMillis,
            Instant lastSuccess,
            Instant lastFailure,
            String lastFailureReason,
            int consecutiveFailures
    ) {
    }
}

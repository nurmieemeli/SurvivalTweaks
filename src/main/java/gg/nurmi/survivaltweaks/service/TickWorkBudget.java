package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.SettingsService;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class TickWorkBudget {

    private final SettingsService settings;
    private final PerformanceGovernor governor;
    private final LongSupplier tick;
    private final EnumMap<Lane, Integer> used = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, Integer> deferredThisTick = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, Long> deferredTotal = new EnumMap<>(Lane.class);
    private long activeTick = Long.MIN_VALUE;
    private int limit;
    private int remaining;

    public TickWorkBudget(
            SettingsService settings,
            PerformanceGovernor governor,
            LongSupplier tick
    ) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.governor = Objects.requireNonNull(governor, "governor");
        this.tick = Objects.requireNonNull(tick, "tick");
        for (Lane lane : Lane.values()) {
            used.put(lane, 0);
            deferredThisTick.put(lane, 0);
            deferredTotal.put(lane, 0L);
        }
    }

    public synchronized boolean tryAcquire(Lane lane, int units) {
        Objects.requireNonNull(lane, "lane");
        if (units <= 0) {
            return true;
        }
        refresh();
        int fairShare = Math.max(1, (limit + Lane.values().length - 1) / Lane.values().length);
        if (remaining < units || used.get(lane) + units > fairShare) {
            deferredThisTick.merge(lane, 1, Integer::sum);
            deferredTotal.merge(lane, 1L, Long::sum);
            return false;
        }
        remaining -= units;
        used.merge(lane, units, Integer::sum);
        return true;
    }

    public synchronized int remaining() {
        refresh();
        return remaining;
    }

    public synchronized Snapshot snapshot() {
        refresh();
        return new Snapshot(
                activeTick,
                limit,
                remaining,
                Map.copyOf(used),
                Map.copyOf(deferredThisTick),
                Map.copyOf(deferredTotal)
        );
    }

    private void refresh() {
        long currentTick = tick.getAsLong();
        if (currentTick == activeTick) {
            return;
        }
        activeTick = currentTick;
        limit = Math.max(
                1,
                (int) Math.floor(
                        settings.current().performanceWorkBudgetPerTick()
                                * governor.workScale()
                )
        );
        remaining = limit;
        for (Lane lane : Lane.values()) {
            used.put(lane, 0);
            deferredThisTick.put(lane, 0);
        }
    }

    public enum Lane {
        TREE_FELLING,
        LEAF_DECAY,
        SPAWN_PREPARATION,
        DEATH_GUIDE,
        ATMOSPHERE
    }

    public record Snapshot(
            long tick,
            int limit,
            int remaining,
            Map<Lane, Integer> used,
            Map<Lane, Integer> deferredThisTick,
            Map<Lane, Long> deferredTotal
    ) {

        public int usedTotal() {
            return limit - remaining;
        }
    }
}

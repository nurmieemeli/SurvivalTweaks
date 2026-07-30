package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.SettingsService;

import java.util.Objects;
import java.util.function.LongSupplier;

public final class TickWorkBudget {

    private final SettingsService settings;
    private final PerformanceGovernor governor;
    private final LongSupplier tick;
    private long activeTick = Long.MIN_VALUE;
    private int remaining;

    public TickWorkBudget(
            SettingsService settings,
            PerformanceGovernor governor,
            LongSupplier tick
    ) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.governor = Objects.requireNonNull(governor, "governor");
        this.tick = Objects.requireNonNull(tick, "tick");
    }

    public boolean tryAcquire(int units) {
        if (units <= 0) {
            return true;
        }
        refresh();
        if (remaining < units) {
            return false;
        }
        remaining -= units;
        return true;
    }

    public int remaining() {
        refresh();
        return remaining;
    }

    private void refresh() {
        long currentTick = tick.getAsLong();
        if (currentTick == activeTick) {
            return;
        }
        activeTick = currentTick;
        remaining = Math.max(
                1,
                (int) Math.floor(
                        settings.current().performanceWorkBudgetPerTick()
                                * governor.workScale()
                )
        );
    }
}

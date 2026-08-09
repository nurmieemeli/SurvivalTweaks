package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TickWorkBudgetTest {

    @Test
    void sharesAndRefreshesTheConfiguredBudgetPerServerTick() {
        AtomicLong tick = new AtomicLong(10L);
        TickWorkBudget budget = new TickWorkBudget(
                new SettingsService(settings()),
                governor(),
                tick::get
        );

        for (TickWorkBudget.Lane lane : TickWorkBudget.Lane.values()) {
            assertTrue(budget.tryAcquire(lane, 50));
        }
        assertEquals(6, budget.remaining());
        assertFalse(budget.tryAcquire(TickWorkBudget.Lane.TREE_FELLING, 3));
        assertEquals(
                1,
                budget.snapshot().deferredThisTick()
                        .get(TickWorkBudget.Lane.TREE_FELLING)
        );

        tick.incrementAndGet();
        assertEquals(256, budget.remaining());
    }

    @Test
    void oneLaneCannotConsumeAnotherLanesFairShare() {
        TickWorkBudget budget = new TickWorkBudget(
                new SettingsService(settings()),
                governor(),
                () -> 10L
        );

        assertTrue(budget.tryAcquire(TickWorkBudget.Lane.TREE_FELLING, 52));
        assertFalse(budget.tryAcquire(TickWorkBudget.Lane.TREE_FELLING, 1));
        assertTrue(budget.tryAcquire(TickWorkBudget.Lane.SPAWN_PREPARATION, 8));
    }

    @Test
    void aLaneWhoseRequestExceedsItsShareStillRunsOncePerTick() {
        // The smallest budget the configuration accepts. A fair share is then
        // 7 units, less than the 8 spawn preparation asks for, so a strict
        // share check refused it on every tick and silently stopped the
        // feature on an otherwise healthy server.
        PluginSettings smallBudget = settings("performance.work-budget-per-tick", 32);
        AtomicLong tick = new AtomicLong(10L);
        TickWorkBudget budget = new TickWorkBudget(
                new SettingsService(smallBudget),
                governor(),
                tick::get
        );

        assertTrue(budget.tryAcquire(TickWorkBudget.Lane.SPAWN_PREPARATION, 8));
        // Sharing still applies once the lane has run this tick.
        assertFalse(budget.tryAcquire(TickWorkBudget.Lane.SPAWN_PREPARATION, 8));
        assertTrue(budget.tryAcquire(TickWorkBudget.Lane.TREE_FELLING, 7));

        tick.incrementAndGet();
        assertTrue(budget.tryAcquire(TickWorkBudget.Lane.SPAWN_PREPARATION, 8));
    }

    @Test
    void anOversizedRequestIsStillRefusedWhenTheTickCannotCoverIt() {
        TickWorkBudget budget = new TickWorkBudget(
                new SettingsService(settings("performance.work-budget-per-tick", 32)),
                governor(),
                () -> 10L
        );

        assertTrue(budget.tryAcquire(TickWorkBudget.Lane.TREE_FELLING, 7));
        assertTrue(budget.tryAcquire(TickWorkBudget.Lane.LEAF_DECAY, 7));
        assertTrue(budget.tryAcquire(TickWorkBudget.Lane.DEATH_GUIDE, 7));
        assertTrue(budget.tryAcquire(TickWorkBudget.Lane.ATMOSPHERE, 7));
        assertEquals(4, budget.remaining());
        assertFalse(budget.tryAcquire(TickWorkBudget.Lane.SPAWN_PREPARATION, 8));
    }

    private PerformanceGovernor governor() {
        return new PerformanceGovernor(
                mock(JavaPlugin.class),
                new SettingsService(settings()),
                new TaskFailureIsolation(
                        Logger.getLogger(TickWorkBudgetTest.class.getName()),
                        Clock.systemUTC()
                )
        );
    }

    private PluginSettings settings() {
        return PluginSettings.validate(defaultConfig());
    }

    private PluginSettings settings(String path, Object value) {
        YamlConfiguration config = defaultConfig();
        config.set(path, value);
        return PluginSettings.validate(config);
    }

    private YamlConfiguration defaultConfig() {
        return YamlConfiguration.loadConfiguration(
                new InputStreamReader(
                        getClass().getClassLoader().getResourceAsStream("config.yml"),
                        StandardCharsets.UTF_8
                )
        );
    }
}

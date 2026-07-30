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

        assertTrue(budget.tryAcquire(250));
        assertEquals(6, budget.remaining());
        assertFalse(budget.tryAcquire(7));

        tick.incrementAndGet();
        assertEquals(256, budget.remaining());
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
        return PluginSettings.validate(YamlConfiguration.loadConfiguration(
                new InputStreamReader(
                        getClass().getClassLoader().getResourceAsStream("config.yml"),
                        StandardCharsets.UTF_8
                )
        ));
    }
}

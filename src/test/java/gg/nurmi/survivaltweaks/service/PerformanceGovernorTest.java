package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class PerformanceGovernorTest {

    @Test
    void escalatesImmediatelyAndRecoversWithHysteresis() {
        PerformanceGovernor governor = governor();

        governor.sample(48.0);
        assertEquals(PerformanceGovernor.Level.CRITICAL, governor.level());
        assertEquals(4, governor.cosmeticDivisor());

        for (int sample = 0; sample < 15; sample++) {
            governor.sample(30.0);
        }
        assertEquals(PerformanceGovernor.Level.REDUCED, governor.level());

        for (int sample = 0; sample < 15; sample++) {
            governor.sample(30.0);
        }
        assertEquals(PerformanceGovernor.Level.NORMAL, governor.level());
    }

    @Test
    void reducedLevelScalesWorkWithoutDisablingIt() {
        PerformanceGovernor governor = governor();

        governor.sample(42.0);

        assertEquals(PerformanceGovernor.Level.REDUCED, governor.level());
        assertEquals(0.65, governor.workScale());
        assertEquals(0.6, governor.particleScale());
    }

    private PerformanceGovernor governor() {
        return new PerformanceGovernor(
                mock(JavaPlugin.class),
                new SettingsService(settings()),
                new TaskFailureIsolation(
                        Logger.getLogger(PerformanceGovernorTest.class.getName()),
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

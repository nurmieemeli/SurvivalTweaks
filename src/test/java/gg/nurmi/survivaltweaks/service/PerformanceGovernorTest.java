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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void logsOnlyWhenTheGovernorLevelActuallyChanges() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Logger logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        PerformanceGovernor governor = new PerformanceGovernor(
                plugin,
                new SettingsService(settings()),
                new TaskFailureIsolation(quietLogger(), Clock.systemUTC())
        );

        governor.sample(42.0);
        governor.sample(43.0);
        for (int sample = 0; sample < 15; sample++) {
            governor.sample(30.0);
        }

        verify(logger, times(1)).warning(anyString());
        verify(logger, times(1)).info(anyString());
    }

    private PerformanceGovernor governor() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(quietLogger());
        return new PerformanceGovernor(
                plugin,
                new SettingsService(settings()),
                new TaskFailureIsolation(
                        quietLogger(),
                        Clock.systemUTC()
                )
        );
    }

    private Logger quietLogger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return logger;
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

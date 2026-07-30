package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.SurvivalTweaks;
import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.PlayerPreferences;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AtmosphereServiceTest {

    @Mock
    private SurvivalTweaks plugin;

    @Mock
    private SettingsService settingsService;

    @Mock
    private PluginSettings settings;

    @Mock
    private MessageService messages;

    @Mock
    private ActionBarService actionBars;

    @Mock
    private PlayerExperienceService experience;

    private AtmosphereService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(settingsService.current()).thenReturn(settings);

        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);

        service = new AtmosphereService(plugin, settingsService, messages, actionBars, experience);
    }

    @Test
    void testIsRareMaterial() {
        assertTrue(service.isRareMaterial(Material.DIAMOND));
        assertTrue(service.isRareMaterial(Material.NETHERITE_INGOT));
        assertTrue(service.isRareMaterial(Material.TOTEM_OF_UNDYING));
        assertTrue(service.isRareMaterial(Material.ELYTRA));
        assertFalse(service.isRareMaterial(Material.DIRT));
        assertFalse(service.isRareMaterial(Material.COBBLESTONE));
    }

    @Test
    void sculkWarningRequiresBothGlobalAndPlayerActionBarSettings() {
        Player player = mock(Player.class);
        when(settings.atmosphereSculkWarningActionbar()).thenReturn(true);
        when(settings.actionBarEnabled()).thenReturn(true);
        when(experience.actionBars(player)).thenReturn(true);

        assertTrue(service.sculkWarningEnabled(player));

        when(experience.actionBars(player)).thenReturn(false);
        assertFalse(service.sculkWarningEnabled(player));

        when(experience.actionBars(player)).thenReturn(true);
        when(settings.actionBarEnabled()).thenReturn(false);
        assertFalse(service.sculkWarningEnabled(player));
    }

    @Test
    void particlePreferencesDisableOrReduceAtmosphereEffects() {
        Player player = mock(Player.class);
        when(experience.preferences(player)).thenReturn(
                PlayerPreferences.DEFAULTS.withReducedEffects(true)
        );

        assertTrue(service.particlesEnabled(player));
        assertEquals(3, service.particleCount(player, 10));
        assertEquals(1, service.particleCount(player, 2));

        when(experience.preferences(player)).thenReturn(
                PlayerPreferences.DEFAULTS.withParticles(false)
        );
        assertFalse(service.particlesEnabled(player));
    }
}

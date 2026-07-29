package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.SurvivalTweaks;
import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

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

    private AtmosphereService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(settingsService.current()).thenReturn(settings);

        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);

        service = new AtmosphereService(plugin, settingsService, messages, actionBars);
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
}

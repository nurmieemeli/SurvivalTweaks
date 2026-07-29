package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DecorationProtectionServiceTest {

    @Mock
    private SettingsService settingsService;

    @Mock
    private PluginSettings settings;

    private DecorationProtectionService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(settingsService.current()).thenReturn(settings);
        service = new DecorationProtectionService(settingsService);
    }

    @Test
    void testIsDecorationEntity() {
        ItemFrame frame = mock(ItemFrame.class);
        assertTrue(service.isDecorationEntity(frame));

        GlowItemFrame glowFrame = mock(GlowItemFrame.class);
        assertTrue(service.isDecorationEntity(glowFrame));

        ArmorStand stand = mock(ArmorStand.class);
        assertTrue(service.isDecorationEntity(stand));

        Painting painting = mock(Painting.class);
        assertTrue(service.isDecorationEntity(painting));

        Entity zombie = mock(Zombie.class);
        assertFalse(service.isDecorationEntity(zombie));
    }
}

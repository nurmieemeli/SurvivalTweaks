package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.SurvivalTweaks;
import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.block.data.type.Leaves;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FastLeafDecayServiceTest {

    @Mock
    private SurvivalTweaks plugin;

    @Mock
    private SettingsService settingsService;

    @Mock
    private PluginSettings settings;

    private FastLeafDecayService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(settingsService.current()).thenReturn(settings);
        service = new FastLeafDecayService(plugin, settingsService);
    }

    @Test
    void testServiceInstantiation() {
        assertNotNull(service);
    }

    @Test
    void recognizesOnlyNaturalLeaves() {
        Leaves leaves = mock(Leaves.class);

        when(leaves.isPersistent()).thenReturn(false);
        assertTrue(service.isNaturalLeaf(true, leaves));

        when(leaves.isPersistent()).thenReturn(true);
        assertFalse(service.isNaturalLeaf(true, leaves));

        assertFalse(service.isNaturalLeaf(false, leaves));
    }
}

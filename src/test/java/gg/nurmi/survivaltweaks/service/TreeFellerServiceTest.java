package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.SurvivalTweaks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class TreeFellerServiceTest {

    @Mock
    private SurvivalTweaks plugin;

    private TreeFellerService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(plugin.getName()).thenReturn("survivaltweaks");
        service = new TreeFellerService(plugin, null);
    }

    @Test
    void testCoordinatePackingUnique() {
        int packed1 = service.packCoordinate(0, 64, 0);
        int packed2 = service.packCoordinate(1, 64, 0);
        int packed3 = service.packCoordinate(0, 65, 0);
        int packed4 = service.packCoordinate(15, -64, 15);
        int packed5 = service.packCoordinate(0, 319, 0); // Max vanilla world height
        
        assertNotEquals(packed1, packed2);
        assertNotEquals(packed1, packed3);
        assertNotEquals(packed1, packed4);
        assertNotEquals(packed1, packed5);
    }

    @Test
    void testCoordinatePackingConsistency() {
        assertEquals(service.packCoordinate(10, 50, 12), service.packCoordinate(10, 50, 12));
        assertEquals(service.packCoordinate(-1, -64, -1), service.packCoordinate(15, -64, 15)); // Because chunkX = x & 15, -1 & 15 == 15
        assertEquals(service.packCoordinate(16, 320, 16), service.packCoordinate(0, 320, 0)); // 16 & 15 == 0
    }
}

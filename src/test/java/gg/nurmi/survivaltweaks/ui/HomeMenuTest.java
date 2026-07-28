package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.HomeCategory;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeMenuTest {

    @Test
    void paginationCoversEmptyPartialAndMultiplePages() {
        assertEquals(1, HomeMenu.pageCount(0));
        assertEquals(1, HomeMenu.pageCount(45));
        assertEquals(2, HomeMenu.pageCount(46));
        assertEquals(3, HomeMenu.pageCount(100));

        assertEquals(0, HomeMenu.clampedPage(-1, 100));
        assertEquals(2, HomeMenu.clampedPage(99, 100));
    }

    @Test
    void slotsMapOnlyToHomesOnTheSelectedPage() {
        assertEquals(0, HomeMenu.homeIndexAt(0, 0, 46).orElseThrow());
        assertEquals(44, HomeMenu.homeIndexAt(0, 44, 46).orElseThrow());
        assertEquals(45, HomeMenu.homeIndexAt(1, 0, 46).orElseThrow());
        assertFalse(HomeMenu.homeIndexAt(1, 1, 46).isPresent());
        assertFalse(HomeMenu.homeIndexAt(0, HomeMenu.NEXT_SLOT, 46).isPresent());
    }

    @Test
    void favoritesAndManualOrderSortBeforeNames() {
        java.util.UUID world = java.util.UUID.randomUUID();
        Home regular = new Home("Alpha", world, "world", 0, 64, 0, 0, 0);
        Home favoriteLater = new Home(
                "Zulu", world, "world", 0, 64, 0, 0, 0, Material.COMPASS, "", true, 2
        );
        Home favoriteEarlier = favoriteLater.withOrder(1).withDescription("First");

        assertTrue(HomeMenu.compareHomes(favoriteEarlier, regular) < 0);
        assertTrue(HomeMenu.compareHomes(favoriteEarlier, favoriteLater) < 0);
    }

    @Test
    void categoriesGroupHomesBeforeManualOrder() {
        java.util.UUID world = java.util.UUID.randomUUID();
        Home farm = new Home("Farm", world, "world", 0, 64, 0, 0, 0)
                .withCategory(HomeCategory.FARM)
                .withOrder(0);
        Home base = new Home("Base", world, "world", 0, 64, 0, 0, 0)
                .withCategory(HomeCategory.BASE)
                .withOrder(99);

        assertTrue(HomeMenu.compareHomes(base, farm) < 0);
    }
}

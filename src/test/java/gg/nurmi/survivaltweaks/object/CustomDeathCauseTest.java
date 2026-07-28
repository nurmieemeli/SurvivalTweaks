package gg.nurmi.survivaltweaks.object;

import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomDeathCauseTest {

    @Test
    void groupsRelatedEnvironmentalDamageCauses() {
        assertEquals(CustomDeathCause.FIRE, CustomDeathCause.from(DamageCause.LAVA).orElseThrow());
        assertEquals(CustomDeathCause.FIRE, CustomDeathCause.from(DamageCause.CAMPFIRE).orElseThrow());
        assertEquals(CustomDeathCause.FALL, CustomDeathCause.from(DamageCause.FLY_INTO_WALL).orElseThrow());
        assertEquals(CustomDeathCause.EXPLOSION, CustomDeathCause.from(DamageCause.BLOCK_EXPLOSION).orElseThrow());
    }

    @Test
    void leavesCombatAndUnclassifiedDamageToVanilla() {
        assertTrue(CustomDeathCause.from(DamageCause.ENTITY_ATTACK).isEmpty());
        assertTrue(CustomDeathCause.from(DamageCause.PROJECTILE).isEmpty());
        assertTrue(CustomDeathCause.from(DamageCause.SONIC_BOOM).isEmpty());
        assertTrue(CustomDeathCause.from(DamageCause.CUSTOM).isEmpty());
    }

    @Test
    void selectsStableCatalogKeys() {
        assertEquals("death-messages.fall.1", CustomDeathCause.FALL.messageKey(1, false));
        assertEquals("death-messages.fall.2", CustomDeathCause.FALL.messageKey(2, false));
        assertEquals("death-messages.fall.rare", CustomDeathCause.FALL.messageKey(1, true));
        assertThrows(IllegalArgumentException.class, () -> CustomDeathCause.FALL.messageKey(3, false));
    }
}

package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PetProtectionServiceTest {

    @Mock
    private SettingsService settingsService;

    @Mock
    private PluginSettings settings;

    private PetProtectionService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(settingsService.current()).thenReturn(settings);
        service = new PetProtectionService(settingsService);
    }

    @Test
    void testIsTamedPetTameable() {
        Tameable tameable = mock(Tameable.class);
        AnimalTamer owner = mock(AnimalTamer.class);

        when(tameable.isTamed()).thenReturn(false);
        assertFalse(service.isTamedPet(tameable));

        when(tameable.isTamed()).thenReturn(true);
        when(tameable.getOwner()).thenReturn(null);
        assertFalse(service.isTamedPet(tameable));

        when(tameable.getOwner()).thenReturn(owner);
        assertTrue(service.isTamedPet(tameable));
    }

    @Test
    void testIsTamedPetHorse() {
        AbstractHorse horse = mock(AbstractHorse.class);
        UUID ownerId = UUID.randomUUID();

        when(horse.isTamed()).thenReturn(false);
        assertFalse(service.isTamedPet(horse));

        when(horse.isTamed()).thenReturn(true);
        when(horse.getOwnerUniqueId()).thenReturn(null);
        assertFalse(service.isTamedPet(horse));

        when(horse.getOwnerUniqueId()).thenReturn(ownerId);
        assertTrue(service.isTamedPet(horse));
    }

    @Test
    void testGetPlayerAttackerDirect() {
        Player player = mock(Player.class);
        assertEquals(player, service.getPlayerAttacker(player));

        Entity zombie = mock(Entity.class);
        assertNull(service.getPlayerAttacker(zombie));
    }

    @Test
    void testGetPlayerAttackerProjectile() {
        Projectile arrow = mock(Projectile.class);
        Player player = mock(Player.class);
        when(arrow.getShooter()).thenReturn(player);

        assertEquals(player, service.getPlayerAttacker(arrow));

        org.bukkit.projectiles.ProjectileSource skeleton = mock(org.bukkit.projectiles.ProjectileSource.class);
        when(arrow.getShooter()).thenReturn(skeleton);
        assertNull(service.getPlayerAttacker(arrow));
    }
}

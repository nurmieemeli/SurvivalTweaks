package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class PetProtectionService implements Listener {

    private final SettingsService settings;

    public PetProtectionService(SettingsService settings) {
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        PluginSettings current = settings.current();
        if (!current.petProtectionEnabled() || !current.petPreventFriendlyFire()) {
            return;
        }

        Entity victim = event.getEntity();
        if (!isTamedPet(victim)) {
            return;
        }

        Player attacker = getPlayerAttacker(event.getDamager());
        if (attacker != null) {
            event.setCancelled(true);
        }
    }

    public boolean isTamedPet(Entity entity) {
        if (entity instanceof Tameable tameable) {
            return tameable.isTamed() && (tameable.getOwner() != null || tameable.getOwnerUniqueId() != null);
        }
        if (entity instanceof AbstractHorse horse) {
            return horse.isTamed() && horse.getOwnerUniqueId() != null;
        }
        return false;
    }

    public Player getPlayerAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }
}

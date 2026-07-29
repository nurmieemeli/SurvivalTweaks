package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;

public class DecorationProtectionService implements Listener {

    private final SettingsService settings;

    public DecorationProtectionService(SettingsService settings) {
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        PluginSettings current = settings.current();
        if (!current.decorationProtectionEnabled()) {
            return;
        }

        Entity victim = event.getEntity();
        if (!isDecorationEntity(victim)) {
            return;
        }

        if (current.decorationRequireSneakToBreak()) {
            Entity damager = event.getDamager();
            if (damager instanceof Player player) {
                if (!player.isSneaking()) {
                    event.setCancelled(true);
                }
            } else if (damager instanceof Projectile) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        PluginSettings current = settings.current();
        if (!current.decorationProtectionEnabled()) {
            return;
        }

        Entity victim = event.getEntity();
        if (!isDecorationEntity(victim)) {
            return;
        }

        if (current.decorationRequireSneakToBreak()) {
            Entity remover = event.getRemover();
            if (remover instanceof Player player) {
                if (!player.isSneaking()) {
                    event.setCancelled(true);
                }
            } else if (remover instanceof Projectile) {
                event.setCancelled(true);
            }
        }
    }

    public boolean isDecorationEntity(Entity entity) {
        return entity instanceof ItemFrame
                || entity instanceof GlowItemFrame
                || entity instanceof ArmorStand
                || entity instanceof Painting;
    }
}

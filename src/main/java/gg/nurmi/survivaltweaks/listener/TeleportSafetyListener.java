package gg.nurmi.survivaltweaks.listener;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.service.SafeTeleportService;
import gg.nurmi.survivaltweaks.ui.SurvivalTweaksMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class TeleportSafetyListener implements Listener {

    private final SafeTeleportService teleports;
    private final SettingsService settings;

    public TeleportSafetyListener(
            SafeTeleportService teleports,
            SettingsService settings
    ) {
        this.teleports = teleports;
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (settings.current().cancelTeleportOnMove()
                && teleports.isPending(event.getPlayer().getUniqueId())) {
            teleports.handleMove(event.getPlayer(), event.getTo());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (settings.current().cancelTeleportOnDamage()
                && event.getFinalDamage() > 0.0
                && event.getEntity() instanceof Player player) {
            teleports.handleDamage(player);
        }
    }

    /**
     * Only real containers are tracked. SurvivalTweaks' own menus cancel every click and drag, so
     * they hold nothing a player could take through a teleport. A player's own inventory never
     * reaches here either, because the server is not told when it is opened client-side.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player
                && !(event.getInventory().getHolder(false) instanceof SurvivalTweaksMenu)) {
            teleports.containerOpened(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        teleports.containerClosed(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        teleports.playerDisconnected(event.getPlayer().getUniqueId());
    }
}
